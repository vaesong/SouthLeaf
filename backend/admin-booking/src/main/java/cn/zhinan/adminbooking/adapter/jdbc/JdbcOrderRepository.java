package cn.zhinan.adminbooking.adapter.jdbc;

import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.BookingFailure;
import cn.zhinan.adminbooking.domain.BookingModels.FulfilmentStatus;
import cn.zhinan.adminbooking.domain.BookingModels.Order;
import cn.zhinan.adminbooking.domain.BookingModels.OrderLifecycle;
import cn.zhinan.adminbooking.domain.BookingModels.OrderRevision;
import cn.zhinan.adminbooking.domain.BookingModels.OrderSnapshot;
import cn.zhinan.adminbooking.port.BookingPorts.OrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Order persistence. The snapshot is written once and never updated; corrections are appended as
 * revisions. There is no payment, deposit, refund or delivery column in this table.
 */
public final class JdbcOrderRepository implements OrderRepository {
  private final JdbcConnectionProvider connections;

  public JdbcOrderRepository(JdbcConnectionProvider connections) {
    this.connections = connections;
  }

  @Override
  public Optional<Order> find(String orderId) {
    Order order =
        JdbcSupport.queryOne(
            connections,
            "SELECT id, source_reservation_id, customer_id, lifecycle_status, fulfilment_status,"
                + " snapshot_id, current_revision, created_at, version FROM booking_order WHERE id"
                + " = ?",
            JdbcOrderRepository::mapOrder,
            orderId);
    return Optional.ofNullable(order);
  }

  @Override
  public Optional<Order> findByReservation(String reservationId) {
    Order order =
        JdbcSupport.queryOne(
            connections,
            "SELECT id, source_reservation_id, customer_id, lifecycle_status, fulfilment_status,"
                + " snapshot_id, current_revision, created_at, version FROM booking_order"
                + " WHERE source_reservation_id = ?",
            JdbcOrderRepository::mapOrder,
            reservationId);
    return Optional.ofNullable(order);
  }

  @Override
  public Optional<OrderSnapshot> findSnapshot(String snapshotId) {
    OrderSnapshot snapshot =
        JdbcSupport.queryOne(
            connections,
            "SELECT id, order_id, package_version_ref, package_display_name, currency_code,"
                + " amount_minor_units, price_version, slot_id, start_at, end_at, business_zone,"
                + " display_offset, schedule_revision, customer_id, source, captured_at"
                + " FROM booking_order_snapshot WHERE id = ?",
            JdbcOrderRepository::mapSnapshot,
            snapshotId);
    return Optional.ofNullable(snapshot);
  }

  @Override
  public Order create(
      String orderId,
      String sourceReservationId,
      String customerId,
      OrderSnapshot snapshot,
      String commandId,
      String actorId,
      Instant now) {
    // The order row is inserted first: booking_order_snapshot references booking_order(id), so the
    // parent must exist before the immutable snapshot can be attached to it.
    JdbcSupport.updateProtected(
        connections,
        "ORDER_SOURCE_CONFLICT",
        "INSERT INTO booking_order"
            + " (id, source_reservation_id, customer_id, lifecycle_status, fulfilment_status,"
            + " snapshot_id, current_revision, created_at, updated_at, version)"
            + " VALUES (?, ?, ?, 'ACTIVE', 'UNSCHEDULED', ?, 1, ?, ?, 1)",
        orderId,
        sourceReservationId,
        customerId,
        snapshot.snapshotId(),
        now,
        now);
    JdbcSupport.update(
        connections,
        "INSERT INTO booking_order_snapshot"
            + " (id, order_id, package_version_ref, package_display_name, currency_code,"
            + " amount_minor_units, price_version, slot_id, start_at, end_at, business_zone,"
            + " display_offset, schedule_revision, customer_id, source, captured_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        snapshot.snapshotId(),
        orderId,
        snapshot.packageVersionRef(),
        snapshot.packageDisplayName(),
        snapshot.currencyCode(),
        snapshot.amountMinorUnits(),
        snapshot.priceVersion(),
        snapshot.slotId(),
        snapshot.startAt(),
        snapshot.endAt(),
        snapshot.businessZone(),
        snapshot.displayOffset(),
        snapshot.scheduleRevision(),
        snapshot.customerId(),
        snapshot.source(),
        snapshot.capturedAt());
    return find(orderId)
        .orElseThrow(
            () -> new BookingException(BookingFailure.STORAGE_UNAVAILABLE, "ORDER_NOT_STORED"));
  }

