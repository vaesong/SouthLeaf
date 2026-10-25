package cn.zhinan.uniapp.booking.adapter.jdbc;

import cn.zhinan.uniapp.booking.PublicBookingException;
import cn.zhinan.uniapp.booking.port.PublicBookingPorts.CommandRow;
import cn.zhinan.uniapp.booking.port.PublicBookingPorts.IdentityRow;
import cn.zhinan.uniapp.booking.port.PublicBookingPorts.OrderRow;
import cn.zhinan.uniapp.booking.port.PublicBookingPorts.Repository;
import cn.zhinan.uniapp.booking.port.PublicBookingPorts.RequestDraft;
import cn.zhinan.uniapp.booking.port.PublicBookingPorts.RequestRow;
import cn.zhinan.uniapp.booking.port.PublicBookingPorts.SlotRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Intake adapter over the canonical booking tables. It writes the same audit and outbox facts the
 * operator channel writes, so one accepted request leaves one trail regardless of the entry point.
 */
public final class JdbcPublicBookingRepository implements Repository {
  private static final String REQUEST_SELECT =
      "SELECT r.id, r.status, r.created_at, r.expires_at, r.package_version_ref, r.version,"
          + " p.slot_id AS pref_slot_id, s.start_at AS pref_start, s.end_at AS pref_end,"
          + " s.business_zone AS pref_zone, s.display_offset AS pref_offset,"
          + " o.id AS order_id, o.lifecycle_status, sn.package_display_name, sn.currency_code,"
          + " sn.amount_minor_units, sn.start_at AS order_start, sn.end_at AS order_end,"
          + " sn.business_zone AS order_zone, sn.display_offset AS order_offset, sn.captured_at"
          + " FROM booking_reservation r"
          + " LEFT JOIN booking_request_preference p ON p.reservation_id = r.id"
          + "   AND p.slot_id = (SELECT p2.slot_id FROM booking_request_preference p2"
          + "     WHERE p2.reservation_id = r.id ORDER BY p2.requested_at, p2.slot_id LIMIT 1)"
          + " LEFT JOIN booking_service_slot s ON s.id = p.slot_id"
          + " LEFT JOIN booking_order o ON o.source_reservation_id = r.id"
          + " LEFT JOIN booking_order_snapshot sn ON sn.order_id = o.id";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public JdbcPublicBookingRepository(DataSource source) {
    this.jdbc = new JdbcTemplate(source);
    this.transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    this.jdbc.setQueryTimeout(5);
  }

  @Override
  public <T> T inTransaction(Supplier<T> work) {
    try {
      return transactions.execute(status -> work.get());
    } catch (PublicBookingException refusal) {
      throw refusal;
    } catch (DataAccessException failure) {
      throw unavailable();
    }
  }

  @Override
  public Optional<IdentityRow> findIdentity(String provider, String appScope, String fingerprint) {
    try {
      List<IdentityRow> rows =
          jdbc.query(
              "SELECT i.customer_id, c.display_name FROM booking_external_identity i"
                  + " JOIN booking_customer c ON c.id = i.customer_id"
                  + " WHERE i.provider = ? AND i.app_scope = ? AND i.subject_fingerprint = ?"
                  + " AND i.status = 'VERIFIED' AND c.status = 'ACTIVE'",
              (rs, row) -> new IdentityRow(rs.getString(1), rs.getString(2)),
              provider,
              appScope,
              fingerprint);
      return rows.stream().findFirst();
    } catch (DataAccessException failure) {
      throw unavailable();
    }
  }

