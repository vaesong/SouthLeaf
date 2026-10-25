package cn.zhinan.admincontent.adapter.jdbc;

import cn.zhinan.admincontent.domain.AuthorizationGate;
import cn.zhinan.admincontent.domain.ContentException;
import cn.zhinan.admincontent.domain.ContentFailure;
import cn.zhinan.admincontent.domain.ContentMode;
import cn.zhinan.admincontent.domain.ContentModels.PublicationResult;
import cn.zhinan.admincontent.domain.ContentModels.PublicationState;
import cn.zhinan.admincontent.domain.ContentModels.Variant;
import cn.zhinan.admincontent.domain.ContentRules;
import cn.zhinan.admincontent.domain.WorkDraft;
import cn.zhinan.admincontent.port.ContentPorts.PublicationCommand;
import cn.zhinan.admincontent.port.ContentPorts.PublicationRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;

/**
 * Publication-writer adapter. One InnoDB transaction spans the management schema (default schema of
 * the connection) and fully-qualified public V2 overlay tables. It never touches V1 tables.
 *
 * <p>Lock order (all publication commands): projection epoch row FOR UPDATE (global serialization
 * point) → command key lookup → work case/version/bindings/media/gate FOR SHARE → publication state
 * FOR UPDATE → active pointer FOR UPDATE → inserts/updates → epoch increment. Draft writers never
 * lock the epoch, so no wait cycle exists between the two writers.
 */
public final class JdbcPublicationRepository implements PublicationRepository {
  private static final Pattern SCHEMA = Pattern.compile("[a-z][a-z0-9_]{0,63}");
  private final ContentJdbc db;
  private final String pub;
  private final ContentMode mode;

  public JdbcPublicationRepository(
      DataSource publicationWriter, String publicSchema, ContentMode mode) {
    if (publicSchema == null || !SCHEMA.matcher(publicSchema).matches())
      throw new IllegalArgumentException("Public schema rejected");
    this.db = new ContentJdbc(publicationWriter);
    this.pub = "`" + publicSchema + "`.";
    this.mode = mode;
  }

  @Override
  public PublicationResult publish(PublicationCommand c) {
    return execute(c, () -> doPublish(c));
  }

  @Override
  public PublicationResult withdraw(PublicationCommand c) {
    return execute(c, () -> doWithdraw(c));
  }

  private PublicationResult execute(
      PublicationCommand c, java.util.function.Supplier<PublicationResult> body) {
    try {
      return db.write(body);
    } catch (DuplicateKeyException race) {
      // Unreachable under the epoch lock; still resolve by the stored command, never by guessing.
      return findCommand(c.actorId(), c.operation(), c.workId(), c.idempotencyKey())
          .orElseThrow(() -> new ContentException(ContentFailure.STORAGE_UNAVAILABLE));
    }
  }

  private Optional<PublicationResult> lockAndReplay(PublicationCommand c) {
    List<Long> epoch =
        db.jdbc.queryForList(
            "SELECT epoch FROM "
                + pub
                + "public_work_projection_epoch WHERE singleton_id=1 FOR UPDATE",
            Long.class);
    if (epoch.size() != 1)
      throw new ContentException(ContentFailure.STORAGE_UNAVAILABLE, "EPOCH_MISSING");
    var stored =
        db.jdbc.queryForList(
            "SELECT command_id,request_sha256,target_version,release_id,completed_at FROM admin_publication_command WHERE actor_id=? AND operation=? AND work_id=? AND idempotency_key=?",
            c.actorId(),
            c.operation(),
            c.workId(),
            c.idempotencyKey());
    if (stored.isEmpty()) return Optional.empty();
    var row = stored.get(0);
    if (!c.requestSha256().equals(row.get("request_sha256")))
      throw new ContentException(ContentFailure.IDEMPOTENCY_CONFLICT, "IDEMPOTENCY_KEY_REUSED");
    return Optional.of(result(row, c.operation(), c.workId(), true));
  }

  private int lockCase(PublicationCommand c) {
    List<Integer> current =
        db.jdbc.queryForList(
            "SELECT current_version FROM admin_work_case WHERE work_id=? FOR SHARE",
            Integer.class,
            c.workId());
    if (current.isEmpty()) throw new ContentException(ContentFailure.NOT_FOUND, "WORK_UNKNOWN");
    if (current.get(0) != c.expectedVersion())
      throw new ContentException(ContentFailure.VERSION_CONFLICT, "VERSION_CONFLICT");
    return current.get(0);
  }

