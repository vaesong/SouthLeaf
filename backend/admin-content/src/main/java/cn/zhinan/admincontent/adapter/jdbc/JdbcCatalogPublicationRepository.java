package cn.zhinan.admincontent.adapter.jdbc;

import cn.zhinan.admincontent.domain.ContentException;
import cn.zhinan.admincontent.domain.ContentFailure;
import cn.zhinan.admincontent.domain.ContentMode;
import cn.zhinan.admincontent.domain.ContentRules;
import cn.zhinan.admincontent.port.CatalogPorts.CatalogMediaReference;
import cn.zhinan.admincontent.port.CatalogPorts.CatalogPublicationRepository;
import cn.zhinan.admincontent.port.CatalogPorts.PublishCommand;
import cn.zhinan.admincontent.port.CatalogPorts.PublishResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;

/** One InnoDB transaction writes the private command/audit and switches the public V3 pointer. */
public final class JdbcCatalogPublicationRepository implements CatalogPublicationRepository {
  private record StoredCommand(PublishResult result, String digest) {}

  private final ContentJdbc db;
  private final String publicSchema;
  private final ContentMode mode;

  public JdbcCatalogPublicationRepository(
      DataSource publicationWriter, String publicSchema, ContentMode mode) {
    if (publicSchema == null || !publicSchema.matches("[a-z][a-z0-9_]{0,63}") || mode == null)
      throw new IllegalArgumentException("Catalog publication configuration rejected");
    this.db = new ContentJdbc(publicationWriter);
    this.publicSchema = "`" + publicSchema + "`";
    this.mode = mode;
  }

  @Override
  public void validateMedia(List<CatalogMediaReference> references) {
    db.read(
        () -> {
          qualifyMedia(references, false);
          return null;
        });
  }

  @Override
  public void validateWorkReferences(List<String> workIds, Instant now) {
    db.read(
        () -> {
          qualifyWorkReferences(workIds, now, false);
          return null;
        });
  }

  @Override
  public Optional<PublishResult> findReplay(String actorId, String key, String digest) {
    return db.read(
        () -> storedCommand(actorId, "PUBLISH", key).map(value -> replay(value, digest)));
  }

  @Override
  public PublishResult publish(PublishCommand command) {
    var existing = storedCommand(command.actorId(), "PUBLISH", command.idempotencyKey());
    if (existing.isPresent()) return replay(existing.get(), command.requestSha256());
    String commandId = ContentRules.newId();
    String releaseId = ContentRules.newId();
    try {
      return db.write(
          () -> {
            var replay = storedCommand(command.actorId(), "PUBLISH", command.idempotencyKey());
            if (replay.isPresent()) return replay(replay.get(), command.requestSha256());
            int currentRevision =
                db.jdbc.queryForObject(
                    "SELECT current_revision FROM admin_catalog_draft WHERE singleton_id=1 FOR SHARE",
                    Integer.class);
            if (currentRevision != command.expectedRevision())
              throw new ContentException(
                  ContentFailure.VERSION_CONFLICT, "CATALOG_REVISION_CONFLICT");
            var qualifiedMedia = qualifyMedia(command.mediaReferences(), true);
            qualifyWorkReferences(command.featuredWorkIds(), command.now(), true);
            int epoch =
                db.jdbc.queryForObject(
                    "SELECT epoch FROM "
                        + publicSchema
                        + ".active_catalog_publication WHERE singleton_id=1 FOR UPDATE",
                    Integer.class);
            Instant now = command.now();
            String version = "catalog-" + releaseId;
            db.jdbc.update(
                "INSERT INTO "
                    + publicSchema
                    + ".public_catalog_release "
                    + "(release_id,revision,content_version,data_mode,snapshot_json,published_at) VALUES (?,?,?,?,?,?)",
                releaseId,
                currentRevision,
                version,
                mode.name(),
                command.payloadJson(),
                ContentJdbc.ts(now));
            projectMedia(releaseId, command.mediaReferences(), qualifiedMedia);
            int switched =
                db.jdbc.update(
                    "UPDATE "
                        + publicSchema
                        + ".active_catalog_publication "
                        + "SET release_id=?,visible=1,epoch=? WHERE singleton_id=1 AND epoch=?",
                    releaseId,
                    Math.addExact(epoch, 1),
                    epoch);
            if (switched != 1)
              throw new ContentException(
                  ContentFailure.VERSION_CONFLICT, "CATALOG_POINTER_CONFLICT");
            insertCommand(commandId, command.actorId(), "PUBLISH", command, releaseId, now);
            insertAudit(commandId, command.actorId(), "PUBLISH", currentRevision, releaseId, now);
            return new PublishResult(commandId, releaseId, version, currentRevision, now, false);
          });
    } catch (DuplicateKeyException raced) {
      return storedCommand(command.actorId(), "PUBLISH", command.idempotencyKey())
          .map(value -> replay(value, command.requestSha256()))
          .orElseThrow(() -> new ContentException(ContentFailure.STORAGE_UNAVAILABLE));
    }
  }

