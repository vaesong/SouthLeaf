package cn.zhinan.adminbooking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.BookingFailure;
import cn.zhinan.adminbooking.domain.BookingModels.ServiceSlot;
import cn.zhinan.adminbooking.domain.BookingModels.SlotAssignment;
import cn.zhinan.adminbooking.domain.BookingModels.SlotOccupancy;
import cn.zhinan.adminbooking.domain.BookingRules;
import cn.zhinan.adminbooking.domain.OperatingRule;
import cn.zhinan.adminbooking.port.BookingPorts.CommandLedger;
import cn.zhinan.adminbooking.port.BookingPorts.ScheduleRepository;
import cn.zhinan.adminbooking.port.BookingPorts.StoredCommand;
import cn.zhinan.adminbooking.port.BookingPorts.TransactionRunner;
import cn.zhinan.adminbooking.port.OperatingRulePorts.OperatingRuleRepository;
import cn.zhinan.adminbooking.port.OperatingRulePorts.StoredRule;
import cn.zhinan.adminbooking.port.SlotOpeningPorts.BoardSlot;
import cn.zhinan.adminbooking.port.SlotOpeningPorts.DaySlot;
import cn.zhinan.adminbooking.port.SlotOpeningPorts.SlotGuard;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class SlotOpeningServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
  private static final OperatingRule RULE = new OperatingRule(24, 30, 48, 120);
  private static final String KEY = "k".repeat(16);
  private static final LocalDate OPEN_DAY = LocalDate.parse("2026-11-03");
  private static final LocalTime TEN = LocalTime.parse("10:00");

  @Test
  void refusesToOpenWhenNoRuleIsSaved() {
    Fixture fixture = new Fixture();
    fixture.rule = Optional.empty();
    assertThatThrownBy(() -> fixture.service.open("owner", KEY, OPEN_DAY, TEN))
        .isInstanceOf(BookingException.class)
        .extracting(error -> ((BookingException) error).reason())
        .isEqualTo("RULES_NOT_CONFIGURED");
    assertThat(fixture.slots).isEmpty();
  }

  @Test
  void opensOneSlotFromTheCurrentRuleAndRejectsOverlapDayLimitAndWindow() {
    Fixture fixture = new Fixture();
    var opened = fixture.service.open("owner", KEY, OPEN_DAY, TEN);
    assertThat(opened.replayed()).isFalse();
    assertThat(opened.slot().businessZone()).isEqualTo("Asia/Shanghai");
    assertThat(opened.slot().displayOffset()).isEqualTo("+08:00");
    assertThat(opened.slot().ruleVersion()).isEqualTo("operating-1");
    assertThat(opened.slot().status()).isEqualTo("OPEN");
    assertThat(opened.slot().endAt()).isEqualTo(opened.slot().startAt().plusSeconds(120 * 60));
    assertThat(fixture.service.open("owner", KEY, OPEN_DAY, TEN).slot().slotId())
        .isEqualTo(opened.slot().slotId());
    assertThat(fixture.slots).hasSize(1);

    assertThatThrownBy(
            () -> fixture.service.open("owner", "m".repeat(16), OPEN_DAY, LocalTime.parse("11:00")))
        .extracting(error -> ((BookingException) error).reason())
        .isEqualTo("SLOT_OVERLAP");
    assertThat(fixture.slots).hasSize(1);

    assertThatThrownBy(
            () ->
                fixture.service.open(
                    "owner",
                    "n".repeat(16),
                    LocalDate.parse("2026-11-01"),
                    LocalTime.parse("18:00")))
        .extracting(error -> ((BookingException) error).reason())
        .isEqualTo("SLOT_OUTSIDE_WINDOW");

    var edge =
        fixture.service.open(
            "owner", "e".repeat(16), LocalDate.parse("2026-12-01"), LocalTime.parse("08:00"));
    assertThat(edge.slot().startAt()).isEqualTo(NOW.plus(java.time.Duration.ofDays(30)));
    assertThatThrownBy(
            () ->
                fixture.service.open(
                    "owner",
                    "f".repeat(16),
                    LocalDate.parse("2026-12-01"),
                    LocalTime.parse("08:01")))
        .extracting(error -> ((BookingException) error).reason())
        .isEqualTo("SLOT_OUTSIDE_WINDOW");
  }

  @Test
  void stopsAtTwentyFourOpenSlotsOnTheSameDay() {
    Fixture fixture = new Fixture();
    for (int index = 0; index < BookingRules.MAX_SLOTS_PER_DAY; index++) {
      Instant start = OPEN_DAY.atTime(TEN).atZone(SlotOpeningService.BUSINESS_ZONE).toInstant();
      fixture.schedule.createSlot(
          BookingRules.newId(),
          start.plusSeconds(index),
          start.plusSeconds(index + 60),
          "Asia/Shanghai",
          "+08:00",
          "seed",
          1,
          NOW);
    }
    assertThatThrownBy(() -> fixture.service.open("owner", KEY, OPEN_DAY, LocalTime.parse("16:00")))
        .extracting(error -> ((BookingException) error).reason())
        .isEqualTo("SLOT_DAY_FULL");
    assertThat(fixture.slots).hasSize(24);
  }

  @Test
  void weekKeepsSuccessesWhenAnotherStartOverlaps() {
    Fixture fixture = new Fixture();
    fixture.service.open("owner", "a".repeat(16), OPEN_DAY, TEN);
    var items =
        fixture.service.openWeek(
            "owner",
            "b".repeat(16),
            List.of(OPEN_DAY, LocalDate.parse("2026-11-04")),
            List.of(TEN));
    assertThat(items).hasSize(2);
    assertThat(items.get(0).ok()).isFalse();
    assertThat(items.get(0).reason()).isEqualTo("SLOT_OVERLAP");
    assertThat(items.get(1).ok()).isTrue();
    assertThat(fixture.slots).hasSize(2);
    fixture.service.openWeek(
        "owner", "b".repeat(16), List.of(OPEN_DAY, LocalDate.parse("2026-11-04")), List.of(TEN));
    assertThat(fixture.slots).hasSize(2);
  }

  @Test
  void closeRejectsOccupancyAndPendingRequestsAndIsIdempotentOnceClosed() {
    Fixture fixture = new Fixture();
    ServiceSlot slot = fixture.service.open("owner", KEY, OPEN_DAY, TEN).slot();
    fixture.occupied.add(slot.slotId());
    assertThatThrownBy(() -> fixture.service.close("owner", "c".repeat(16), slot.slotId(), 1))
        .extracting(error -> ((BookingException) error).reason())
        .isEqualTo("SLOT_OCCUPIED");
    assertThat(fixture.slots.get(slot.slotId()).status()).isEqualTo("OPEN");
    assertThat(fixture.slots.get(slot.slotId()).revision()).isEqualTo(1);

    fixture.occupied.remove(slot.slotId());
    fixture.pending.add(slot.slotId());
    assertThatThrownBy(() -> fixture.service.close("owner", "d".repeat(16), slot.slotId(), 1))
        .extracting(error -> ((BookingException) error).reason())
        .isEqualTo("SLOT_PENDING_REQUEST");
    assertThat(fixture.slots.get(slot.slotId()).revision()).isEqualTo(1);

    fixture.pending.remove(slot.slotId());
    var closed = fixture.service.close("owner", "e".repeat(16), slot.slotId(), 1);
    assertThat(closed.slot().status()).isEqualTo("CLOSED");
    assertThat(closed.slot().revision()).isEqualTo(2);
    var again = fixture.service.close("owner", "f".repeat(16), slot.slotId(), 1);
    assertThat(again.slot().status()).isEqualTo("CLOSED");
    assertThat(again.slot().revision()).isEqualTo(2);
    assertThat(again.replayed()).isTrue();
  }

  @Test
  void anOpenSlotStillRequiresTheCurrentRevision() {
    Fixture fixture = new Fixture();
    ServiceSlot slot = fixture.service.open("owner", KEY, OPEN_DAY, TEN).slot();
    assertThatThrownBy(() -> fixture.service.close("owner", "g".repeat(16), slot.slotId(), 4))
        .extracting(error -> ((BookingException) error).failure())
        .isEqualTo(BookingFailure.VERSION_CONFLICT);
    assertThat(fixture.slots.get(slot.slotId()).status()).isEqualTo("OPEN");
  }

  @Test
  void openingLocksTheRuleHeadBeforeAnySlotAndClosingDoesNot() {
    Fixture fixture = new Fixture();
    ServiceSlot slot = fixture.service.open("owner", KEY, OPEN_DAY, TEN).slot();
    assertThat(fixture.locks).containsExactly("rule-head", "day-slots");
    fixture.locks.clear();
    fixture.service.close("owner", "h".repeat(16), slot.slotId(), 1);
    assertThat(fixture.locks).containsExactly("slot-row");
  }

  private static final class Fixture {
    private final LinkedHashMap<String, ServiceSlot> slots = new LinkedHashMap<>();
    private final Set<String> occupied = ConcurrentHashMap.newKeySet();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final LinkedHashMap<String, StoredCommand> commands = new LinkedHashMap<>();
    private final List<String> locks = new ArrayList<>();
    private Optional<StoredRule> rule = Optional.of(new StoredRule(1, RULE, "owner", NOW));
    private final MemorySchedule schedule = new MemorySchedule();
    private final SlotOpeningService service =
        new SlotOpeningService(
            new MemoryRules(),
            schedule,
            new MemoryGuard(),
            new MemoryCommands(),
            (actorId, commandId, objectType, objectId, action, reasonCode, now) -> {},
            new MemoryTransactions(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final class MemoryRules implements OperatingRuleRepository {
      @Override
      public Optional<StoredRule> current() {
        return rule;
      }

      @Override
      public Optional<Integer> lockHead() {
        locks.add("rule-head");
        return rule.map(StoredRule::version);
      }

      @Override
      public void insertVersion(int version, String payloadJson, String actorId, Instant now) {}

      @Override
      public void insertHead(int version) {}

      @Override
      public void updateHead(int expectedVersion, int nextVersion) {}

      @Override
      public List<StoredRule> history() {
        return rule.stream().toList();
      }
    }

    private final class MemorySchedule implements ScheduleRepository {
      @Override
      public Optional<ServiceSlot> findSlot(String slotId) {
        return Optional.ofNullable(slots.get(slotId));
      }

      @Override
      public Optional<ServiceSlot> findSlotForUpdate(String slotId) {
        locks.add("slot-row");
        return findSlot(slotId);
      }

      @Override
      public List<ServiceSlot> listOpenSlots(Instant from, Instant to, int limit) {
        throw new UnsupportedOperationException();
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
        ServiceSlot slot =
            new ServiceSlot(
                slotId,
                startAt,
                endAt,
                businessZone,
                displayOffset,
                "OPEN",
                capacity,
                ruleVersion,
                1);
        slots.put(slotId, slot);
        return slot;
      }

      @Override
      public int closeSlot(String slotId, String actorId, Instant now) {
        throw new UnsupportedOperationException("staff close must not call closeSlot");
      }

      @Override
      public Optional<SlotOccupancy> findOccupancy(String slotId) {
        return occupied.contains(slotId)
            ? Optional.of(new SlotOccupancy(slotId, "reservation", NOW, 1))
            : Optional.empty();
      }

      @Override
      public SlotOccupancy occupy(String slotId, String reservationId, Instant now) {
        throw new UnsupportedOperationException();
      }

      @Override
      public SlotOccupancy moveOccupancy(
          String previousSlotId, String newSlotId, String reservationId, Instant now) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void release(String slotId, Instant now) {}

      @Override
      public void appendAssignment(SlotAssignment assignment) {}
    }

    private final class MemoryGuard implements SlotGuard {
      @Override
      public List<DaySlot> lockSlotsStartingBetween(Instant from, Instant to) {
        locks.add("day-slots");
        List<DaySlot> day = new ArrayList<>();
        for (ServiceSlot slot : slots.values()) {
          if (!slot.startAt().isBefore(from) && slot.startAt().isBefore(to))
            day.add(new DaySlot(slot, occupied.contains(slot.slotId())));
        }
        return day;
      }

      @Override
      public boolean pendingConfirmation(String slotId) {
        return pending.contains(slotId);
      }

      @Override
      public int closeIfOpen(String slotId, int expectedRevision, Instant now) {
        ServiceSlot slot = slots.get(slotId);
        if (slot == null || !"OPEN".equals(slot.status()) || slot.revision() != expectedRevision)
          return 0;
        slots.put(
            slotId,
            new ServiceSlot(
                slot.slotId(),
                slot.startAt(),
                slot.endAt(),
                slot.businessZone(),
                slot.displayOffset(),
                "CLOSED",
                slot.capacity(),
                slot.ruleVersion(),
                slot.revision() + 1));
        return 1;
      }

      @Override
      public List<BoardSlot> listBoard(Instant from, Instant to, int limit) {
        return List.of();
      }
    }

    private final class MemoryCommands implements CommandLedger {
      @Override
      public Optional<StoredCommand> find(String actorId, String operation, String idempotencyKey) {
        return Optional.ofNullable(commands.get(actorId + "|" + operation + "|" + idempotencyKey));
      }

      @Override
      public void record(
          String actorId,
          String operation,
          String idempotencyKey,
          String requestSha256,
          String resultId,
          Instant completedAt) {
        commands.put(
            actorId + "|" + operation + "|" + idempotencyKey,
            new StoredCommand(requestSha256, resultId));
      }
    }

    private static final class MemoryTransactions implements TransactionRunner {
      @Override
      public <T> T inTransaction(java.util.function.Supplier<T> work) {
        return work.get();
      }

      @Override
      public void inTransaction(Runnable work) {
        work.run();
      }
    }
  }
}
