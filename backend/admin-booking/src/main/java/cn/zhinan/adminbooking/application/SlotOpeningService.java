package cn.zhinan.adminbooking.application;

import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.BookingFailure;
import cn.zhinan.adminbooking.domain.BookingModels.ServiceSlot;
import cn.zhinan.adminbooking.domain.BookingRules;
import cn.zhinan.adminbooking.domain.OperatingRule;
import cn.zhinan.adminbooking.port.BookingPorts.BookingAudit;
import cn.zhinan.adminbooking.port.BookingPorts.CommandLedger;
import cn.zhinan.adminbooking.port.BookingPorts.ScheduleRepository;
import cn.zhinan.adminbooking.port.BookingPorts.StoredCommand;
import cn.zhinan.adminbooking.port.BookingPorts.TransactionRunner;
import cn.zhinan.adminbooking.port.OperatingRulePorts.OperatingRuleRepository;
import cn.zhinan.adminbooking.port.OperatingRulePorts.StoredRule;
import cn.zhinan.adminbooking.port.SlotOpeningPorts.BoardSlot;
import cn.zhinan.adminbooking.port.SlotOpeningPorts.DaySlot;
import cn.zhinan.adminbooking.port.SlotOpeningPorts.SlotGuard;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Opens and closes slots for the studio. This path does not call {@code
 * BookingApplication.closeSlot}. Lead time and the furthest date are absolute instants: earliest is
 * {@code now} plus the rule's hours, and latest is {@code now} plus the rule's days, inclusive.
 *
 * <p>An open locks the operating-rule head before any slot row. Confirm, reschedule and this close
 * lock only the slot and never the rule head, so they cannot cycle with an open. Two opens
 * serialize on that one rule row before the overlap check.
 */
public final class SlotOpeningService {
  public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  public static final String DISPLAY_OFFSET = "+08:00";
  public static final String OPEN_SLOT = "OPEN_SLOT";
  public static final String OPEN_SLOT_WEEK = "OPEN_SLOT_WEEK";
  public static final String CLOSE_UNOCCUPIED_SLOT = "CLOSE_UNOCCUPIED_SLOT";
  public static final int MAX_WEEK_DAYS = 7;
  public static final int MAX_START_TIMES = 10;

  private final OperatingRuleRepository rules;
  private final ScheduleRepository schedule;
  private final SlotGuard guard;
  private final CommandLedger commands;
  private final BookingAudit audit;
  private final TransactionRunner transactions;
  private final Clock clock;

  public SlotOpeningService(
      OperatingRuleRepository rules,
      ScheduleRepository schedule,
      SlotGuard guard,
      CommandLedger commands,
      BookingAudit audit,
      TransactionRunner transactions,
      Clock clock) {
    this.rules = rules;
    this.schedule = schedule;
    this.guard = guard;
    this.commands = commands;
    this.audit = audit;
    this.transactions = transactions;
    this.clock = clock;
  }

  public List<BoardSlot> board(Instant from, Instant to, int limit) {
    if (from == null || to == null || !from.isBefore(to) || limit < 1)
      throw new BookingException(BookingFailure.INVALID_REQUEST, "TIME_RANGE_INVALID");
    return guard.listBoard(from, to, limit).stream()
        .filter(slot -> !"CLOSED".equals(slot.availability()))
        .toList();
  }

  public OpenedSlot open(String actorId, String idempotencyKey, LocalDate date, LocalTime time) {
    String actor = BookingRules.ref(actorId);
    String key = BookingRules.idempotencyKey(idempotencyKey);
    return transactions.inTransaction(() -> openLocked(actor, key, date, time));
  }

