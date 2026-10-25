package cn.zhinan.adminbooking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.adminbooking.domain.OutboxRules;
import cn.zhinan.adminbooking.port.BookingPorts.DeliveryOutcome;
import cn.zhinan.adminbooking.port.BookingPorts.NotificationDelivery;
import cn.zhinan.adminbooking.port.BookingPorts.OutboxMessage;
import cn.zhinan.adminbooking.port.BookingPorts.OutboxWorker;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The outbox dispatcher rules: lease, deliver, retry, dead letter. */
class OutboxDispatcherTest {
  private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
  private static final OutboxPolicy POLICY =
      new OutboxPolicy(5, Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofHours(1), 100);

  private static OutboxPolicy policy(int maxAttempts) {
    return new OutboxPolicy(
        maxAttempts, Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofHours(1), 100);
  }

  /** An in-memory worker that records what the dispatcher reports. */
  private static final class FakeWorker implements OutboxWorker {
    private final List<OutboxMessage> queue;
    private final List<String> report = new ArrayList<>();
    private Instant retryAt = NOW;

    FakeWorker(List<OutboxMessage> queue) {
      this.queue = queue;
    }

    @Override
    public List<OutboxMessage> lease(String owner, int limit, Instant now, Duration leaseDuration) {
      List<OutboxMessage> leased = new ArrayList<>();
      for (OutboxMessage message : queue) {
        if (leased.size() >= limit) break;
        leased.add(
            new OutboxMessage(
                message.messageId(),
                message.intent(),
                message.recipientRef(),
                message.objectRef(),
                message.dedupKey(),
                message.dueAt(),
                "LEASED",
                message.attempts() + 1,
                now.plus(leaseDuration),
                owner,
                message.lastError()));
      }
      queue.removeAll(leased);
      return leased;
    }

    @Override
    public void markDelivered(String messageId, String owner, Instant now) {
      report.add("DELIVERED:" + messageId);
    }

    @Override
    public void markFailed(
        String messageId,
        String owner,
        String reasonCode,
        boolean retryable,
        Instant now,
        Instant retryAt) {
      this.retryAt = retryAt;
      report.add("RETRY:" + messageId + ':' + reasonCode);
    }

    @Override
    public void markDead(String messageId, String owner, String reasonCode, Instant now) {
      report.add("DEAD:" + messageId + ':' + reasonCode);
    }

    @Override
    public List<OutboxMessage> deadLetters(int limit) {
      return List.of();
    }

    @Override
    public boolean retryDeadLetter(String messageId, Instant now) {
      return false;
    }

    @Override
    public int pendingCount(Instant now) {
      return queue.size();
    }
  }

  private static OutboxMessage message(String id, int attempts) {
    return new OutboxMessage(
        id,
        "RESERVATION_CONFIRMED",
        "customer-1",
        "reservation-1",
        "dedup-key",
        NOW,
        "PENDING",
        attempts,
        null,
        null,
        null);
  }

  private static final class RecordingDelivery implements NotificationDelivery {
    private final DeliveryOutcome outcome;
    private final List<String> seen = new ArrayList<>();

    RecordingDelivery(DeliveryOutcome outcome) {
      this.outcome = outcome;
    }

    @Override
    public DeliveryOutcome deliver(OutboxMessage message) {
      seen.add(message.messageId());
      return outcome;
    }
  }

  @Test
  void aSuccessfulDeliveryIsReportedAsDelivered() {
    FakeWorker worker = new FakeWorker(new ArrayList<>(List.of(message("m1", 0))));
    RecordingDelivery delivery = new RecordingDelivery(DeliveryOutcome.success());

    OutboxDispatcher.DispatchResult result =
        new OutboxDispatcher(worker, delivery, POLICY).dispatch("worker-1", 10, NOW);

    assertThat(result.delivered()).isEqualTo(1);
    assertThat(result.retried()).isZero();
    assertThat(result.dead()).isZero();
    assertThat(worker.report).containsExactly("DELIVERED:m1");
  }

  @Test
  void aRetryableFailureSchedulesTheNextAttempt() {
    FakeWorker worker = new FakeWorker(new ArrayList<>(List.of(message("m1", 0))));
    RecordingDelivery delivery =
        new RecordingDelivery(DeliveryOutcome.retryable("PROVIDER_TIMEOUT"));

    OutboxDispatcher.DispatchResult result =
        new OutboxDispatcher(worker, delivery, POLICY).dispatch("worker-1", 10, NOW);

    assertThat(result.retried()).isEqualTo(1);
    assertThat(result.dead()).isZero();
    assertThat(worker.report).containsExactly("RETRY:m1:PROVIDER_TIMEOUT");
    assertThat(worker.retryAt).isAfter(NOW);
  }

