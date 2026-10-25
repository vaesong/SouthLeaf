package cn.zhinan.admincontent.adapter.jdbc;

import cn.zhinan.admincontent.domain.ContentException;
import cn.zhinan.admincontent.domain.ContentFailure;
import cn.zhinan.admincontent.port.CatalogPorts.CatalogDraftRepository;
import cn.zhinan.admincontent.port.CatalogPorts.DraftRevision;
import cn.zhinan.admincontent.port.CatalogPorts.DraftVersion;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;

/**
 * Append-only draft revisions with optimistic concurrency; the business identity can only write V6.
 */
public final class JdbcCatalogDraftRepository implements CatalogDraftRepository {
  private final ContentJdbc jdbc;

  public JdbcCatalogDraftRepository(DataSource businessDataSource) {
    this.jdbc = new ContentJdbc(businessDataSource);
  }

  @Override
  public Optional<DraftRevision> current() {
    return jdbc.read(
        () -> {
          List<Integer> heads =
              jdbc.jdbc.query(
                  "SELECT current_revision FROM admin_catalog_draft WHERE singleton_id=1",
                  (rs, row) -> rs.getInt(1));
          if (heads.isEmpty() || heads.getFirst() == 0) return Optional.empty();
          return jdbc
              .jdbc
              .query(
                  "SELECT revision,payload_json,updated_by,updated_at FROM admin_catalog_draft_revision WHERE revision=?",
                  (rs, row) ->
                      new DraftRevision(
                          rs.getInt(1),
                          rs.getString(2),
                          rs.getString(3),
                          ContentJdbc.instant(rs.getObject(4))),
                  heads.getFirst())
              .stream()
              .findFirst();
        });
  }

  @Override
  public List<DraftVersion> history() {
    return jdbc.read(
        () ->
            jdbc.jdbc.query(
                "SELECT revision,updated_by,updated_at FROM admin_catalog_draft_revision ORDER BY revision DESC",
                (rs, row) ->
                    new DraftVersion(
                        rs.getInt(1), rs.getString(2), ContentJdbc.instant(rs.getObject(3)))));
  }

  @Override
  public Optional<DraftRevision> version(int revision) {
    if (revision < 1) return Optional.empty();
    return jdbc.read(
        () ->
            jdbc
                .jdbc
                .query(
                    "SELECT revision,payload_json,updated_by,updated_at FROM admin_catalog_draft_revision WHERE revision=?",
                    (rs, row) ->
                        new DraftRevision(
                            rs.getInt(1),
                            rs.getString(2),
                            rs.getString(3),
                            ContentJdbc.instant(rs.getObject(4))),
                    revision)
                .stream()
                .findFirst());
  }

  @Override
  public int save(int expectedRevision, String payloadJson, String actorId, Instant now) {
    try {
      return jdbc.write(
          () -> {
            if (expectedRevision == 0) {
              try {
                jdbc.jdbc.update(
                    "INSERT INTO admin_catalog_draft (singleton_id,current_revision) VALUES (1,0)");
              } catch (DuplicateKeyException alreadyCreated) {
                // Continue under the row lock; the expected revision check decides the winner.
              }
            }
            List<Integer> heads =
                jdbc.jdbc.query(
                    "SELECT current_revision FROM admin_catalog_draft WHERE singleton_id=1 FOR UPDATE",
                    (rs, row) -> rs.getInt(1));
            int current = heads.isEmpty() ? 0 : heads.getFirst();
            if (current != expectedRevision)
              throw new ContentException(
                  ContentFailure.VERSION_CONFLICT, "CATALOG_REVISION_CONFLICT");
            int next = Math.addExact(current, 1);
            jdbc.jdbc.update(
                "INSERT INTO admin_catalog_draft_revision (revision,payload_json,updated_by,updated_at) VALUES (?,?,?,?)",
                next,
                payloadJson,
                actorId,
                ContentJdbc.ts(now));
            if (heads.isEmpty()) {
              jdbc.jdbc.update(
                  "INSERT INTO admin_catalog_draft (singleton_id,current_revision) VALUES (1,?)",
                  next);
            } else {
              jdbc.jdbc.update(
                  "UPDATE admin_catalog_draft SET current_revision=? WHERE singleton_id=1", next);
            }
            return next;
          });
    } catch (DuplicateKeyException race) {
      throw new ContentException(ContentFailure.VERSION_CONFLICT, "CATALOG_REVISION_CONFLICT");
    }
  }
}
