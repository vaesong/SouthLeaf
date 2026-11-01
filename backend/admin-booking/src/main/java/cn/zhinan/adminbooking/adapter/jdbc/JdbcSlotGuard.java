package cn.zhinan.adminbooking.adapter.jdbc;

import cn.zhinan.adminbooking.domain.BookingModels.ServiceSlot;
import cn.zhinan.adminbooking.port.SlotOpeningPorts.BoardSlot;
import cn.zhinan.adminbooking.port.SlotOpeningPorts.DaySlot;
import cn.zhinan.adminbooking.port.SlotOpeningPorts.SlotGuard;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Slot locks and the week board. Closing here never deletes a row and never calls {@code
 * closeSlot}.
 */
public final class JdbcSlotGuard implements SlotGuard {
  private static final String SLOT_COLUMNS =
      "s.id, s.start_at, s.end_at, s.business_zone, s.display_offset, s.status, s.capacity,"
          + " s.rule_version, s.revision";

  private final JdbcConnectionProvider connections;

  public JdbcSlotGuard(JdbcConnectionProvider connections) {
    this.connections = connections;
  }

  @Override
  public List<DaySlot> lockSlotsStartingBetween(Instant from, Instant to) {
    List<ServiceSlot> slots =
        JdbcSupport.queryList(
            connections,
            "SELECT "
                + SLOT_COLUMNS
                + " FROM booking_service_slot s WHERE s.start_at >= ? AND s.start_at < ?"
                + " ORDER BY s.start_at, s.id FOR UPDATE",
            JdbcSlotGuard::mapSlot,
            from,
            to);
    List<DaySlot> locked = new ArrayList<>();
    for (ServiceSlot slot : slots) {
      String occupied =
          JdbcSupport.queryOne(
              connections,
              "SELECT slot_id FROM booking_slot_occupancy WHERE slot_id = ?",
              resultSet -> resultSet.getString("slot_id"),
              slot.slotId());
      locked.add(new DaySlot(slot, occupied != null));
    }
    return List.copyOf(locked);
  }

  @Override
  public boolean pendingConfirmation(String slotId) {
    String reservationId =
        JdbcSupport.queryOne(
            connections,
            "SELECT p.reservation_id FROM booking_request_preference p"
                + " JOIN booking_reservation r ON r.id = p.reservation_id"
                + " WHERE p.slot_id = ? AND r.status = 'PENDING_CONFIRMATION' LIMIT 1",
            resultSet -> resultSet.getString("reservation_id"),
            slotId);
    return reservationId != null;
  }

  @Override
  public int closeIfOpen(String slotId, int expectedRevision, Instant now) {
    return JdbcSupport.update(
        connections,
        "UPDATE booking_service_slot SET status = 'CLOSED', revision = revision + 1,"
            + " updated_at = ? WHERE id = ? AND status = 'OPEN' AND revision = ?",
        now,
        slotId,
        expectedRevision);
  }

  @Override
  public List<BoardSlot> listBoard(Instant from, Instant to, int limit) {
    return JdbcSupport.queryList(
        connections,
        "SELECT "
            + SLOT_COLUMNS
            + ", CASE WHEN o.slot_id IS NOT NULL THEN 'OCCUPIED'"
            + " WHEN EXISTS (SELECT 1 FROM booking_request_preference p"
            + " JOIN booking_reservation r ON r.id = p.reservation_id"
            + " WHERE p.slot_id = s.id AND r.status = 'PENDING_CONFIRMATION') THEN 'PENDING'"
            + " WHEN s.status = 'OPEN' THEN 'OPEN' ELSE 'CLOSED' END AS availability"
            + " FROM booking_service_slot s"
            + " LEFT JOIN booking_slot_occupancy o ON o.slot_id = s.id"
            + " WHERE s.start_at >= ? AND s.start_at < ?"
            + " ORDER BY s.start_at, s.id LIMIT ?",
        JdbcSlotGuard::mapBoard,
        from,
        to,
        limit);
  }

  private static BoardSlot mapBoard(ResultSet resultSet) throws SQLException {
    return new BoardSlot(mapSlot(resultSet), resultSet.getString("availability"));
  }

  private static ServiceSlot mapSlot(ResultSet resultSet) throws SQLException {
    return new ServiceSlot(
        resultSet.getString("id"),
        JdbcSupport.instant(resultSet, "start_at"),
        JdbcSupport.instant(resultSet, "end_at"),
        resultSet.getString("business_zone"),
        resultSet.getString("display_offset"),
        resultSet.getString("status"),
        resultSet.getInt("capacity"),
        resultSet.getString("rule_version"),
        resultSet.getInt("revision"));
  }
}
