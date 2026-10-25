package cn.zhinan.adminbooking.adapter.jdbc;

import cn.zhinan.adminbooking.domain.BookingModels.Reservation;
import cn.zhinan.adminbooking.domain.BookingModels.ReservationCreated;
import cn.zhinan.adminbooking.domain.BookingModels.ReservationStatus;
import cn.zhinan.adminbooking.domain.BookingModels.SlotAssignment;
import cn.zhinan.adminbooking.port.BookingPorts.ReservationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Reservation persistence. Every state change is a guarded update, never a blind write. */
public final class JdbcReservationRepository implements ReservationRepository {
  private static final String COLUMNS =
      " id, customer_id, package_version_ref, source, status, expires_at, slot_id,"
          + " schedule_revision, confirmed_at, confirmed_by, created_at, version";

  private final JdbcConnectionProvider connections;

  public JdbcReservationRepository(JdbcConnectionProvider connections) {
    this.connections = connections;
  }

  @Override
  public Optional<Reservation> find(String reservationId) {
    Reservation reservation =
        JdbcSupport.queryOne(
            connections,
            "SELECT" + COLUMNS + " FROM booking_reservation WHERE id = ?",
            JdbcReservationRepository::mapReservation,
            reservationId);
    return Optional.ofNullable(reservation);
  }

  @Override
  public ReservationCreated create(
      String reservationId,
      String customerId,
      String packageVersionRef,
      String source,
      Instant expiresAt,
      String actorId,
      String idempotencyKey,
      String requestSha256,
      Instant now) {
    JdbcSupport.update(
        connections,
        "INSERT INTO booking_reservation"
            + " (id, customer_id, package_version_ref, source, status, expires_at, slot_id,"
            + " schedule_revision, confirmed_at, confirmed_by, created_at, updated_at, version)"
            + " VALUES (?, ?, ?, ?, 'PENDING_CONFIRMATION', ?, NULL, 0, NULL, NULL, ?, ?, 1)",
        reservationId,
        customerId,
        packageVersionRef,
        source,
        expiresAt,
        now,
        now);
    return new ReservationCreated(reservationId, 1, false);
  }

  @Override
  public List<Reservation> listPending(int limit) {
    return JdbcSupport.queryList(
        connections,
        "SELECT"
            + COLUMNS
            + " FROM booking_reservation"
            + " WHERE status = 'PENDING_CONFIRMATION' ORDER BY created_at ASC, id ASC LIMIT ?",
        JdbcReservationRepository::mapReservation,
        limit);
  }

  @Override
  public List<Reservation> listByCustomer(String customerId, int limit) {
    return JdbcSupport.queryList(
        connections,
        "SELECT"
            + COLUMNS
            + " FROM booking_reservation"
            + " WHERE customer_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
        JdbcReservationRepository::mapReservation,
        customerId,
        limit);
  }

  @Override
  public List<SlotAssignment> listAssignments(String reservationId) {
    return JdbcSupport.queryList(
        connections,
        "SELECT id, slot_id, reservation_id, previous_slot_id, assigned_at, reason_code, revision"
            + " FROM booking_slot_assignment_history WHERE reservation_id = ?"
            + " ORDER BY assigned_at ASC, id ASC",
        resultSet ->
            new SlotAssignment(
                resultSet.getString("id"),
                resultSet.getString("slot_id"),
                resultSet.getString("reservation_id"),
                resultSet.getString("previous_slot_id"),
                JdbcSupport.instant(resultSet, "assigned_at"),
                resultSet.getString("reason_code"),
                resultSet.getInt("revision")),
        reservationId);
  }

  @Override
  public int markConfirmed(
      String reservationId,
      int expectedVersion,
      String slotId,
      int scheduleRevision,
      String actorId,
      Instant now) {
    return JdbcSupport.update(
        connections,
        "UPDATE booking_reservation SET status = 'CONFIRMED', slot_id = ?, schedule_revision = ?,"
            + " confirmed_at = ?, confirmed_by = ?, updated_at = ?, version = version + 1"
            + " WHERE id = ? AND version = ? AND status = 'PENDING_CONFIRMATION'",
        slotId,
        scheduleRevision,
        now,
        actorId,
        now,
        reservationId,
        expectedVersion);
  }

  @Override
  public int markRejected(
      String reservationId, int expectedVersion, String reasonCode, String actorId, Instant now) {
    return JdbcSupport.update(
        connections,
        "UPDATE booking_reservation SET status = 'REJECTED', updated_at = ?, version = version + 1"
            + " WHERE id = ? AND version = ? AND status = 'PENDING_CONFIRMATION'",
        now,
        reservationId,
        expectedVersion);
  }

  @Override
  public int markCancelled(String reservationId, int expectedVersion, String actorId, Instant now) {
    return JdbcSupport.update(
        connections,
        "UPDATE booking_reservation SET status = 'CANCELLED', updated_at = ?, version = version + 1"
            + " WHERE id = ? AND version = ? AND status = 'CONFIRMED'",
        now,
        reservationId,
        expectedVersion);
  }

  @Override
  public int markExpired(String reservationId, Instant dbNow) {
    return JdbcSupport.update(
        connections,
        "UPDATE booking_reservation SET status = 'EXPIRED', updated_at = ?, version = version + 1"
            + " WHERE id = ? AND status = 'PENDING_CONFIRMATION' AND expires_at <= ?",
        dbNow,
        reservationId,
        dbNow);
  }

  @Override
  public int reschedule(
      String reservationId,
      int expectedVersion,
      String slotId,
      int scheduleRevision,
      String actorId,
      Instant now) {
    return JdbcSupport.update(
        connections,
        "UPDATE booking_reservation SET slot_id = ?, schedule_revision = ?, updated_at = ?,"
            + " version = version + 1"
            + " WHERE id = ? AND version = ? AND status = 'CONFIRMED'",
        slotId,
        scheduleRevision,
        now,
        reservationId,
        expectedVersion);
  }

  private static Reservation mapReservation(java.sql.ResultSet resultSet)
      throws java.sql.SQLException {
    return new Reservation(
        resultSet.getString("id"),
        resultSet.getString("customer_id"),
        resultSet.getString("package_version_ref"),
        resultSet.getString("source"),
        ReservationStatus.valueOf(resultSet.getString("status")),
        JdbcSupport.instant(resultSet, "expires_at"),
        resultSet.getString("slot_id"),
        resultSet.getInt("schedule_revision"),
        JdbcSupport.instant(resultSet, "confirmed_at"),
        resultSet.getString("confirmed_by"),
        JdbcSupport.instant(resultSet, "created_at"),
        resultSet.getInt("version"));
  }
}
