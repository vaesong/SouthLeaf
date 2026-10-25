package cn.zhinan.adminbooking.application;

import cn.zhinan.adminbooking.domain.OutboxRules;
import cn.zhinan.adminbooking.port.BookingPorts.DeliveryOutcome;
import cn.zhinan.adminbooking.port.BookingPorts.NotificationDelivery;
import cn.zhinan.adminbooking.port.BookingPorts.OutboxMessage;
import cn.zhinan.adminbooking.port.BookingPorts.OutboxWorker;
import java.time.Instant;
import java.util.List;

/**
 * Drives the notification outbox.
 *
 * <p>The dispatcher leases a batch, hands every message to the transport and reports the outcome
 * under the lease. It never claims exactly once delivery: a lease can expire while a slow transport
 * is still running, so the same message may reach the transport twice. The dedup key travels with
 * the message so the transport can drop the repeat.
 *
 * <p>The attempt budget, lease length and retry delays come from the explicit policy supplied by
 * the runtime; this capability layer does not guess operating values.
 */
public final class OutboxDispatcher {
  private final OutboxWorker worker;
  private final NotificationDelivery delivery;
  private final OutboxPolicy policy;

  public OutboxDispatcher(OutboxWorker worker, NotificationDelivery delivery, OutboxPolicy policy) {
    this.worker = worker;
    this.delivery = delivery;
    this.policy = policy;
  }

  /** The result of one dispatch pass. */
  public record DispatchResult(int leased, int delivered, int retried, int dead) {
    public static DispatchResult empty() {
      return new DispatchResult(0, 0, 0, 0);
    }
  }

  /** Leases a batch and delivers it under the deployment supplied policy. */
  public DispatchResult dispatch(String owner, int limit, Instant now) {
    String workerOwner = OutboxRules.workerOwner(owner);
    int batch = OutboxRules.leaseBatch(Math.min(limit, policy.batchSize()));
    List<OutboxMessage> leased = worker.lease(workerOwner, batch, now, policy.leaseDuration());
    if (leased.isEmpty()) return DispatchResult.empty();

    int delivered = 0;
    int retried = 0;
    int dead = 0;
    for (OutboxMessage message : leased) {
      DeliveryOutcome outcome = deliver(message);
      if (outcome.delivered()) {
        worker.markDelivered(message.messageId(), workerOwner, now);
        delivered++;
        continue;
      }
      String reasonCode = OutboxRules.reasonCode(outcome.reasonCode());
      boolean goesDead =
          OutboxRules.becomesDead(outcome.retryable(), message.attempts(), policy.maxAttempts());
      if (goesDead) {
        worker.markDead(message.messageId(), workerOwner, reasonCode, now);
        dead++;
        continue;
      }
      worker.markFailed(
          message.messageId(),
          workerOwner,
          reasonCode,
          true,
          now,
          OutboxRules.nextAttemptAt(
              now, message.attempts(), policy.baseRetryDelay(), policy.maximumRetryDelay()));
      retried++;
    }
    return new DispatchResult(leased.size(), delivered, retried, dead);
  }

  /**
   * Hands one message to the transport. A transport that throws is treated as a retryable failure,
   * because the outcome of an unknown attempt is unknown rather than known bad.
   */
  private DeliveryOutcome deliver(OutboxMessage message) {
    try {
      DeliveryOutcome outcome = delivery.deliver(message);
      if (outcome == null) return DeliveryOutcome.retryable("DELIVERY_OUTCOME_MISSING");
      return outcome;
    } catch (RuntimeException failure) {
      return DeliveryOutcome.retryable("DELIVERY_THREW");
    }
  }
}