  @Override
  public IdentityRow createCustomer(
      String customerId,
      String displayName,
      String provider,
      String appScope,
      String fingerprint,
      Instant now) {
    try {
      jdbc.update(
          "INSERT INTO booking_customer (id, status, display_name, language, created_at, updated_at,"
              + " version) VALUES (?, 'ACTIVE', ?, NULL, ?, ?, 1)",
          customerId,
          displayName,
          Timestamp.from(now),
          Timestamp.from(now));
      jdbc.update(
          "INSERT INTO booking_external_identity (id, customer_id, provider, app_scope,"
              + " subject_fingerprint, status, verified_at, created_at, updated_at, version)"
              + " VALUES (?, ?, ?, ?, ?, 'VERIFIED', ?, ?, ?, 1)",
          java.util.UUID.randomUUID().toString(),
          customerId,
          provider,
          appScope,
          fingerprint,
          Timestamp.from(now),
          Timestamp.from(now),
          Timestamp.from(now));
      return new IdentityRow(customerId, displayName);
    } catch (DuplicateKeyException race) {
      // A concurrent first login for the same subject wins; read the winner instead of a second
      // row.
      return findIdentity(provider, appScope, fingerprint)
          .orElseThrow(JdbcPublicBookingRepository::unavailable);
    } catch (DataAccessException failure) {
      throw unavailable();
    }
  }

  @Override
  public List<SlotRow> listAvailableSlots(Instant from, Instant to, int limit) {
    try {
      return jdbc.query(
          "SELECT s.id, s.start_at, s.end_at, s.business_zone, s.display_offset, s.status"
              + " FROM booking_service_slot s"
              + " LEFT JOIN booking_slot_occupancy o ON o.slot_id = s.id"
              + " WHERE s.status = 'OPEN' AND o.slot_id IS NULL AND s.start_at >= ? AND s.start_at < ?"
              + " ORDER BY s.start_at ASC, s.id ASC LIMIT ?",
          (rs, row) -> slot(rs),
          Timestamp.from(from),
          Timestamp.from(to),
          limit);
    } catch (DataAccessException failure) {
      throw unavailable();
    }
  }