  @Override
  public void withdraw(
      int expectedRevision,
      String actorId,
      String idempotencyKey,
      String requestSha256,
      Instant now) {
    var existing = storedCommand(actorId, "WITHDRAW", idempotencyKey);
    if (existing.isPresent()) {
      replay(existing.get(), requestSha256);
      return;
    }
    String commandId = ContentRules.newId();
    try {
      db.write(
          () -> {
            var replay = storedCommand(actorId, "WITHDRAW", idempotencyKey);
            if (replay.isPresent()) {
              replay(replay.get(), requestSha256);
              return null;
            }
            int revision =
                db.jdbc.queryForObject(
                    "SELECT current_revision FROM admin_catalog_draft WHERE singleton_id=1 FOR SHARE",
                    Integer.class);
            if (revision != expectedRevision)
              throw new ContentException(
                  ContentFailure.VERSION_CONFLICT, "CATALOG_REVISION_CONFLICT");
            var pointer =
                db.jdbc.queryForList(
                    "SELECT release_id,epoch FROM "
                        + publicSchema
                        + ".active_catalog_publication WHERE singleton_id=1 AND visible=1 FOR UPDATE");
            if (pointer.isEmpty())
              throw new ContentException(ContentFailure.NOT_PUBLISHED, "CATALOG_NOT_PUBLISHED");
            String releaseId = (String) pointer.getFirst().get("release_id");
            int epoch = ((Number) pointer.getFirst().get("epoch")).intValue();
            int withdrawn =
                db.jdbc.update(
                    "UPDATE "
                        + publicSchema
                        + ".active_catalog_publication SET visible=0,epoch=? WHERE singleton_id=1 AND visible=1 AND epoch=?",
                    Math.addExact(epoch, 1),
                    epoch);
            if (withdrawn != 1)
              throw new ContentException(
                  ContentFailure.VERSION_CONFLICT, "CATALOG_POINTER_CONFLICT");
            PublishCommand command =
                new PublishCommand(
                    actorId,
                    expectedRevision,
                    idempotencyKey,
                    requestSha256,
                    "{}",
                    List.of(),
                    List.of(),
                    now);
            insertCommand(commandId, actorId, "WITHDRAW", command, releaseId, now);
            insertAudit(commandId, actorId, "WITHDRAW", revision, releaseId, now);
            return null;
          });
    } catch (DuplicateKeyException raced) {
      StoredCommand racedCommand =
          storedCommand(actorId, "WITHDRAW", idempotencyKey)
              .orElseThrow(() -> new ContentException(ContentFailure.STORAGE_UNAVAILABLE));
      replay(racedCommand, requestSha256);
    }
  }

  @Override
  public Optional<PublishResult> findCommand(String actorId, String key) {
    return db.read(
        () ->
            storedCommand(actorId, "PUBLISH", key)
                .or(() -> storedCommand(actorId, "WITHDRAW", key))
                .map(StoredCommand::result));
  }

