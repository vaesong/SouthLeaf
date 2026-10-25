package cn.zhinan.adminbooking.adapter.jdbc;

import cn.zhinan.adminbooking.port.BookingPorts.BookingAudit;
import cn.zhinan.adminbooking.port.BookingPorts.NotificationOutbox;
import java.time.Instant;

/**
 * Audit trail and notification outbox. Both are written inside the business transaction so an
 * accepted command always leaves the same trail. Neither stores a token, a raw contact value or an
 * external subject.
 */
public final class JdbcBookingAudit implements BookingAudit, NotificationOutbox {
  private final JdbcConnectionProvider connections;

  public JdbcBookingAudit(JdbcConnectionProvider connections) {
    this.connections = connections;
  }

  @Override
  public void record(
      String actorId,
      String commandId,
      String objectType,
      String objectId,
      String action,
      String reasonCode,
      Instant now) {
    JdbcSupport.update(
        connections,
        "INSERT INTO booking_audit"
            + " (id, actor_id, command_id, object_type, object_id, action, reason_code, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        cn.zhinan.adminbooking.domain.BookingRules.newId(),
        actorId,
        commandId,
        objectType,
        objectId,
        action,
        reasonCode,
        now);
  }

  @Override
  public void enqueue(
      String intent, String recipientRef, String objectRef, Instant dueAt, Instant now) {
    JdbcSupport.update(
        connections,
        "INSERT INTO booking_notification_outbox"
            + " (id, intent, recipient_ref, object_ref, due_at, status, attempts, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)",
        cn.zhinan.adminbooking.domain.BookingRules.newId(),
        intent,
        recipientRef,
        objectRef,
        dueAt,
        now,
        now);
  }
}