  @Override
  public List<OrderRevision> listRevisions(String orderId) {
    return JdbcSupport.queryList(
        connections,
        "SELECT revision_no, command_id, actor_ref, changed_fields, reason_code, created_at"
            + " FROM booking_order_revision WHERE order_id = ? ORDER BY revision_no ASC",
        resultSet ->
            new OrderRevision(
                resultSet.getInt("revision_no"),
                resultSet.getString("command_id"),
                resultSet.getString("actor_ref"),
                resultSet.getString("changed_fields"),
                resultSet.getString("reason_code"),
                JdbcSupport.instant(resultSet, "created_at")),
        orderId);
  }

  @Override
  public OrderRevision appendRevision(
      String orderId,
      int expectedRevision,
      String commandId,
      String actorId,
      String changedFields,
      String reasonCode,
      Instant now) {
    int updated =
        JdbcSupport.update(
            connections,
            "UPDATE booking_order SET current_revision = current_revision + 1, updated_at = ?,"
                + " version = version + 1 WHERE id = ? AND current_revision = ?",
            now,
            orderId,
            expectedRevision);
    if (updated == 0)
      throw new BookingException(BookingFailure.VERSION_CONFLICT, "ORDER_REVISION_MISMATCH");
    JdbcSupport.update(
        connections,
        "INSERT INTO booking_order_revision (order_id, revision_no, command_id, actor_ref,"
            + " changed_fields, reason_code, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        orderId,
        expectedRevision + 1,
        commandId,
        actorId,
        changedFields,
        reasonCode,
        now);
    return new OrderRevision(
        expectedRevision + 1, commandId, actorId, changedFields, reasonCode, now);
  }

  @Override
  public int markCancelled(
      String orderId, int expectedRevision, String commandId, String actorId, Instant now) {
    return JdbcSupport.update(
        connections,
        "UPDATE booking_order SET lifecycle_status = 'CANCELLED', updated_at = ?, version = version"
            + " + 1 WHERE id = ? AND current_revision = ? AND lifecycle_status = 'ACTIVE'",
        now,
        orderId,
        expectedRevision);
  }

  private static Order mapOrder(java.sql.ResultSet resultSet) throws java.sql.SQLException {
    return new Order(
        resultSet.getString("id"),
        resultSet.getString("source_reservation_id"),
        resultSet.getString("customer_id"),
        OrderLifecycle.valueOf(resultSet.getString("lifecycle_status")),
        FulfilmentStatus.valueOf(resultSet.getString("fulfilment_status")),
        resultSet.getString("snapshot_id"),
        resultSet.getInt("current_revision"),
        JdbcSupport.instant(resultSet, "created_at"),
        resultSet.getInt("version"));
  }

  private static OrderSnapshot mapSnapshot(java.sql.ResultSet resultSet)
      throws java.sql.SQLException {
    return new OrderSnapshot(
        resultSet.getString("id"),
        resultSet.getString("order_id"),
        resultSet.getString("package_version_ref"),
        resultSet.getString("package_display_name"),
        resultSet.getString("currency_code"),
        resultSet.getLong("amount_minor_units"),
        resultSet.getString("price_version"),
        resultSet.getString("slot_id"),
        JdbcSupport.instant(resultSet, "start_at"),
        JdbcSupport.instant(resultSet, "end_at"),
        resultSet.getString("business_zone"),
        resultSet.getString("display_offset"),
        resultSet.getInt("schedule_revision"),
        resultSet.getString("customer_id"),
        resultSet.getString("source"),
        JdbcSupport.instant(resultSet, "captured_at"));
  }
}
