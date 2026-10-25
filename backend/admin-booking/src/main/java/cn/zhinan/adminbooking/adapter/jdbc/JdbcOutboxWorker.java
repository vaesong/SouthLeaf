package cn.zhinan.adminbooking.adapter.jdbc;

import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.BookingFailure;
import cn.zhinan.adminbooking.domain.BookingRules;
import cn.zhinan.adminbooking.domain.OutboxRules;
import cn.zhinan.adminbooking.port.BookingPorts.OutboxMessage;
import cn.zhinan.adminbooking.port.BookingPorts.OutboxWorker;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The outbox worker over the booking notification table.
 *
 * <p>The lease is taken with {@code SELECT ... FOR UPDATE SKIP LOCKED}, so two workers never lease
 * the same message and a worker never blocks behind another one. The lease deadline is stored on
 * the row, so a worker that dies leaves the message recoverable once the lease expires.
 */
public final class JdbcOutboxWorker implements OutboxWorker {
  private final JdbcConnectionProvider connections;

  public JdbcOutboxWorker(JdbcConnectionProvider connections) {
    this.connections = connections;
  }

  @Override
  public List<OutboxMessage> lease(String owner, int limit, Instant now, Duration leaseDuration) {
    owner = OutboxRules.workerOwner(owner);
    limit = OutboxRules.leaseBatch(limit);
    leaseDuration = OutboxRules.leaseDuration(leaseDuration);
    Instant leaseUntil = now.plus(leaseDuration);
    List<OutboxMessage> leased = new ArrayList<>();

    // The row lock and the lease updates share a transaction. SKIP LOCKED lets another worker take
    // a different batch without waiting; the lease_owner predicate is the durable ownership check.
    connections.begin();
    try {
      List<OutboxMessage> candidates =
          JdbcSupport.queryList(
              connections,
              "SELECT id, intent, recipient_ref, object_ref, due_at, status, attempts, lease_until,"
                  + " last_error FROM booking_notification_outbox"
                  + " WHERE status IN ('PENDING', 'LEASED') AND due_at <= ?"
                  + " AND (lease_until IS NULL OR lease_until <= ?)"
                  + " ORDER BY due_at, id LIMIT ? FOR UPDATE SKIP LOCKED",
              JdbcOutboxWorker::mapMessage,
              now,
              now,
              limit);

      for (OutboxMessage candidate : candidates) {
        int updated =
            JdbcSupport.update(
                connections,
                "UPDATE booking_notification_outbox SET status = 'LEASED', lease_until = ?,"
                    + " lease_owner = ?, attempts = attempts + 1, updated_at = ? WHERE id = ? AND"
                    + " (lease_until IS NULL OR lease_until <= ?)",
                leaseUntil,
                owner,
                now,
                candidate.messageId(),
                now);
        if (updated == 1) {
          leased.add(
              new OutboxMessage(
                  candidate.messageId(),
                  candidate.intent(),
                  candidate.recipientRef(),
                  candidate.objectRef(),
                  candidate.dedupKey(),
                  candidate.dueAt(),
                  "LEASED",
                  candidate.attempts() + 1,
                  leaseUntil,
                  owner,
                  candidate.lastError()));
        }
      }
      connections.commit();
      return leased;
    } catch (RuntimeException failure) {
      connections.rollback();
      throw failure;
    }
  }

  @Override
  public void markDelivered(String messageId, String owner, Instant now) {
    int updated =
        JdbcSupport.update(
            connections,
            "UPDATE booking_notification_outbox"
                + " SET status = 'SENT', lease_until = NULL, lease_owner = NULL, last_error = NULL,"
                + " updated_at = ? WHERE id = ? AND lease_owner = ?",
            now,
            messageId,
            OutboxRules.workerOwner(owner));
    requireLease(updated);
  }

  @Override
  public void markFailed(
      String messageId,
      String owner,
      String reasonCode,
      boolean retryable,
      Instant now,
      Instant retryAt) {
    if (!retryable) {
      markDead(messageId, owner, reasonCode, now);
      return;
    }
    int updated =
        JdbcSupport.update(
            connections,
            "UPDATE booking_notification_outbox"
                + " SET status = 'PENDING', lease_until = NULL, lease_owner = NULL, due_at = ?,"
                + " last_error = ?, updated_at = ? WHERE id = ? AND lease_owner = ?",
            retryAt,
            OutboxRules.reasonCode(reasonCode),
            now,
            messageId,
            OutboxRules.workerOwner(owner));
    requireLease(updated);
  }

  @Override
  public void markDead(String messageId, String owner, String reasonCode, Instant now) {
    int updated =
        JdbcSupport.update(
            connections,
            "UPDATE booking_notification_outbox"
                + " SET status = 'FAILED', lease_until = NULL, lease_owner = NULL, last_error = ?,"
                + " updated_at = ? WHERE id = ? AND lease_owner = ?",
            OutboxRules.reasonCode(reasonCode),
            now,
            messageId,
            OutboxRules.workerOwner(owner));
    requireLease(updated);
  }

  private static void requireLease(int updated) {
    if (updated != 1)
      throw new BookingException(BookingFailure.VERSION_CONFLICT, "OUTBOX_LEASE_LOST");
  }

  @Override
  public List<OutboxMessage> deadLetters(int limit) {
    return JdbcSupport.queryList(
        connections,
        "SELECT id, intent, recipient_ref, object_ref, due_at, status, attempts, lease_until,"
            + " last_error FROM booking_notification_outbox WHERE status = 'FAILED'"
            + " ORDER BY updated_at, id LIMIT ?",
        JdbcOutboxWorker::mapMessage,
        OutboxRules.leaseBatch(limit));
  }

  @Override
  public boolean retryDeadLetter(String messageId, Instant now) {
    int updated =
        JdbcSupport.update(
            connections,
            "UPDATE booking_notification_outbox SET status = 'PENDING', due_at = ?,"
                + " lease_until = NULL, lease_owner = NULL, updated_at = ?"
                + " WHERE id = ? AND status = 'FAILED' AND lease_until IS NULL"
                + " AND lease_owner IS NULL",
            now,
            now,
            messageId);
    return updated == 1;
  }

  @Override
  public int pendingCount(Instant now) {
    Integer count =
        JdbcSupport.queryOne(
            connections,
            "SELECT COUNT(*) FROM booking_notification_outbox"
                + " WHERE status IN ('PENDING', 'LEASED') AND due_at <= ?",
            resultSet -> resultSet.getInt(1),
            now);
    return count == null ? 0 : count;
  }

  private static OutboxMessage mapMessage(ResultSet resultSet) throws SQLException {
    return new OutboxMessage(
        resultSet.getString("id"),
        resultSet.getString("intent"),
        resultSet.getString("recipient_ref"),
        resultSet.getString("object_ref"),
        null,
        JdbcSupport.instant(resultSet, "due_at"),
        resultSet.getString("status"),
        resultSet.getInt("attempts"),
        JdbcSupport.instant(resultSet, "lease_until"),
        null,
        resultSet.getString("last_error"));
  }

  /** A stable key a transport can use to drop a repeated delivery of the same business fact. */
  public static String dedupKey(String intent, String objectRef) {
    return BookingRules.digestOf(intent, objectRef);
  }
}
