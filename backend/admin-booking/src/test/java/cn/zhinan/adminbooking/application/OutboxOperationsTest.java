package cn.zhinan.adminbooking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.BookingFailure;
import cn.zhinan.adminbooking.port.BookingPorts.BookingAudit;
import cn.zhinan.adminbooking.port.BookingPorts.CommandLedger;
import cn.zhinan.adminbooking.port.BookingPorts.OutboxMessage;
import cn.zhinan.adminbooking.port.BookingPorts.OutboxWorker;
import cn.zhinan.adminbooking.port.BookingPorts.StoredCommand;
import cn.zhinan.adminbooking.port.BookingPorts.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class OutboxOperationsTest {
  private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
  private static final String MESSAGE_ID = "00000000-0000-0000-0000-000000000001";
  private static final String KEY = "manual-recovery-key-0001";

  private static final class FakeWorker implements OutboxWorker {
    private boolean failed = true;
    private int attempts = 3;
    private int recoveryCalls;

    @Override
    public List<OutboxMessage> lease(String owner, int limit, Instant now, Duration duration) {
      return List.of();
    }

    @Override
    public void markDelivered(String messageId, String owner, Instant now) {}

    @Override
    public void markFailed(
        String messageId,
        String owner,
        String reasonCode,
        boolean retryable,
        Instant now,
        Instant retryAt) {}

    @Override
    public void markDead(String messageId, String owner, String reasonCode, Instant now) {}

    @Override
    public List<OutboxMessage> deadLetters(int limit) {
      return List.of();
    }

    @Override
    public boolean retryDeadLetter(String messageId, Instant now) {
      recoveryCalls++;
      if (!failed) return false;
      failed = false;
      return true;
    }

    @Override
    public int pendingCount(Instant now) {
      return failed ? 0 : 1;
    }
  }

  private static final class FakeCommands implements CommandLedger {
    private final Map<String, StoredCommand> commands = new HashMap<>();

    @Override
    public Optional<StoredCommand> find(String actor, String operation, String key) {
      return Optional.ofNullable(commands.get(actor + ':' + operation + ':' + key));
    }

    @Override
    public void record(
        String actor,
        String operation,
        String key,
        String digest,
        String result,
        Instant completedAt) {
      commands.put(actor + ':' + operation + ':' + key, new StoredCommand(digest, result));
    }
  }

  private static final class FakeAudit implements BookingAudit {
    private final List<String> actions = new ArrayList<>();

    @Override
    public void record(
        String actor,
        String command,
        String type,
        String id,
        String action,
        String reason,
        Instant now) {
      actions.add(actor + ':' + command + ':' + type + ':' + id + ':' + action + ':' + reason);
    }
  }

  private static final class DirectTransactions implements TransactionRunner {
    @Override
    public <T> T inTransaction(Supplier<T> work) {
      return work.get();
    }

    @Override
    public void inTransaction(Runnable work) {
      work.run();
    }
  }

  private static OutboxOperations operations(
      FakeWorker worker, FakeCommands commands, FakeAudit audit) {
    return new OutboxOperations(
        worker, commands, audit, new DirectTransactions(), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void manualRecoveryIsAuditedAndUsesAnIdempotentCommand() {
    FakeWorker worker = new FakeWorker();
    FakeCommands commands = new FakeCommands();
    FakeAudit audit = new FakeAudit();
    OutboxOperations operations = operations(worker, commands, audit);

    var first = operations.retryDeadLetter("operator-1", KEY, MESSAGE_ID, "PROVIDER_FIXED");
    var replay = operations.retryDeadLetter("operator-1", KEY, MESSAGE_ID, "PROVIDER_FIXED");

    assertThat(first.replayed()).isFalse();
    assertThat(replay)
        .isEqualTo(new OutboxOperations.RecoveryResult(first.commandId(), MESSAGE_ID, true));
    assertThat(worker.recoveryCalls).isEqualTo(1);
    assertThat(audit.actions)
        .hasSize(1)
        .allMatch(value -> value.contains(":MANUAL_RETRY:PROVIDER_FIXED"));
    assertThat(operations.pendingCount()).isEqualTo(1);
  }

  @Test
  void anIdempotencyKeyCannotBeReusedForAnotherRecoveryPayload() {
    OutboxOperations operations = operations(new FakeWorker(), new FakeCommands(), new FakeAudit());
    operations.retryDeadLetter("operator-1", KEY, MESSAGE_ID, "PROVIDER_FIXED");

    assertThatThrownBy(
            () -> operations.retryDeadLetter("operator-1", KEY, MESSAGE_ID, "RECIPIENT_FIXED"))
        .isInstanceOfSatisfying(
            BookingException.class,
            failure ->
                assertThat(failure.failure()).isEqualTo(BookingFailure.IDEMPOTENCY_CONFLICT));
  }

  @Test
  void aMessageThatIsNoLongerDeadCannotBeRequeued() {
    FakeWorker worker = new FakeWorker();
    worker.failed = false;
    FakeAudit audit = new FakeAudit();
    OutboxOperations operations = operations(worker, new FakeCommands(), audit);

    assertThatThrownBy(
            () -> operations.retryDeadLetter("operator-1", KEY, MESSAGE_ID, "PROVIDER_FIXED"))
        .isInstanceOfSatisfying(
            BookingException.class,
            failure -> assertThat(failure.failure()).isEqualTo(BookingFailure.VERSION_CONFLICT));
    assertThat(audit.actions).isEmpty();
  }

  @Test
  void recoveryReasonsMustBeStableCodesRatherThanOperatorFreeText() {
    OutboxOperations operations = operations(new FakeWorker(), new FakeCommands(), new FakeAudit());

    assertThatThrownBy(
            () ->
                operations.retryDeadLetter(
                    "operator-1", KEY, MESSAGE_ID, "fixed after call to customer"))
        .isInstanceOfSatisfying(
            BookingException.class,
            failure -> assertThat(failure.failure()).isEqualTo(BookingFailure.INVALID_REQUEST));
  }
}