  private PublicationResult doPublish(PublicationCommand c) {
    var replay = lockAndReplay(c);
    if (replay.isPresent()) return replay.get();
    int version = lockCase(c);
    var versionRows =
        db.jdbc.queryForList(
            "SELECT title,summary,subject_scope_ref,cover_media_id FROM admin_work_version WHERE work_id=? AND version=? FOR SHARE",
            c.workId(),
            version);
    if (versionRows.size() != 1)
      throw new ContentException(ContentFailure.STORAGE_UNAVAILABLE, "VERSION_MISSING");
    var v = versionRows.get(0);
    List<String> businesses =
        db.jdbc.queryForList(
            "SELECT business_id FROM admin_work_version_business WHERE work_id=? AND version=? ORDER BY position FOR SHARE",
            String.class,
            c.workId(),
            version);
    List<WorkDraft.MediaBinding> bindings =
        db.jdbc.query(
            "SELECT media_id,alt_text FROM admin_work_version_media WHERE work_id=? AND version=? ORDER BY position FOR SHARE",
            (rs, n) -> new WorkDraft.MediaBinding(rs.getString(1), rs.getString(2)),
            c.workId(),
            version);
    var draft =
        new WorkDraft(
            (String) v.get("title"),
            (String) v.get("summary"),
            (String) v.get("subject_scope_ref"),
            businesses,
            bindings,
            (String) v.get("cover_media_id"));
    draft.requireComplete();
    List<String> mediaIds = draft.mediaIds();
    var ready =
        db.named.queryForList(
            "SELECT media_id FROM admin_media_asset WHERE media_id IN (:ids) AND status='READY' FOR SHARE",
            Map.of("ids", mediaIds),
            String.class);
    if (ready.size() != mediaIds.size())
      throw new ContentException(ContentFailure.MEDIA_NOT_READY, "MEDIA_NOT_READY");
    var variantRows =
        db.named.queryForList(
            "SELECT media_id,variant,object_key,sha256,byte_length,width,height,content_type FROM admin_media_variant WHERE media_id IN (:ids) ORDER BY media_id,variant FOR SHARE",
            Map.of("ids", mediaIds));
    Map<String, List<Variant>> variants = new HashMap<>();
    for (var row : variantRows)
      variants
          .computeIfAbsent((String) row.get("media_id"), k -> new ArrayList<>())
          .add(JdbcMediaRepository.variant(row));
    for (String id : mediaIds) {
      var own = variants.getOrDefault(id, List.of());
      if (own.size() != 2
          || own.stream().noneMatch(x -> x.variant().equals("thumbnail"))
          || own.stream().noneMatch(x -> x.variant().equals("preview")))
        throw new ContentException(ContentFailure.MEDIA_NOT_READY, "MEDIA_VARIANTS_INCOMPLETE");
    }
    String scopeDigest = ContentRules.mediaScopeDigest(mediaIds);
    AuthorizationGate gate = readGate(c.workId(), true).orElse(null);
    String blocked =
        AuthorizationGate.blockingReason(gate, draft.subjectScopeRef(), scopeDigest, c.now(), mode);
    if (blocked != null) throw new ContentException(ContentFailure.PUBLICATION_BLOCKED, blocked);

    lockState(c.workId());
    lockPointer(c.workId());
    String releaseId = ContentRules.newId();
    String commandId = ContentRules.newId();
    var now = ContentJdbc.ts(c.now());
    db.jdbc.update(
        "INSERT INTO "
            + pub
            + "public_work_release (release_id,work_id,work_version,title,summary,cover_media_id,subject_scope_ref,data_mode,published_at) VALUES (?,?,?,?,?,?,?,?,?)",
        releaseId,
        c.workId(),
        version,
        draft.title(),
        draft.summary(),
        draft.coverMediaId(),
        draft.subjectScopeRef(),
        mode.publicMode(),
        now);
    for (int i = 0; i < businesses.size(); i++)
      db.jdbc.update(
          "INSERT INTO "
              + pub
              + "public_work_release_business (release_id,position,business_id) VALUES (?,?,?)",
          releaseId,
          i,
          businesses.get(i));
    for (int i = 0; i < bindings.size(); i++) {
      var binding = bindings.get(i);
      db.jdbc.update(
          "INSERT INTO "
              + pub
              + "public_work_release_media (release_id,media_id,position,alt_text) VALUES (?,?,?,?)",
          releaseId,
          binding.mediaId(),
          i,
          binding.altText());
      for (Variant x : variants.get(binding.mediaId()))
        db.jdbc.update(
            "INSERT INTO "
                + pub
                + "public_work_release_variant (release_id,media_id,variant,object_key,sha256,byte_length,width,height,content_type) VALUES (?,?,?,?,?,?,?,?,?)",
            releaseId,
            binding.mediaId(),
            x.variant(),
            x.objectKey(),
            x.sha256(),
            x.byteLength(),
            x.width(),
            x.height(),
            x.contentType());
    }
    db.jdbc.update(
        "INSERT INTO "
            + pub
            + "public_work_release_authorization (release_id,authority_revision,state,subject_scope_ref,media_scope_sha256,purpose,channel,valid_from,valid_until,provenance,fact_sha256) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        releaseId,
        gate.authorityRevision(),
        gate.state(),
        gate.subjectScopeRef(),
        gate.mediaScopeSha256(),
        gate.purpose(),
        gate.channel(),
        ContentJdbc.ts(gate.validFrom()),
        ContentJdbc.ts(gate.validUntil()),
        gate.provenance(),
        gate.factSha256());
    int moved =
        db.jdbc.update(
            "UPDATE "
                + pub
                + "active_work_publication SET release_id=?, visible=1, changed_at=? WHERE work_id=?",
            releaseId,
            now,
            c.workId());
    if (moved == 0)
      db.jdbc.update(
          "INSERT INTO "
              + pub
              + "active_work_publication (work_id,release_id,visible,changed_at) VALUES (?,?,1,?)",
          c.workId(),
          releaseId,
          now);
    bumpEpoch(now);
    writeState(c.workId(), "PUBLISHED", version, releaseId, commandId, now);
    return record(c, commandId, version, releaseId, now);
  }