  @Override
  public boolean slotAvailable(String slotId) {
    try {
      Integer count =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM booking_service_slot s"
                  + " LEFT JOIN booking_slot_occupancy o ON o.slot_id = s.id"
                  + " WHERE s.id = ? AND s.status = 'OPEN' AND o.slot_id IS NULL",
              Integer.class,
              slotId);
      return count != null && count > 0;
    } catch (DataAccessException failure) {
      throw unavailable();
    }
  }

  @Override
  public Optional<String> findOpenReservationId(String customerId, Instant now) {
    try {
      List<String> rows =
          jdbc.query(
              "SELECT id FROM booking_reservation"
                  + " WHERE customer_id = ? AND status = 'PENDING_CONFIRMATION' AND expires_at > ?"
                  + " ORDER BY created_at ASC, id ASC LIMIT 1",
              (rs, row) -> rs.getString(1),
              customerId,
              Timestamp.from(now));
      return rows.stream().findFirst();
    } catch (DataAccessException failure) {
      throw unavailable();
    }
  }

  @Override
  public Optional<CommandRow> findCommand(String actorId, String operation, String idempotencyKey) {
    try {
      List<CommandRow> rows =
          jdbc.query(
              "SELECT request_sha256, result_ref FROM booking_command"
                  + " WHERE actor_id = ? AND operation = ? AND idempotency_key = ?",
              (rs, row) -> new CommandRow(rs.getString(1), rs.getString(2)),
              actorId,
              operation,
              idempotencyKey);
      return rows.stream().findFirst();
    } catch (DataAccessException failure) {
      throw unavailable();
    }
  }

  @Override
  public String insertRequest(RequestDraft draft) {
    try {
      jdbc.update(
          "INSERT INTO booking_reservation (id, customer_id, package_version_ref, source, status,"
              + " expires_at, slot_id, schedule_revision, confirmed_at, confirmed_by, created_at,"
              + " updated_at, version) VALUES (?, ?, ?, ?, 'PENDING_CONFIRMATION', ?, NULL, 0,"
              + " NULL, NULL, ?, ?, 1)",
          draft.reservationId(),
          draft.customerId(),
          draft.packageVersionRef(),
          draft.source(),
          Timestamp.from(draft.expiresAt()),
          Timestamp.from(draft.now()),
          Timestamp.from(draft.now()));
      jdbc.update(
          "INSERT INTO booking_request_preference (reservation_id, slot_id, requested_at)"
              + " VALUES (?, ?, ?)",
          draft.reservationId(),
          draft.slotId(),
          Timestamp.from(draft.now()));
      jdbc.update(
          "INSERT INTO booking_command (id, actor_id, operation, idempotency_key, request_sha256,"
              + " result_ref, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
          draft.commandId(),
          draft.actorId(),
          draft.operation(),
          draft.idempotencyKey(),
          draft.requestSha256(),
          draft.reservationId(),
          Timestamp.from(draft.now()));
      jdbc.update(
          "INSERT INTO booking_audit (id, actor_id, command_id, object_type, object_id, action,"
              + " reason_code, created_at) VALUES (?, ?, ?, 'RESERVATION', ?, 'CREATE',"
              + " 'RESERVATION_REQUESTED', ?)",
          java.util.UUID.randomUUID().toString(),
          draft.actorId(),
          draft.commandId(),
          draft.reservationId(),
          Timestamp.from(draft.now()));
      jdbc.update(
          "INSERT INTO booking_notification_outbox (id, intent, recipient_ref, object_ref, due_at,"
              + " status, attempts, created_at, updated_at)"
              + " VALUES (?, 'RESERVATION_PENDING_CONFIRMATION', ?, ?, ?, 'PENDING', 0, ?, ?)",
          java.util.UUID.randomUUID().toString(),
          draft.customerId(),
          draft.reservationId(),
          Timestamp.from(draft.now()),
          Timestamp.from(draft.now()),
          Timestamp.from(draft.now()));
      return draft.reservationId();
    } catch (DuplicateKeyException race) {
      throw new PublicBookingException(
          PublicBookingException.ErrorCode.SERVICE_UNAVAILABLE, "CONCURRENT_INTAKE");
    } catch (DataAccessException failure) {
      throw unavailable();
    }
  }

  @Override
  public Optional<RequestRow> findRequest(String customerId, String reservationId) {
    try {
      List<RequestRow> rows =
          jdbc.query(
              REQUEST_SELECT + " WHERE r.customer_id = ? AND r.id = ?",
              (rs, row) -> request(rs),
              customerId,
              reservationId);
      return rows.stream().findFirst();
    } catch (DataAccessException failure) {
      throw unavailable();
    }
  }

  @Override
  public List<RequestRow> listRequests(String customerId, int limit) {
    try {
      return jdbc.query(
          REQUEST_SELECT + " WHERE r.customer_id = ? ORDER BY r.created_at DESC, r.id DESC LIMIT ?",
          (rs, row) -> request(rs),
          customerId,
          limit);
    } catch (DataAccessException failure) {
      throw unavailable();
    }
  }

  private static RequestRow request(ResultSet rs) throws SQLException {
    SlotRow preference = null;
    if (rs.getString("pref_slot_id") != null)
      preference =
          new SlotRow(
              rs.getString("pref_slot_id"),
              instant(rs, "pref_start"),
              instant(rs, "pref_end"),
              rs.getString("pref_zone"),
              rs.getString("pref_offset"),
              "OPEN");
    OrderRow order = null;
    if (rs.getString("order_id") != null)
      order =
          new OrderRow(
              rs.getString("order_id"),
              rs.getString("package_display_name"),
              rs.getString("currency_code"),
              rs.getLong("amount_minor_units"),
              instant(rs, "order_start"),
              instant(rs, "order_end"),
              rs.getString("order_zone"),
              rs.getString("order_offset"),
              instant(rs, "captured_at"));
    return new RequestRow(
        rs.getString("id"),
        rs.getString("status"),
        instant(rs, "created_at"),
        instant(rs, "expires_at"),
        rs.getString("package_version_ref"),
        rs.getInt("version"),
        preference,
        order);
  }

  private static SlotRow slot(ResultSet rs) throws SQLException {
    return new SlotRow(
        rs.getString(1),
        instant(rs, "start_at"),
        instant(rs, "end_at"),
        rs.getString("business_zone"),
        rs.getString("display_offset"),
        rs.getString("status"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static PublicBookingException unavailable() {
    return new PublicBookingException(
        PublicBookingException.ErrorCode.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
  }
}
