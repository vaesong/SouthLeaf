package cn.zhinan.adminbooking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.BookingFailure;
import cn.zhinan.adminbooking.domain.OperatingRule;
import cn.zhinan.adminbooking.port.BookingPorts.CommandLedger;
import cn.zhinan.adminbooking.port.BookingPorts.StoredCommand;
import cn.zhinan.adminbooking.port.BookingPorts.TransactionRunner;
import cn.zhinan.adminbooking.port.OperatingRulePorts.OperatingRuleRepository;
import cn.zhinan.adminbooking.port.OperatingRulePorts.StoredRule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OperatingRuleServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
  private static final OperatingRule RULE = new OperatingRule(24, 30, 48, 120);

  @Test
  void missingHeadStaysUnconfiguredAndASaveAppendsWithoutRewritingHistory() {
    var repository = new MemoryRules();
    var service = service(repository, new MemoryCommands());
    assertThat(service.current()).isEmpty();
    var first = service.save("owner", 0, "k".repeat(16), RULE);
    assertThat(first.version()).isEqualTo(1);
    assertThat(first.replayed()).isFalse();
    var again = service.save("owner", 0, "k".repeat(16), RULE);
    assertThat(again.replayed()).isTrue();
    assertThat(again.version()).isEqualTo(1);
    assertThat(repository.versions).hasSize(1);
    var changed = new OperatingRule(1, 2, 3, 30);
    assertThatThrownBy(() -> service.save("owner", 0, "k".repeat(16), changed))
        .isInstanceOf(BookingException.class)
        .extracting(error -> ((BookingException) error).failure())
        .isEqualTo(BookingFailure.IDEMPOTENCY_CONFLICT);
    var second = service.save("owner", 1, "m".repeat(16), changed);
    assertThat(second.version()).isEqualTo(2);
    assertThat(service.current().orElseThrow().rule()).isEqualTo(changed);
    assertThat(service.history()).extracting(StoredRule::version).containsExactly(2, 1);
    assertThat(repository.versions.getFirst().rule()).isEqualTo(RULE);
  }

  @Test
  void boundsAndAStaleVersionAreRejected() {
    assertThatThrownBy(() -> new OperatingRule(-1, 30, 48, 120))
        .isInstanceOf(BookingException.class)
        .extracting(error -> ((BookingException) error).reason())
        .isEqualTo("RULE_FIELD_INVALID_MIN_ADVANCE_HOURS");
    assertThatThrownBy(() -> new OperatingRule(0, 181, 48, 120))
        .isInstanceOf(BookingException.class);
    assertThatThrownBy(() -> new OperatingRule(0, 1, 0, 120)).isInstanceOf(BookingException.class);
    assertThatThrownBy(() -> new OperatingRule(0, 1, 1, 29)).isInstanceOf(BookingException.class);
    var service = service(new MemoryRules(), new MemoryCommands());
    service.save("owner", 0, "k".repeat(16), RULE);
    assertThatThrownBy(() -> service.save("owner", 0, "n".repeat(16), RULE))
        .isInstanceOf(BookingException.class)
        .extracting(error -> ((BookingException) error).failure())
        .isEqualTo(BookingFailure.VERSION_CONFLICT);
  }

  private static OperatingRuleService service(MemoryRules rules, MemoryCommands commands) {
    return new OperatingRuleService(
        rules,
        commands,
        new TransactionRunner() {
          @Override
          public <T> T inTransaction(java.util.function.Supplier<T> work) {
            return work.get();
          }

          @Override
          public void inTransaction(Runnable work) {
            work.run();
          }
        },
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static final class MemoryRules implements OperatingRuleRepository {
    private final List<StoredRule> versions = new ArrayList<>();
    private final AtomicReference<Integer> head = new AtomicReference<>();

    @Override
    public Optional<StoredRule> current() {
      Integer version = head.get();
      if (version == null || version < 1) return Optional.empty();
      return versions.stream().filter(rule -> rule.version() == version).findFirst();
    }

    @Override
    public List<StoredRule> history() {
      return versions.reversed();
    }

    @Override
    public Optional<Integer> lockHead() {
      return Optional.ofNullable(head.get());
    }

    @Override
    public void insertVersion(
        int version, String payloadJson, String updatedBy, Instant updatedAt) {
      versions.add(
          new StoredRule(version, OperatingRule.fromStoredJson(payloadJson), updatedBy, updatedAt));
    }

    @Override
    public void insertHead(int version) {
      if (!head.compareAndSet(null, version))
        throw new BookingException(BookingFailure.VERSION_CONFLICT, "RULE_VERSION_CONFLICT");
    }

    @Override
    public void updateHead(int expectedVersion, int version) {
      if (!head.compareAndSet(expectedVersion, version))
        throw new BookingException(BookingFailure.VERSION_CONFLICT, "RULE_VERSION_CONFLICT");
    }
  }

  private static final class MemoryCommands implements CommandLedger {
    private final List<String> keys = new ArrayList<>();
    private final List<StoredCommand> commands = new ArrayList<>();

    @Override
    public Optional<StoredCommand> find(String actorId, String operation, String idempotencyKey) {
      int index = keys.indexOf(actorId + operation + idempotencyKey);
      return index < 0 ? Optional.empty() : Optional.of(commands.get(index));
    }

    @Override
    public void record(
        String actorId,
        String operation,
        String idempotencyKey,
        String requestSha256,
        String resultId,
        Instant completedAt) {
      keys.add(actorId + operation + idempotencyKey);
      commands.add(new StoredCommand(requestSha256, resultId));
    }
  }
}