  @Override
  public Optional<PublishResult> commandById(String commandId) {
    return db.read(
        () ->
            db
                .jdbc
                .query(
                    "SELECT command_id,result_release_id,expected_revision,completed_at "
                        + "FROM admin_catalog_command WHERE command_id=?",
                    (rs, n) ->
                        new PublishResult(
                            rs.getString(1),
                            rs.getString(2),
                            "catalog-" + rs.getString(2),
                            rs.getInt(3),
                            ContentJdbc.instant(rs.getObject(4)),
                            true),
                    commandId)
                .stream()
                .findFirst());
  }

  private Optional<StoredCommand> storedCommand(String actorId, String operation, String key) {
    return db
        .jdbc
        .query(
            "SELECT command_id,result_release_id,expected_revision,completed_at,request_sha256 "
                + "FROM admin_catalog_command WHERE actor_id=? AND operation=? AND idempotency_key=?",
            (rs, n) ->
                new StoredCommand(
                    new PublishResult(
                        rs.getString(1),
                        rs.getString(2),
                        "catalog-" + rs.getString(2),
                        rs.getInt(3),
                        ContentJdbc.instant(rs.getObject(4)),
                        true),
                    rs.getString(5)),
            actorId,
            operation,
            key)
        .stream()
        .findFirst();
  }

  private static PublishResult replay(StoredCommand prior, String digest) {
    if (!prior.digest().equals(digest))
      throw new ContentException(ContentFailure.IDEMPOTENCY_CONFLICT, "IDEMPOTENCY_KEY_REUSED");
    PublishResult result = prior.result();
    return new PublishResult(
        result.commandId(),
        result.releaseId(),
        result.contentVersion(),
        result.revision(),
        result.completedAt(),
        true);
  }

  private void qualifyWorkReferences(List<String> workIds, Instant now, boolean lock) {
    String lockClause = lock ? " FOR SHARE" : "";
    for (String workId : workIds.stream().distinct().sorted().toList()) {
      var states =
          db.jdbc.queryForList(
              "SELECT state,release_id FROM admin_work_publication_state WHERE work_id=?"
                  + lockClause,
              workId);
      var gates =
          db.jdbc.queryForList(
              "SELECT state,valid_from,valid_until,provenance FROM admin_work_authorization_gate WHERE work_id=?"
                  + lockClause,
              workId);
      var pointers =
          db.jdbc.queryForList(
              "SELECT visible,release_id FROM "
                  + publicSchema
                  + ".active_work_publication WHERE work_id=?"
                  + lockClause,
              workId);
      boolean valid = states.size() == 1 && gates.size() == 1 && pointers.size() == 1;
      if (valid) {
        var state = states.getFirst();
        var gate = gates.getFirst();
        var pointer = pointers.getFirst();
        valid =
            "PUBLISHED".equals(state.get("state"))
                && "VALID".equals(gate.get("state"))
                && mode.acceptedProvenance().equals(gate.get("provenance"))
                && now.isBefore(ContentJdbc.instant(gate.get("valid_until")))
                && !now.isBefore(ContentJdbc.instant(gate.get("valid_from")))
                && isVisible(pointer.get("visible"))
                && Objects.equals(state.get("release_id"), pointer.get("release_id"));
      }
      if (!valid)
        throw new ContentException(ContentFailure.PUBLICATION_BLOCKED, "WORK_NOT_PUBLISHABLE");
    }
  }

  private static boolean isVisible(Object value) {
    return Boolean.TRUE.equals(value)
        || (value instanceof Number n && n.intValue() == 1)
        || "1".equals(value);
  }

  private record VariantRow(
      String variant,
      String objectKey,
      String sha256,
      int byteLength,
      int width,
      int height,
      String contentType) {}

  private Map<String, List<VariantRow>> qualifyMedia(
      List<CatalogMediaReference> references, boolean lock) {
    var result = new java.util.LinkedHashMap<String, List<VariantRow>>();
    references.stream()
        .map(CatalogMediaReference::mediaId)
        .distinct()
        .sorted()
        .forEach(id -> result.put(id, qualifyOne(id, lock)));
    return result;
  }

