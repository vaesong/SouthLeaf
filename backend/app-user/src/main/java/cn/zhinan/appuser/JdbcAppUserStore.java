package cn.zhinan.appuser;

import cn.zhinan.appuser.AppUserModels.Counts;
import cn.zhinan.appuser.AppUserModels.ShareParticipant;
import cn.zhinan.appuser.AppUserModels.Store;
import cn.zhinan.appuser.AppUserModels.StoredAvatar;
import cn.zhinan.appuser.AppUserModels.StoredReservation;
import cn.zhinan.appuser.AppUserModels.StoredShare;
import cn.zhinan.appuser.AppUserModels.StoredUser;
import cn.zhinan.appuser.AppUserModels.StoredVisit;
import cn.zhinan.appuser.AppUserModels.UserCensus;
import cn.zhinan.appuser.AppUserModels.UserPage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC adapter for the admin schema. The public account cannot insert a booking customer. */
public final class JdbcAppUserStore implements Store {
  private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public JdbcAppUserStore(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.transactions =
        new TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
  }

  @Override
  public Optional<StoredUser> findBySubject(String provider, String appScope, String fingerprint) {
    return one(
        USER,
        "SELECT "
            + USER_COLUMNS
            + " FROM app_user WHERE provider = ? AND app_scope = ? AND subject_fingerprint = ?",
        provider,
        appScope,
        fingerprint);
  }

  @Override
  public StoredUser insertUser(StoredUser user) {
    return write(
        () -> {
          jdbc.update(
              "INSERT INTO app_user (id, provider, app_scope, subject_fingerprint, display_name,"
                  + " note, created_at, updated_at, last_seen_at, version) VALUES (?,?,?,?,?,?,?,?,?,?)",
              user.id(),
              user.provider(),
              user.appScope(),
              user.fingerprint(),
              user.displayName(),
              user.note(),
              stamp(user.createdAt()),
              stamp(user.updatedAt()),
              stamp(user.lastSeenAt()),
              user.version());
          return user;
        });
  }

  @Override
  public void touch(String userId, Instant seenAt) {
    write(
        () -> {
          jdbc.update(
              "UPDATE app_user SET last_seen_at = ?, updated_at = ? WHERE id = ?",
              stamp(seenAt),
              stamp(seenAt),
              userId);
          return null;
        });
  }

  @Override
  public Optional<StoredUser> findUser(String userId) {
    return one(USER, "SELECT " + USER_COLUMNS + " FROM app_user WHERE id = ?", userId);
  }

  @Override
  public StoredUser updateName(String userId, String displayName, Instant now) {
    return write(
        () -> {
          int updated =
              jdbc.update(
                  "UPDATE app_user SET display_name = ?, updated_at = ?, version = version + 1"
                      + " WHERE id = ?",
                  displayName,
                  stamp(now),
                  userId);
          if (updated != 1) throw AppUserException.notFound();
          return findUser(userId).orElseThrow(AppUserException::notFound);
        });
  }

  @Override
  public StoredUser updateStaff(
      String userId, int version, String displayName, String note, Instant now, String actorId) {
    return write(
        () -> {
          StoredUser current = findUser(userId).orElseThrow(AppUserException::notFound);
          int updated =
              jdbc.update(
                  "UPDATE app_user SET display_name = ?, note = ?, updated_at = ?,"
                      + " version = version + 1 WHERE id = ? AND version = ?",
                  displayName,
                  note,
                  stamp(now),
                  userId,
                  version);
          if (updated != 1) throw AppUserException.conflict();
          if (!same(current.displayName(), displayName))
            audit(actorId, userId, "display_name", now);
          if (!same(current.note(), note)) audit(actorId, userId, "note", now);
          return findUser(userId).orElseThrow(AppUserException::notFound);
        });
  }

  @Override
  public void replaceAvatar(String userId, String contentType, byte[] content, Instant now) {
    write(
        () -> {
          jdbc.update(
              "INSERT INTO app_user_avatar (user_id, content_type, byte_size, content, updated_at)"
                  + " VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE content_type = VALUES(content_type),"
                  + " byte_size = VALUES(byte_size), content = VALUES(content), updated_at ="
                  + " VALUES(updated_at)",
              userId,
              contentType,
              content.length,
              content,
              stamp(now));
          return null;
        });
  }