  public List<WeekItem> openWeek(
      String actorId, String idempotencyKey, List<LocalDate> dates, List<LocalTime> times) {
    String actor = BookingRules.ref(actorId);
    String key = BookingRules.idempotencyKey(idempotencyKey);
    List<LocalDate> days = distinct(dates, MAX_WEEK_DAYS, "SLOT_BATCH_INVALID");
    List<LocalTime> starts = distinct(times, MAX_START_TIMES, "SLOT_BATCH_INVALID");
    if (rules.current().isEmpty())
      throw new BookingException(BookingFailure.INVALID_REQUEST, "RULES_NOT_CONFIGURED");
    String digest = sha256(canonicalWeek(days, starts));
    Optional<StoredCommand> prior =
        transactions.inTransaction(() -> commands.find(actor, OPEN_SLOT_WEEK, key));
    if (prior.isPresent() && !digest.equals(prior.get().requestSha256()))
      throw new BookingException(BookingFailure.IDEMPOTENCY_CONFLICT, "IDEMPOTENCY_KEY_REUSE");
    List<WeekItem> items = new ArrayList<>();
    int opened = 0;
    for (LocalDate date : days) {
      for (LocalTime time : starts) {
        try {
          OpenedSlot slot = open(actor, itemKey(key, date, time), date, time);
          items.add(new WeekItem(date, time, true, null, slot.slot(), slot.replayed()));
          opened++;
        } catch (BookingException rejected) {
          if (rejected.failure() != BookingFailure.INVALID_REQUEST
              && rejected.failure() != BookingFailure.CONFLICT) throw rejected;
          items.add(new WeekItem(date, time, false, rejected.reason(), null, false));
        }
      }
    }
    if (prior.isEmpty()) {
      int recorded = opened;
      transactions.inTransaction(
          () ->
              commands.record(
                  actor, OPEN_SLOT_WEEK, key, digest, Integer.toString(recorded), clock.instant()));
    }
    return List.copyOf(items);
  }

  public ClosedSlot close(
      String actorId, String idempotencyKey, String slotId, int expectedRevision) {
    String actor = BookingRules.ref(actorId);
    String key = BookingRules.idempotencyKey(idempotencyKey);
    String id = BookingRules.uuid(slotId);
    if (expectedRevision < 1)
      throw new BookingException(BookingFailure.INVALID_REQUEST, "REVISION_INVALID");
    String digest = sha256(id);
    return transactions.inTransaction(
        () -> {
          // Slot row only. The rule head stays unlocked so this cannot deadlock with an open.
          ServiceSlot locked =
              schedule
                  .findSlotForUpdate(id)
                  .orElseThrow(
                      () -> new BookingException(BookingFailure.NOT_FOUND, "SLOT_NOT_FOUND"));
          if ("CLOSED".equals(locked.status())) return new ClosedSlot(locked, true);
          if (locked.revision() != expectedRevision)
            throw new BookingException(BookingFailure.VERSION_CONFLICT, "SLOT_REVISION_CONFLICT");
          Optional<StoredCommand> prior = commands.find(actor, CLOSE_UNOCCUPIED_SLOT, key);
          if (prior.isPresent() && !digest.equals(prior.get().requestSha256()))
            throw new BookingException(
                BookingFailure.IDEMPOTENCY_CONFLICT, "IDEMPOTENCY_KEY_REUSE");
          if (schedule.findOccupancy(id).isPresent())
            throw new BookingException(BookingFailure.CONFLICT, "SLOT_OCCUPIED");
          if (guard.pendingConfirmation(id))
            throw new BookingException(BookingFailure.CONFLICT, "SLOT_PENDING_REQUEST");
          Instant now = clock.instant();
          int updated = guard.closeIfOpen(id, expectedRevision, now);
          if (updated != 1)
            throw new BookingException(BookingFailure.VERSION_CONFLICT, "SLOT_REVISION_CONFLICT");
          audit.record(actor, id, "SERVICE_SLOT", id, "CLOSE", "SLOT_CLOSED", now);
          ServiceSlot closed =
              schedule
                  .findSlot(id)
                  .orElseThrow(
                      () ->
                          new BookingException(
                              BookingFailure.STORAGE_UNAVAILABLE, "SLOT_NOT_STORED"));
          if (prior.isEmpty()) commands.record(actor, CLOSE_UNOCCUPIED_SLOT, key, digest, id, now);
          return new ClosedSlot(closed, false);
        });
  }