  private PublicationResult doWithdraw(PublicationCommand c) {
    var replay = lockAndReplay(c);
    if (replay.isPresent()) return replay.get();
    lockCase(c);
    var state = lockState(c.workId());
    if (state == null || !"PUBLISHED".equals(state.get("state")))
      throw new ContentException(ContentFailure.NOT_PUBLISHED, "NOT_PUBLISHED");
    String releaseId = (String) state.get("release_id");
    int publishedVersion = ((Number) state.get("published_version")).intValue();
    var pointer = lockPointer(c.workId());
    if (pointer == null
        || !releaseId.equals(pointer.get("release_id"))
        || !isVisible(pointer.get("visible")))
      throw new ContentException(ContentFailure.STORAGE_UNAVAILABLE, "PROJECTION_INCONSISTENT");
    var now = ContentJdbc.ts(c.now());
    db.jdbc.update(
        "UPDATE "
            + pub
            + "active_work_publication SET visible=0, changed_at=? WHERE work_id=? AND release_id=?",
        now,
        c.workId(),
        releaseId);
    bumpEpoch(now);
    String commandId = ContentRules.newId();
    writeState(c.workId(), "WITHDRAWN", publishedVersion, releaseId, commandId, now);
    return record(c, commandId, publishedVersion, releaseId, now);
  }

  static boolean isVisible(Object value) {
    return value instanceof Boolean flag
        ? flag
        : value instanceof Number number && number.intValue() == 1;
  }