  @Override
  public Optional<StoredAvatar> findAvatar(String userId) {
    return one(
        (rows, row) ->
            new StoredAvatar(
                rows.getString("content_type"),
                rows.getBytes("content"),
                instant(rows, "updated_at")),
        "SELECT content_type, content, updated_at FROM app_user_avatar WHERE user_id = ?",
        userId);
  }

  @Override
  public List<StoredReservation> reservations(
      String provider, String appScope, String fingerprint, Instant now) {
    return read(
        () ->
            jdbc.query(
                "SELECT r.id, r.status, r.expires_at, s.start_at, s.end_at FROM"
                    + " booking_external_identity i JOIN booking_reservation r ON r.customer_id ="
                    + " i.customer_id LEFT JOIN booking_service_slot s ON s.id = r.slot_id WHERE"
                    + " i.provider = ? AND i.app_scope = ? AND i.subject_fingerprint = ? ORDER BY"
                    + " r.created_at DESC, r.id DESC LIMIT 50",
                (rows, row) -> reservation(rows, now),
                provider,
                appScope,
                fingerprint));
  }

  @Override
  public Optional<StoredVisit> findVisitByClient(String userId, String clientEventId) {
    return one(
        VISIT,
        "SELECT " + VISIT_COLUMNS + " FROM visit_event WHERE user_id = ? AND client_event_id = ?",
        userId,
        clientEventId);
  }

  @Override
  public Optional<StoredVisit> findRecentVisit(
      String userId, String page, String contentId, Instant since) {
    return one(
        VISIT,
        "SELECT "
            + VISIT_COLUMNS
            + " FROM visit_event WHERE user_id = ? AND page = ? AND occurred_at >= ? AND"
            + " ((content_id IS NULL AND ? IS NULL) OR content_id = ?) ORDER BY occurred_at DESC,"
            + " id DESC LIMIT 1",
        userId,
        page,
        stamp(since),
        contentId,
        contentId);
  }

  @Override
  public StoredVisit insertVisit(StoredVisit visit) {
    return write(
        () -> {
          jdbc.update(
              "INSERT INTO visit_event (id, user_id, client_event_id, page, content_id,"
                  + " share_card_id, scene, occurred_at) VALUES (?,?,?,?,?,?,?,?)",
              visit.id(),
              visit.userId(),
              visit.clientEventId(),
              visit.page(),
              visit.contentId(),
              visit.shareCardId(),
              visit.scene(),
              stamp(visit.occurredAt()));
          return visit;
        });
  }

