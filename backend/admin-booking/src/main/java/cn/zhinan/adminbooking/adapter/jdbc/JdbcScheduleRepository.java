package cn.zhinan.adminbooking.adapter.jdbc;

import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.BookingFailure;
import cn.zhinan.adminbooking.domain.BookingModels.ServiceSlot;
import cn.zhinan.adminbooking.domain.BookingModels.SlotAssignment;
import cn.zhinan.adminbooking.domain.BookingModels.SlotOccupancy;
import cn.zhinan.adminbooking.port.BookingPorts.ScheduleRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Slot and occupancy persistence. The exclusive hold is enforced by a unique key, not by a read.
 */
public final class JdbcScheduleRepository implements ScheduleRepository {
  private final JdbcConnectionProvider connections;

  public JdbcScheduleRepository(JdbcConnectionProvider connections) {
    this.connections = connections;
  }

  @Override
  public Optional<ServiceSlot> findSlot(String slotId) {
    ServiceSlot slot =
        JdbcSupport.queryOne(
            connections,
            "SELECT id, start_at, end_at, business_zone, display_offset, status, capacity,"
                + " rule_version, revision FROM booking_service_slot WHERE id = ?",
            JdbcScheduleRepository::mapSlot,
            slotId);
    return Optional.ofNullable(slot);
  }

  @Override
  public Optional<ServiceSlot> findSlotForUpdate(String slotId) {
    ServiceSlot slot =
        JdbcSupport.queryOne(
            connections,
            "SELECT id, start_at, end_at, business_zone, display_offset, status, capacity,"
                + " rule_version, revision FROM booking_service_slot WHERE id = ? FOR UPDATE",
            JdbcScheduleRepository::mapSlot,
            slotId);
    return Optional.ofNullable(slot);
  }

  @Override
  public List<ServiceSlot> listOpenSlots(Instant from, Instant to, int limit) {
    return JdbcSupport.queryList(
        connections,
        "SELECT id, start_at, end_at, business_zone, display_offset, status, capacity,"
            + " rule_version, revision FROM booking_service_slot WHERE status = 'OPEN' AND start_at"
            + " >= ? AND start_at < ? AND NOT EXISTS (SELECT 1 FROM booking_slot_occupancy"
            + " occupied WHERE occupied.slot_id = booking_service_slot.id)"
            + " ORDER BY start_at ASC, id ASC LIMIT ?",
        JdbcScheduleRepository::mapSlot,
        from,
        to,
        limit);
  }

  @Override
  public ServiceSlot createSlot(
      String slotId,
      Instant startAt,
      Instant endAt,
      String businessZone,
      String displayOffset,
      String ruleVersion,
      int capacity,
      Instant now) {
    JdbcSupport.update(
        connections,
        "INSERT INTO booking_service_slot"
            + " (id, start_at, end_at, business_zone, display_offset, status, capacity,"
            + " rule_version, revision, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?, 1, ?, ?)",
        slotId,
        startAt,
        endAt,
        businessZone,
        displayOffset,
        capacity,
        ruleVersion,
        now,
        now);
    return findSlot(slotId)
        .orElseThrow(
            () -> new BookingException(BookingFailure.STORAGE_UNAVAILABLE, "SLOT_NOT_STORED"));
  }

  @Override
  public int closeSlot(String slotId, String actorId, Instant now) {
    return JdbcSupport.update(
        connections,
        "UPDATE booking_service_slot SET status = 'CLOSED', revision = revision + 1, updated_at = ?"
            + " WHERE id = ? AND status = 'OPEN'",
        now,
        slotId);
  }

  @Override
  public Optional<SlotOccupancy> findOccupancy(String slotId) {
    SlotOccupancy occupancy =
        JdbcSupport.queryOne(
            connections,
            "SELECT slot_id, reservation_id, assigned_at, revision FROM booking_slot_occupancy"
                + " WHERE slot_id = ?",
            resultSet ->
                new SlotOccupancy(
                    resultSet.getString("slot_id"),
                    resultSet.getString("reservation_id"),
                    JdbcSupport.instant(resultSet, "assigned_at"),
                    resultSet.getInt("revision")),
            slotId);
    return Optional.ofNullable(occupancy);
  }

  @Override
  public SlotOccupancy occupy(String slotId, String reservationId, Instant now) {
    // The primary key on slot_id is the exclusive hold; a duplicate key is a lost race, not a
    // storage failure.
    JdbcSupport.updateProtected(
        connections,
        "SLOT_OCCUPANCY_CONFLICT",
        "INSERT INTO booking_slot_occupancy (slot_id, reservation_id, assigned_at, revision)"
            + " VALUES (?, ?, ?, 1)",
        slotId,
        reservationId,
        now);
    return findOccupancy(slotId)
        .orElseThrow(
            () -> new BookingException(BookingFailure.STORAGE_UNAVAILABLE, "OCCUPANCY_NOT_STORED"));
  }

  @Override
  public SlotOccupancy moveOccupancy(
      String previousSlotId, String newSlotId, String reservationId, Instant now) {
    int updated =
        JdbcSupport.updateProtected(
            connections,
            "SLOT_OCCUPANCY_CONFLICT",
            "UPDATE booking_slot_occupancy SET slot_id = ?, assigned_at = ?, revision = revision +"
                + " 1 WHERE slot_id = ? AND reservation_id = ?",
            newSlotId,
            now,
            previousSlotId,
            reservationId);
    if (updated == 0)
      throw new BookingException(BookingFailure.VERSION_CONFLICT, "OCCUPANCY_MOVED");
    return findOccupancy(newSlotId)
        .orElseThrow(
            () -> new BookingException(BookingFailure.STORAGE_UNAVAILABLE, "OCCUPANCY_NOT_STORED"));
  }

  @Override
  public void release(String slotId, Instant now) {
    JdbcSupport.update(connections, "DELETE FROM booking_slot_occupancy WHERE slot_id = ?", slotId);
  }

  @Override
  public void appendAssignment(SlotAssignment assignment) {
    JdbcSupport.update(
        connections,
        "INSERT INTO booking_slot_assignment_history"
            + " (id, slot_id, reservation_id, previous_slot_id, assigned_at, reason_code, revision)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
        cn.zhinan.adminbooking.domain.BookingRules.newId(),
        assignment.slotId(),
        assignment.reservationId(),
        assignment.previousSlotId(),
        assignment.assignedAt(),
        assignment.reasonCode(),
        assignment.revision());
  }

  private static ServiceSlot mapSlot(java.sql.ResultSet resultSet) throws java.sql.SQLException {
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