  private OpenedSlot openLocked(String actor, String key, LocalDate date, LocalTime time) {
    // Rule head first, then slot rows. Confirm and reschedule never take this lock.
    Optional<Integer> locked = rules.lockHead();
    if (locked.isEmpty() || locked.get() < 1)
      throw new BookingException(BookingFailure.INVALID_REQUEST, "RULES_NOT_CONFIGURED");
    StoredRule stored =
        rules
            .current()
            .orElseThrow(
                () -> new BookingException(BookingFailure.INVALID_REQUEST, "RULES_NOT_CONFIGURED"));
    if (stored.version() != locked.get())
      throw new BookingException(BookingFailure.VERSION_CONFLICT, "RULE_VERSION_CHANGED");
    OperatingRule rule = stored.rule();
    Instant start = startAt(date, time);
    Instant end = start.plus(Duration.ofMinutes(rule.slotDurationMinutes()));
    BookingRules.slotStart(start, end);
    BookingRules.zone(BUSINESS_ZONE.getId());
    BookingRules.capacity(OperatingRule.CAPACITY);
    String ruleVersion = BookingRules.ref("operating-" + stored.version());
    String digest = sha256(date + "T" + time + "|v" + stored.version());
    Optional<StoredCommand> prior = commands.find(actor, OPEN_SLOT, key);
    if (prior.isPresent()) {
      if (!digest.equals(prior.get().requestSha256()))
        throw new BookingException(BookingFailure.IDEMPOTENCY_CONFLICT, "IDEMPOTENCY_KEY_REUSE");
      ServiceSlot existing =
          schedule
              .findSlot(prior.get().resultId())
              .orElseThrow(
                  () ->
                      new BookingException(BookingFailure.STORAGE_UNAVAILABLE, "SLOT_NOT_STORED"));
      return new OpenedSlot(existing, true);
    }
    Instant now = clock.instant();
    if (start.isBefore(now.plus(Duration.ofHours(rule.minAdvanceHours())))
        || start.isAfter(now.plus(Duration.ofDays(rule.maxAdvanceDays()))))
      throw new BookingException(BookingFailure.INVALID_REQUEST, "SLOT_OUTSIDE_WINDOW");
    Instant dayStart = date.atStartOfDay(BUSINESS_ZONE).toInstant();
    Instant dayEnd = date.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
    List<DaySlot> sameDay = guard.lockSlotsStartingBetween(dayStart, dayEnd);
    long openOnDay = sameDay.stream().filter(day -> "OPEN".equals(day.slot().status())).count();
    if (openOnDay >= BookingRules.MAX_SLOTS_PER_DAY)
      throw new BookingException(BookingFailure.CONFLICT, "SLOT_DAY_FULL");
    for (DaySlot day : sameDay) {
      boolean blocks = day.occupied() || "OPEN".equals(day.slot().status());
      boolean overlaps = day.slot().startAt().isBefore(end) && day.slot().endAt().isAfter(start);
      if (blocks && overlaps) throw new BookingException(BookingFailure.CONFLICT, "SLOT_OVERLAP");
    }
    String slotId = BookingRules.newId();
    ServiceSlot created =
        schedule.createSlot(
            slotId,
            start,
            end,
            BUSINESS_ZONE.getId(),
            DISPLAY_OFFSET,
            ruleVersion,
            OperatingRule.CAPACITY,
            now);
    audit.record(actor, slotId, "SERVICE_SLOT", slotId, "CREATE", "SLOT_CREATED", now);
    commands.record(actor, OPEN_SLOT, key, digest, slotId, now);
    return new OpenedSlot(created, false);
  }

  private static Instant startAt(LocalDate date, LocalTime time) {
    if (date == null || time == null || time.getSecond() != 0 || time.getNano() != 0)
      throw new BookingException(BookingFailure.INVALID_REQUEST, "SLOT_TIME_INVALID");
    return ZonedDateTime.of(date, time.withSecond(0).withNano(0), BUSINESS_ZONE).toInstant();
  }

  static String itemKey(String key, LocalDate date, LocalTime time) {
    return "w" + sha256(key + "|" + date + "|" + time).substring(0, 40);
  }

  private static String canonicalWeek(List<LocalDate> dates, List<LocalTime> times) {
    return dates.toString() + times.toString();
  }

  private static <T> List<T> distinct(List<T> values, int max, String reason) {
    if (values == null || values.isEmpty() || values.size() > max)
      throw new BookingException(BookingFailure.INVALID_REQUEST, reason);
    for (T value : values)
      if (value == null) throw new BookingException(BookingFailure.INVALID_REQUEST, reason);
    if (values.stream().distinct().count() != values.size())
      throw new BookingException(BookingFailure.INVALID_REQUEST, reason);
    return List.copyOf(values);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  public record OpenedSlot(ServiceSlot slot, boolean replayed) {}

  public record ClosedSlot(ServiceSlot slot, boolean replayed) {}

  public record WeekItem(
      LocalDate date,
      LocalTime time,
      boolean ok,
      String reason,
      ServiceSlot slot,
      boolean replayed) {}
}