  @Override
  public boolean shareExists(String shareId) {
    Integer count =
        read(
            () ->
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM share_card WHERE id = ?", Integer.class, shareId));
    return count != null && count == 1;
  }

  @Override
  public StoredShare insertShare(StoredShare share) {
    return write(
        () -> {
          jdbc.update(
              "INSERT INTO share_card (id, user_id, parent_card_id, page, content_id, scene,"
                  + " created_at) VALUES (?,?,?,?,?,?,?)",
              share.id(),
              share.userId(),
              share.parentCardId(),
              share.page(),
              share.contentId(),
              share.scene(),
              stamp(share.createdAt()));
          return share;
        });
  }

  @Override
  public UserPage listUsers(String query, Instant cursorSeen, String cursorId, int limit) {
    String like = query.isEmpty() ? null : "%" + escapeLike(query) + "%";
    String shortId = query.matches("(?i)[0-9a-f]{6}") ? query.toUpperCase() : "";
    List<StoredUser> rows =
        read(
            () ->
                jdbc.query(
                    "SELECT "
                        + USER_COLUMNS
                        + " FROM app_user WHERE (? IS NULL OR display_name LIKE ? ESCAPE '\\\\' OR"
                        + " UPPER(LEFT(subject_fingerprint, 6)) = ?) AND (? IS NULL OR last_seen_at"
                        + " < ? OR (last_seen_at = ? AND id < ?)) ORDER BY last_seen_at DESC, id"
                        + " DESC LIMIT ?",
                    USER,
                    like,
                    like,
                    shortId,
                    cursorSeen == null ? null : stamp(cursorSeen),
                    cursorSeen == null ? null : stamp(cursorSeen),
                    cursorSeen == null ? null : stamp(cursorSeen),
                    cursorId,
                    limit + 1));
    boolean more = rows.size() > limit;
    List<StoredUser> page = more ? rows.subList(0, limit) : rows;
    if (!more || page.isEmpty()) return new UserPage(page, null, null);
    StoredUser last = page.get(page.size() - 1);
    return new UserPage(page, last.lastSeenAt(), last.id());
  }

  @Override
  public List<StoredVisit> visits(String userId, int limit) {
    return read(
        () ->
            jdbc.query(
                "SELECT "
                    + VISIT_COLUMNS
                    + " FROM visit_event WHERE user_id = ? ORDER BY occurred_at DESC, id DESC LIMIT ?",
                VISIT,
                userId,
                limit));
  }

  @Override
  public Optional<StoredShare> findShare(String shareId) {
    return one(SHARE, "SELECT " + SHARE_COLUMNS + " FROM share_card WHERE id = ?", shareId);
  }

  @Override
  public List<ShareParticipant> opens(String shareId) {
    return people(
        "SELECT v.id, u.id AS user_id, u.display_name, u.subject_fingerprint, v.occurred_at FROM"
            + " visit_event v JOIN app_user u ON u.id = v.user_id WHERE v.share_card_id = ? ORDER"
            + " BY v.occurred_at, v.id",
        shareId);
  }

  @Override
  public List<ShareParticipant> forwards(String shareId) {
    return people(
        "SELECT s.id, u.id AS user_id, u.display_name, u.subject_fingerprint, s.created_at AS"
            + " occurred_at FROM share_card s JOIN app_user u ON u.id = s.user_id WHERE"
            + " s.parent_card_id = ? ORDER BY s.created_at, s.id",
        shareId);
  }

  @Override
  public UserCensus census(Instant todayStart) {
    return read(
        () -> {
          UserCensus census =
              jdbc.queryForObject(
                  "SELECT (SELECT COUNT(*) FROM app_user), (SELECT COUNT(*) FROM app_user WHERE"
                      + " last_seen_at >= ?), (SELECT COUNT(*) FROM app_user WHERE display_name IS"
                      + " NULL OR display_name = ''), (SELECT COUNT(DISTINCT u.id) FROM app_user u"
                      + " JOIN booking_external_identity i ON i.provider = u.provider AND"
                      + " i.app_scope = u.app_scope AND i.subject_fingerprint = u.subject_fingerprint"
                      + " JOIN booking_reservation r ON r.customer_id = i.customer_id), (SELECT"
                      + " COUNT(DISTINCT user_id) FROM share_card)",
                  (rows, row) ->
                      new UserCensus(
                          rows.getInt(1),
                          rows.getInt(2),
                          rows.getInt(3),
                          rows.getInt(4),
                          rows.getInt(5)),
                  stamp(todayStart));
          return census == null ? new UserCensus(0, 0, 0, 0, 0) : census;
        });
  }

  @Override
  public Counts counts(String userId) {
    return read(
        () -> {
          int visits = count("SELECT COUNT(*) FROM visit_event WHERE user_id = ?", userId);
          int shares = count("SELECT COUNT(*) FROM share_card WHERE user_id = ?", userId);
          int forwards =
              count(
                  "SELECT COUNT(*) FROM share_card WHERE user_id = ? AND parent_card_id IS NOT NULL",
                  userId);
          return new Counts(visits, shares, forwards);
        });
  }

  @Override
  public int[] purge(Instant cutoff) {
    return write(
        () -> {
          Timestamp limit = stamp(cutoff);
          int visits = jdbc.update("DELETE FROM visit_event WHERE occurred_at < ?", limit);
          jdbc.update(
              "UPDATE share_card child JOIN share_card parent ON child.parent_card_id = parent.id"
                  + " LEFT JOIN visit_event visit ON visit.share_card_id = parent.id SET"
                  + " child.parent_card_id = NULL WHERE parent.created_at < ? AND visit.id IS NULL",
              limit);
          int shares =
              jdbc.update(
                  "DELETE share_card FROM share_card LEFT JOIN visit_event visit ON"
                      + " visit.share_card_id = share_card.id WHERE share_card.created_at < ? AND"
                      + " visit.id IS NULL",
                  limit);
          return new int[] {visits, shares};
        });
  }

  private void audit(String actorId, String userId, String field, Instant now) {
    jdbc.update(
        "INSERT INTO app_user_audit (id, actor_id, user_id, field_name, occurred_at) VALUES (?,?,?,?,?)",
        UUID.randomUUID().toString(),
        actorId,
        userId,
        field,
        stamp(now));
  }

  private List<ShareParticipant> people(String sql, String shareId) {
    return read(
        () ->
            jdbc.query(
                sql,
                (rows, row) ->
                    new ShareParticipant(
                        rows.getString("id"),
                        rows.getString("user_id"),
                        rows.getString("display_name"),
                        rows.getString("subject_fingerprint"),
                        instant(rows, "occurred_at")),
                shareId));
  }

  private int count(String sql, String userId) {
    Integer value = jdbc.queryForObject(sql, Integer.class, userId);
    return value == null ? 0 : value;
  }

  private StoredReservation reservation(ResultSet rows, Instant now) throws SQLException {
    Instant expires = instant(rows, "expires_at");
    String status = rows.getString("status");
    if ("PENDING_CONFIRMATION".equals(status) && expires != null && !now.isBefore(expires)) {
      status = "EXPIRED";
    }
    Instant start = instant(rows, "start_at");
    Instant end = instant(rows, "end_at");
    String localDate = null;
    String localStart = null;
    String localEnd = null;
    if (start != null && end != null) {
      ZonedDateTime starts = start.atZone(SHANGHAI);
      ZonedDateTime ends = end.atZone(SHANGHAI);
      localDate = DAY.format(starts);
      localStart = CLOCK.format(starts);
      localEnd = CLOCK.format(ends);
    }
    return new StoredReservation(
        rows.getString("id"), status, expires, localDate, localStart, localEnd);
  }

  private <T> Optional<T> one(RowMapper<T> mapper, String sql, Object... args) {
    List<T> rows = read(() -> jdbc.query(sql, mapper, args));
    if (rows.isEmpty()) return Optional.empty();
    return Optional.of(rows.get(0));
  }

  private <T> T read(Sql<T> work) {
    try {
      return work.get();
    } catch (DataAccessException failure) {
      throw AppUserException.unavailable();
    }
  }

  private <T> T write(Sql<T> work) {
    try {
      return transactions.execute(status -> work.get());
    } catch (AppUserException rejected) {
      throw rejected;
    } catch (DuplicateKeyException duplicated) {
      throw duplicated;
    } catch (DataAccessException failure) {
      throw AppUserException.unavailable();
    }
  }

  private static boolean same(String left, String right) {
    return left == null ? right == null : left.equals(right);
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static Timestamp stamp(Instant instant) {
    return Timestamp.from(instant);
  }

  private static Instant instant(ResultSet rows, String column) throws SQLException {
    Timestamp value = rows.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  @FunctionalInterface
  private interface Sql<T> {
    T get();
  }

  private static final String USER_COLUMNS =
      "id, provider, app_scope, subject_fingerprint, display_name, note, created_at, updated_at,"
          + " last_seen_at, version";
  private static final String VISIT_COLUMNS =
      "id, user_id, client_event_id, page, content_id, share_card_id, scene, occurred_at";
  private static final String SHARE_COLUMNS =
      "id, user_id, parent_card_id, page, content_id, scene, created_at";

  private static final RowMapper<StoredUser> USER =
      (rows, row) ->
          new StoredUser(
              rows.getString("id"),
              rows.getString("provider"),
              rows.getString("app_scope"),
              rows.getString("subject_fingerprint"),
              rows.getString("display_name"),
              rows.getString("note"),
              instant(rows, "created_at"),
              instant(rows, "updated_at"),
              instant(rows, "last_seen_at"),
              rows.getInt("version"));

  private static final RowMapper<StoredVisit> VISIT =
      (rows, row) ->
          new StoredVisit(
              rows.getString("id"),
              rows.getString("user_id"),
              rows.getString("client_event_id"),
              rows.getString("page"),
              rows.getString("content_id"),
              rows.getString("share_card_id"),
              (Integer) rows.getObject("scene"),
              instant(rows, "occurred_at"));

  private static final RowMapper<StoredShare> SHARE =
      (rows, row) ->
          new StoredShare(
              rows.getString("id"),
              rows.getString("user_id"),
              rows.getString("parent_card_id"),
              rows.getString("page"),
              rows.getString("content_id"),
              (Integer) rows.getObject("scene"),
              instant(rows, "created_at"));
}
