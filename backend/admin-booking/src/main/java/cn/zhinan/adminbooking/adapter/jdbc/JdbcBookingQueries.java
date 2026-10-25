package cn.zhinan.adminbooking.adapter.jdbc;

import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.BookingFailure;
import cn.zhinan.adminbooking.domain.BookingModels.ConfirmationResult;
import cn.zhinan.adminbooking.domain.BookingModels.Order;
import cn.zhinan.adminbooking.domain.BookingModels.OrderRevision;
import cn.zhinan.adminbooking.domain.BookingModels.OrderSnapshot;
import cn.zhinan.adminbooking.domain.BookingModels.OrderView;
import cn.zhinan.adminbooking.domain.BookingModels.Reservation;
import cn.zhinan.adminbooking.domain.BookingModels.ReservationView;
import cn.zhinan.adminbooking.domain.BookingModels.ServiceSlot;
import cn.zhinan.adminbooking.domain.BookingModels.SlotAssignment;
import cn.zhinan.adminbooking.port.BookingPorts.BookingQueries;
import java.time.Instant;
import java.util.List;

/** Read model for the management UI. It assembles views without changing any state. */
public final class JdbcBookingQueries implements BookingQueries {
  private final JdbcConnectionProvider connections;
  private final JdbcReservationRepository reservations;
  private final JdbcOrderRepository orders;

  public JdbcBookingQueries(
      JdbcConnectionProvider connections,
      JdbcReservationRepository reservations,
      JdbcOrderRepository orders) {
    this.connections = connections;
    this.reservations = reservations;
    this.orders = orders;
  }

  @Override
  public ReservationView reservationView(String reservationId) {
    Reservation reservation =
        reservations
            .find(reservationId)
            .orElseThrow(
                () -> new BookingException(BookingFailure.NOT_FOUND, "RESERVATION_NOT_FOUND"));
    List<SlotAssignment> assignments = reservations.listAssignments(reservationId);
    String orderId = orders.findByReservation(reservationId).map(Order::orderId).orElse(null);
    return new ReservationView(reservation, assignments, orderId, null);
  }

  @Override
  public OrderView orderView(String orderId) {
    Order order =
        orders
            .find(orderId)
            .orElseThrow(() -> new BookingException(BookingFailure.NOT_FOUND, "ORDER_NOT_FOUND"));
    OrderSnapshot snapshot =
        orders
            .findSnapshot(order.snapshotId())
            .orElseThrow(
                () -> new BookingException(BookingFailure.NOT_FOUND, "SNAPSHOT_NOT_FOUND"));
    List<OrderRevision> revisions = orders.listRevisions(orderId);
    String reservationStatus =
        reservations.find(order.sourceReservationId()).map(r -> r.status().name()).orElse(null);
    return new OrderView(order, snapshot, revisions, reservationStatus);
  }

  @Override
  public List<Reservation> pendingReservations(int limit) {
    return reservations.listPending(limit);
  }

  @Override
  public List<ServiceSlot> openSlots(Instant from, Instant to, int limit) {
    return JdbcSupport.queryList(
        connections,
        "SELECT id, start_at, end_at, business_zone, display_offset, status, capacity,"
            + " rule_version, revision FROM booking_service_slot WHERE status = 'OPEN' AND start_at"
            + " >= ? AND start_at < ? ORDER BY start_at ASC, id ASC LIMIT ?",
        resultSet ->
            new ServiceSlot(
                resultSet.getString("id"),
                JdbcSupport.instant(resultSet, "start_at"),
                JdbcSupport.instant(resultSet, "end_at"),
                resultSet.getString("business_zone"),
                resultSet.getString("display_offset"),
                resultSet.getString("status"),
                resultSet.getInt("capacity"),
                resultSet.getString("rule_version"),
                resultSet.getInt("revision")),
        from,
        to,
        limit);
  }

  @Override
  public ConfirmationResult lastConfirmation(String commandId) {
    Reservation reservation =
        JdbcSupport.queryOne(
            connections,
            "SELECT r.id, r.customer_id, r.package_version_ref, r.source, r.status, r.expires_at,"
                + " r.slot_id, r.schedule_revision, r.confirmed_at, r.confirmed_by, r.created_at,"
                + " r.version FROM booking_reservation r"
                + " JOIN booking_order o ON o.source_reservation_id = r.id"
                + " WHERE o.id = ?",
            resultSet ->
                new Reservation(
                    resultSet.getString("id"),
                    resultSet.getString("customer_id"),
                    resultSet.getString("package_version_ref"),
                    resultSet.getString("source"),
                    cn.zhinan.adminbooking.domain.BookingModels.ReservationStatus.valueOf(
                        resultSet.getString("status")),
                    JdbcSupport.instant(resultSet, "expires_at"),
                    resultSet.getString("slot_id"),
                    resultSet.getInt("schedule_revision"),
                    JdbcSupport.instant(resultSet, "confirmed_at"),
                    resultSet.getString("confirmed_by"),
                    JdbcSupport.instant(resultSet, "created_at"),
                    resultSet.getInt("version")),
            commandId);
    if (reservation == null) return null;
    return new ConfirmationResult(
        commandId,
        reservation.reservationId(),
        commandId,
        commandId,
        reservation.slotId(),
        1,
        reservation.confirmedAt(),
        true);
  }
}
