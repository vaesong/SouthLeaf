package cn.zhinan.admincontent.adapter.jdbc;

import cn.zhinan.admincontent.domain.ContentException;
import cn.zhinan.admincontent.domain.ContentFailure;
import cn.zhinan.admincontent.domain.ContentModels.WorkCreated;
import cn.zhinan.admincontent.domain.ContentRules;
import cn.zhinan.admincontent.domain.WorkDraft;
import cn.zhinan.admincontent.port.ContentPorts.StoredWork;
import cn.zhinan.admincontent.port.ContentPorts.WorkRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;

/** Business-writer adapter for immutable draft revisions with optimistic version checks. */
public final class JdbcWorkRepository implements WorkRepository {
  private final ContentJdbc db;

  public JdbcWorkRepository(DataSource businessWriter) {
    this.db = new ContentJdbc(businessWriter);
  }

  @Override
  public WorkCreated create(String actorId, String key, String requestSha256, Instant now) {
    var existing = findCreate(actorId, key, requestSha256);
    if (existing.isPresent()) return existing.get();
    String workId = ContentRules.newId();
    try {
      return db.write(
          () -> {
            db.jdbc.update(
                "INSERT INTO admin_work_case (work_id,current_version,created_by,create_idempotency_key,create_request_sha256,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
                workId,
                1,
                actorId,
                key,
                requestSha256,
                ContentJdbc.ts(now),
                ContentJdbc.ts(now));
            insertVersion(workId, 1, WorkDraft.empty(), actorId, now);
            return new WorkCreated(workId, 1, false);
          });
    } catch (DuplicateKeyException race) {
      return findCreate(actorId, key, requestSha256)
          .orElseThrow(() -> new ContentException(ContentFailure.STORAGE_UNAVAILABLE));
    }
  }

  private Optional<WorkCreated> findCreate(String actorId, String key, String requestSha256) {
    return db.read(
        () ->
            db
                .jdbc
                .query(
                    "SELECT work_id,create_request_sha256 FROM admin_work_case WHERE created_by=? AND create_idempotency_key=?",
                    (rs, n) -> {
                      if (!requestSha256.equals(rs.getString(2)))
                        throw new ContentException(
                            ContentFailure.IDEMPOTENCY_CONFLICT, "IDEMPOTENCY_KEY_REUSED");
                      return new WorkCreated(rs.getString(1), 1, true);
                    },
                    actorId,
                    key)
                .stream()
                .findFirst());
  }

  @Override
  public Optional<StoredWork> find(String workId) {
    return db.read(() -> load(workId));
  }

  private Optional<StoredWork> load(String workId) {
    var cases =
        db.jdbc.queryForList(
            "SELECT c.current_version,c.updated_at,v.title,v.summary,v.subject_scope_ref,v.cover_media_id FROM admin_work_case c JOIN admin_work_version v ON v.work_id=c.work_id AND v.version=c.current_version WHERE c.work_id=?",
            workId);
    if (cases.isEmpty()) return Optional.empty();
    var row = cases.get(0);
    int version = ((Number) row.get("current_version")).intValue();
    List<String> businesses =
        db.jdbc.queryForList(
            "SELECT business_id FROM admin_work_version_business WHERE work_id=? AND version=? ORDER BY position",
            String.class,
            workId,
            version);
    List<WorkDraft.MediaBinding> media =
        db.jdbc.query(
            "SELECT media_id,alt_text FROM admin_work_version_media WHERE work_id=? AND version=? ORDER BY position",
            (rs, n) -> new WorkDraft.MediaBinding(rs.getString(1), rs.getString(2)),
            workId,
            version);
    var draft =
        new WorkDraft(
            (String) row.get("title"),
            (String) row.get("summary"),
            (String) row.get("subject_scope_ref"),
            businesses,
            media,
            (String) row.get("cover_media_id"));
    return Optional.of(
        new StoredWork(workId, version, draft, ContentJdbc.instant(row.get("updated_at"))));
  }

  @Override
  public int saveDraft(
      String workId, int expectedVersion, WorkDraft draft, String actorId, Instant now) {
    return db.write(
        () -> {
          List<Integer> current =
              db.jdbc.queryForList(
                  "SELECT current_version FROM admin_work_case WHERE work_id=? FOR UPDATE",
                  Integer.class,
                  workId);
          if (current.isEmpty())
            throw new ContentException(ContentFailure.NOT_FOUND, "WORK_UNKNOWN");
          if (current.get(0) != expectedVersion)
            throw new ContentException(ContentFailure.VERSION_CONFLICT, "VERSION_CONFLICT");
          int next = expectedVersion + 1;
          insertVersion(workId, next, draft, actorId, now);
          db.jdbc.update(
              "UPDATE admin_work_case SET current_version=?, updated_at=? WHERE work_id=? AND current_version=?",
              next,
              ContentJdbc.ts(now),
              workId,
              expectedVersion);
          return next;
        });
  }

  private void insertVersion(
      String workId, int version, WorkDraft draft, String actorId, Instant now) {
    db.jdbc.update(
        "INSERT INTO admin_work_version (work_id,version,title,summary,subject_scope_ref,cover_media_id,created_by,created_at) VALUES (?,?,?,?,?,?,?,?)",
        workId,
        version,
        draft.title(),
        draft.summary(),
        draft.subjectScopeRef(),
        draft.coverMediaId(),
        actorId,
        ContentJdbc.ts(now));
    for (int i = 0; i < draft.businessIds().size(); i++)
      db.jdbc.update(
          "INSERT INTO admin_work_version_business (work_id,version,position,business_id) VALUES (?,?,?,?)",
          workId,
          version,
          i,
          draft.businessIds().get(i));
    for (int i = 0; i < draft.media().size(); i++)
      db.jdbc.update(
          "INSERT INTO admin_work_version_media (work_id,version,media_id,position,alt_text) VALUES (?,?,?,?,?)",
          workId,
          version,
          draft.media().get(i).mediaId(),
          i,
          draft.media().get(i).altText());
  }

  @Override
  public List<StoredWork> list(int limit) {
    return db.read(
        () ->
            db
                .named
                .queryForList(
                    "SELECT work_id FROM admin_work_case ORDER BY updated_at DESC, work_id LIMIT :limit",
                    Map.of("limit", limit),
                    String.class)
                .stream()
                .map(this::load)
                .flatMap(Optional::stream)
                .toList());
  }
}