  private Map<String, Object> lockState(String workId) {
    var rows =
        db.jdbc.queryForList(
            "SELECT state,published_version,release_id FROM admin_work_publication_state WHERE work_id=? FOR UPDATE",
            workId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private Map<String, Object> lockPointer(String workId) {
    var rows =
        db.jdbc.queryForList(
            "SELECT release_id,visible FROM "
                + pub
                + "active_work_publication WHERE work_id=? FOR UPDATE",
            workId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private void bumpEpoch(java.sql.Timestamp now) {
    if (db.jdbc.update(
            "UPDATE "
                + pub
                + "public_work_projection_epoch SET epoch=epoch+1, changed_at=? WHERE singleton_id=1",
            now)
        != 1) throw new ContentException(ContentFailure.STORAGE_UNAVAILABLE, "EPOCH_MISSING");
  }

  private void writeState(
      String workId,
      String state,
      int version,
      String releaseId,
      String commandId,
      java.sql.Timestamp now) {
    int updated =
        db.jdbc.update(
            "UPDATE admin_work_publication_state SET state=?, published_version=?, release_id=?, last_command_id=?, changed_at=? WHERE work_id=?",
            state,
            version,
            releaseId,
            commandId,
            now,
            workId);
    if (updated == 0)
      db.jdbc.update(
          "INSERT INTO admin_work_publication_state (work_id,state,published_version,release_id,last_command_id,changed_at) VALUES (?,?,?,?,?,?)",
          workId,
          state,
          version,
          releaseId,
          commandId,
          now);
  }

  private PublicationResult record(
      PublicationCommand c,
      String commandId,
      int version,
      String releaseId,
      java.sql.Timestamp now) {
    db.jdbc.update(
        "INSERT INTO admin_publication_command (command_id,actor_id,operation,work_id,idempotency_key,request_sha256,target_version,release_id,outcome,completed_at) VALUES (?,?,?,?,?,?,?,?,'SUCCEEDED',?)",
        commandId,
        c.actorId(),
        c.operation(),
        c.workId(),
        c.idempotencyKey(),
        c.requestSha256(),
        version,
        releaseId,
        now);
    db.jdbc.update(
        "INSERT INTO admin_publication_audit (audit_id,command_id,actor_id,operation,work_id,work_version,release_id,occurred_at) VALUES (?,?,?,?,?,?,?,?)",
        ContentRules.newId(),
        commandId,
        c.actorId(),
        c.operation(),
        c.workId(),
        version,
        releaseId,
        now);
    return new PublicationResult(
        commandId, c.operation(), c.workId(), version, releaseId, c.now(), false);
  }

  private static PublicationResult result(
      Map<String, Object> row, String operation, String workId, boolean replayed) {
    return new PublicationResult(
        (String) row.get("command_id"),
        operation,
        workId,
        ((Number) row.get("target_version")).intValue(),
        (String) row.get("release_id"),
        ContentJdbc.instant(row.get("completed_at")),
        replayed);
  }

  @Override
  public Optional<PublicationResult> findCommand(
      String actorId, String operation, String workId, String key) {
    return db.read(
        () ->
            db
                .jdbc
                .queryForList(
                    "SELECT command_id,target_version,release_id,completed_at FROM admin_publication_command WHERE actor_id=? AND operation=? AND work_id=? AND idempotency_key=?",
                    actorId,
                    operation,
                    workId,
                    key)
                .stream()
                .findFirst()
                .map(row -> result(row, operation, workId, true)));
  }

  @Override
  public Optional<PublicationResult> commandById(String commandId) {
    return db.read(
        () ->
            db
                .jdbc
                .queryForList(
                    "SELECT command_id,operation,work_id,target_version,release_id,completed_at FROM admin_publication_command WHERE command_id=?",
                    commandId)
                .stream()
                .findFirst()
                .map(
                    row ->
                        result(
                            row,
                            (String) row.get("operation"),
                            (String) row.get("work_id"),
                            true)));
  }

  @Override
  public Map<String, PublicationState> states(Collection<String> workIds) {
    if (workIds.isEmpty()) return Map.of();
    return db.read(
        () -> {
          var result = new HashMap<String, PublicationState>();
          for (var row :
              db.named.queryForList(
                  "SELECT work_id,state,published_version,release_id FROM admin_work_publication_state WHERE work_id IN (:ids)",
                  Map.of("ids", List.copyOf(workIds))))
            result.put(
                (String) row.get("work_id"),
                new PublicationState(
                    (String) row.get("work_id"),
                    (String) row.get("state"),
                    ((Number) row.get("published_version")).intValue(),
                    (String) row.get("release_id")));
          return Map.copyOf(result);
        });
  }

  @Override
  public Optional<AuthorizationGate> gate(String workId) {
    return db.read(() -> readGate(workId, false));
  }

  private Optional<AuthorizationGate> readGate(String workId, boolean lock) {
    return db
        .jdbc
        .queryForList(
            "SELECT authority_revision,state,subject_scope_ref,media_scope_sha256,purpose,channel,valid_from,valid_until,provenance,fact_sha256 FROM admin_work_authorization_gate WHERE work_id=?"
                + (lock ? " FOR SHARE" : ""),
            workId)
        .stream()
        .findFirst()
        .map(
            g ->
                new AuthorizationGate(
                    (String) g.get("authority_revision"),
                    (String) g.get("state"),
                    (String) g.get("subject_scope_ref"),
                    (String) g.get("media_scope_sha256"),
                    (String) g.get("purpose"),
                    (String) g.get("channel"),
                    ContentJdbc.instant(g.get("valid_from")),
                    ContentJdbc.instant(g.get("valid_until")),
                    (String) g.get("provenance"),
                    (String) g.get("fact_sha256")));
  }
}