  @Test
  void aPermanentFailureBecomesDeadWithoutAnotherAttempt() {
    FakeWorker worker = new FakeWorker(new ArrayList<>(List.of(message("m1", 0))));
    RecordingDelivery delivery =
        new RecordingDelivery(DeliveryOutcome.permanent("RECIPIENT_INVALID"));

    OutboxDispatcher.DispatchResult result =
        new OutboxDispatcher(worker, delivery, POLICY).dispatch("worker-1", 10, NOW);

    assertThat(result.dead()).isEqualTo(1);
    assertThat(result.retried()).isZero();
    assertThat(worker.report).containsExactly("DEAD:m1:RECIPIENT_INVALID");
  }

  @Test
  void aRetryableFailureWithAnExhaustedBudgetBecomesDead() {
    // Five attempts are already used and the budget is five, so no further attempt is allowed.
    FakeWorker worker = new FakeWorker(new ArrayList<>(List.of(message("m1", 5))));
    RecordingDelivery delivery =
        new RecordingDelivery(DeliveryOutcome.retryable("PROVIDER_TIMEOUT"));

    OutboxDispatcher.DispatchResult result =
        new OutboxDispatcher(worker, delivery, policy(5)).dispatch("worker-1", 10, NOW);

    assertThat(result.dead()).isEqualTo(1);
    assertThat(result.retried()).isZero();
    assertThat(worker.report).containsExactly("DEAD:m1:PROVIDER_TIMEOUT");
  }

  @Test
  void aTransportThatThrowsIsTreatedAsAnUnknownRetryableOutcome() {
    FakeWorker worker = new FakeWorker(new ArrayList<>(List.of(message("m1", 0))));
    NotificationDelivery throwing =
        message -> {
          throw new IllegalStateException("provider timeout");
        };

    OutboxDispatcher.DispatchResult result =
        new OutboxDispatcher(worker, throwing, POLICY).dispatch("worker-1", 10, NOW);

    assertThat(result.retried()).isEqualTo(1);
    assertThat(worker.report).containsExactly("RETRY:m1:DELIVERY_THREW");
  }

  @Test
  void anEmptyQueueIsANoOp() {
    FakeWorker worker = new FakeWorker(new ArrayList<>());
    RecordingDelivery delivery = new RecordingDelivery(DeliveryOutcome.success());

    OutboxDispatcher.DispatchResult result =
        new OutboxDispatcher(worker, delivery, POLICY).dispatch("worker-1", 10, NOW);

    assertThat(result.leased()).isZero();
    assertThat(delivery.seen).isEmpty();
  }

  @Test
  void retryDelayUsesTheSuppliedPolicyAndIsCapped() {
    Duration base = Duration.ofSeconds(30);
    Duration maximum = Duration.ofHours(1);
    assertThat(OutboxRules.retryDelay(1, base, maximum)).isEqualTo(base);
    assertThat(OutboxRules.retryDelay(2, base, maximum))
        .isGreaterThan(OutboxRules.retryDelay(1, base, maximum));
    assertThat(OutboxRules.retryDelay(3, base, maximum))
        .isGreaterThan(OutboxRules.retryDelay(2, base, maximum));
    assertThat(OutboxRules.retryDelay(1000, base, maximum)).isEqualTo(maximum);
  }

  @Test
  void attemptBudgetAndLeaseLengthMustBeExplicitAndBounded() {
    assertThat(OutboxRules.maxAttempts(3)).isEqualTo(3);
    assertThatThrownBy(() -> OutboxRules.maxAttempts(0))
        .isInstanceOf(cn.zhinan.adminbooking.domain.BookingException.class);
    assertThatThrownBy(() -> OutboxRules.maxAttempts(101))
        .isInstanceOf(cn.zhinan.adminbooking.domain.BookingException.class);
    assertThat(OutboxRules.leaseDuration(Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(30));
    assertThatThrownBy(() -> OutboxRules.leaseDuration(Duration.ZERO))
        .isInstanceOf(cn.zhinan.adminbooking.domain.BookingException.class);
    assertThatThrownBy(() -> OutboxRules.leaseDuration(Duration.ofHours(2)))
        .isInstanceOf(cn.zhinan.adminbooking.domain.BookingException.class);
  }

  @Test
  void workerOwnerIsBoundedAndContainsNoUnexpectedCharacters() {
    assertThat(OutboxRules.workerOwner("worker-1")).isEqualTo("worker-1");
    assertThatThrownBy(() -> OutboxRules.workerOwner("bad worker"))
        .isInstanceOf(cn.zhinan.adminbooking.domain.BookingException.class);
    assertThatThrownBy(() -> OutboxRules.workerOwner("x".repeat(65)))
        .isInstanceOf(cn.zhinan.adminbooking.domain.BookingException.class);
  }

  @Test
  void providerTextIsNotPersistedAsAnOutboxError() {
    assertThat(OutboxRules.reasonCode("PROVIDER_TIMEOUT")).isEqualTo("PROVIDER_TIMEOUT");
    assertThat(OutboxRules.reasonCode("token=secret, raw provider response"))
        .isEqualTo("DELIVERY_FAILED");
  }
}