  private List<VariantRow> qualifyOne(String mediaId, boolean lock) {
    String suffix = lock ? " FOR SHARE" : "";
    List<String> statuses =
        db.jdbc.query(
            "SELECT status FROM admin_media_asset WHERE media_id=?" + suffix,
            (rs, n) -> rs.getString(1),
            mediaId);
    if (!statuses.equals(List.of("READY")))
      throw new ContentException(ContentFailure.MEDIA_NOT_READY, "MEDIA_NOT_READY");
    var variants =
        db.jdbc.query(
            "SELECT variant,object_key,sha256,byte_length,width,height,content_type "
                + "FROM admin_media_variant WHERE media_id=? ORDER BY variant"
                + suffix,
            (rs, n) ->
                new VariantRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getInt(4),
                    rs.getInt(5),
                    rs.getInt(6),
                    rs.getString(7)),
            mediaId);
    if (variants.size() != 2
        || !variants.stream()
            .map(VariantRow::variant)
            .collect(java.util.stream.Collectors.toSet())
            .equals(java.util.Set.of("thumbnail", "preview")))
      throw new ContentException(ContentFailure.MEDIA_NOT_READY, "MEDIA_VARIANTS_INCOMPLETE");
    return variants;
  }

  private void projectMedia(
      String releaseId,
      List<CatalogMediaReference> references,
      Map<String, List<VariantRow>> qualifiedMedia) {
    var byId = new java.util.LinkedHashMap<String, CatalogMediaReference>();
    for (var reference : references) {
      CatalogMediaReference previous = byId.putIfAbsent(reference.mediaId(), reference);
      if (previous != null && !previous.altText().equals(reference.altText()))
        throw new ContentException(ContentFailure.INVALID_REQUEST, "MEDIA_ALT_CONFLICT");
    }
    byId.forEach(
        (mediaId, reference) -> {
          var variants = qualifiedMedia.get(mediaId);
          if (variants == null)
            throw new ContentException(ContentFailure.MEDIA_NOT_READY, "MEDIA_NOT_READY");
          db.jdbc.update(
              "INSERT INTO "
                  + publicSchema
                  + ".public_catalog_release_media "
                  + "(release_id,media_id,alt_text) VALUES (?,?,?)",
              releaseId,
              mediaId,
              reference.altText());
          for (var variant : variants)
            db.jdbc.update(
                "INSERT INTO "
                    + publicSchema
                    + ".public_catalog_release_variant (release_id,media_id,variant,object_key,sha256,byte_length,width,height,content_type) "
                    + "VALUES (?,?,?,?,?,?,?,?,?)",
                releaseId,
                mediaId,
                variant.variant(),
                variant.objectKey(),
                variant.sha256(),
                variant.byteLength(),
                variant.width(),
                variant.height(),
                variant.contentType());
        });
    for (var reference : references)
      db.jdbc.update(
          "INSERT INTO "
              + publicSchema
              + ".public_catalog_release_binding (release_id,media_id,owner_type,owner_id,position) VALUES (?,?,?,?,?)",
          releaseId,
          reference.mediaId(),
          reference.ownerType(),
          reference.ownerId(),
          reference.position());
  }

  private void insertCommand(
      String id,
      String actor,
      String operation,
      PublishCommand command,
      String release,
      Instant now) {
    db.jdbc.update(
        "INSERT INTO admin_catalog_command (command_id,actor_id,operation,idempotency_key,"
            + "request_sha256,expected_revision,result_release_id,outcome,completed_at) VALUES (?,?,?,?,?,?,?,'SUCCEEDED',?)",
        id,
        actor,
        operation,
        command.idempotencyKey(),
        command.requestSha256(),
        command.expectedRevision(),
        release,
        ContentJdbc.ts(now));
  }

  private void insertAudit(
      String command, String actor, String operation, int revision, String release, Instant now) {
    db.jdbc.update(
        "INSERT INTO admin_catalog_audit (audit_id,command_id,actor_id,operation,"
            + "target_revision,result_release_id,created_at) VALUES (?,?,?,?,?,?,?)",
        ContentRules.newId(),
        command,
        actor,
        operation,
        revision,
        release,
        ContentJdbc.ts(now));
  }
}
