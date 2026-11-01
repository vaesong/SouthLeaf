package cn.zhinan.adminbooking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.adminbooking.adapter.jdbc.JdbcBookingAudit;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcCommandLedger;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcConnectionProvider;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcOperatingRuleRepository;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcScheduleRepository;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcSlotGuard;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcTransactionRunner;
import cn.zhinan.adminbooking.application.OperatingRuleService;
import cn.zhinan.adminbooking.application.SlotOpeningService;
import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.BookingRules;
import cn.zhinan.adminbooking.domain.OperatingRule;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Real MySQL coverage for operating rules and staff slot commands. Scripts come from
 * admin-migration-cli, including V12 and V13. This suite does not call {@code closeSlot}.
 */
class BookingRulesAndSlotsIT {
  private static final String URL = System.getenv("SOUTHLEAF_BOOKING_IT_URL");
  private static final String USER = System.getenv("SOUTHLEAF_BOOKING_IT_USERNAME");
  private static final String PASSWORD = System.getenv("SOUTHLEAF_BOOKING_IT_PASSWORD");
  private static final String INSTANCE_ID = System.getenv("SOUTHLEAF_BOOKING_IT_INSTANCE_ID");
  private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
  private static final OperatingRule RULE = new OperatingRule(24, 30, 48, 120);
  private static final LocalDate DAY = LocalDate.parse("2026-11-03");
  private static final LocalTime TEN = LocalTime.parse("10:00");

  private OperatingRuleService rules;
  private SlotOpeningService slots;

  @BeforeEach
  void setUp() throws Exception {
    if (URL == null || USER == null || PASSWORD == null || INSTANCE_ID == null)
      throw new IllegalStateException("Required booking integration environment is missing");
    BookingItTarget.assertOwned(URL, USER, PASSWORD, INSTANCE_ID);
    byte[] source =
        Files.readAllBytes(
            AdminSchemaMigrations.directory().resolve("V6__admin_identity_booking.sql"));
    byte[] copy =
        BookingRulesAndSlotsIT.class
            .getResourceAsStream("/db/admin-migration/V6__admin_identity_booking.sql")
            .readAllBytes();
    assertThat(copy).isEqualTo(source);
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
      AdminSchemaMigrations.replaceIsolatedSchema(connection);
    }
    JdbcConnectionProvider connections = new JdbcConnectionProvider(new SimpleDataSource());
    var transactions = new JdbcTransactionRunner(connections);
    var clock = Clock.fixed(NOW, ZoneOffset.UTC);
    rules =
        new OperatingRuleService(
            new JdbcOperatingRuleRepository(connections),
            new JdbcCommandLedger(connections),
            transactions,
            clock);
    JdbcBookingAudit audit = new JdbcBookingAudit(connections);
    slots =
        new SlotOpeningService(
            new JdbcOperatingRuleRepository(connections),
            new JdbcScheduleRepository(connections),
            new JdbcSlotGuard(connections),
            new JdbcCommandLedger(connections),
            audit,
            transactions,
            clock);
  }

  @Test
  void savingARuleAppendsAVersionAndRejectsAStaleMatch() {
    var first = rules.save("owner", 0, "k".repeat(16), RULE);
    assertThat(first.version()).isEqualTo(1);
    assertThat(first.replayed()).isFalse();
    assertThatThrownBy(() -> rules.save("owner", 0, "m".repeat(16), RULE))
        .isInstanceOf(BookingException.class)
        .extracting(error -> ((BookingException) error).reason())
        .isEqualTo("RULE_VERSION_CONFLICT");
    OperatingRule next = new OperatingRule(12, 30, 48, 120);
    var second = rules.save("owner", 1, "n".repeat(16), next);
    assertThat(second.version()).isEqualTo(2);
    assertThat(rules.history())
        .extracting(rule -> rule.rule().minAdvanceHours())
        .containsExactly(12, 24);
    assertThat(rules.current().orElseThrow().version()).isEqualTo(2);
  }

  @Test
  void openingRejectsOverlapDayLimitAndAStartOutsideTheWindow() throws Exception {
    rules.save("owner", 0, "k".repeat(16), RULE);
    var opened = slots.open("owner", "a".repeat(16), DAY, TEN);
    assertThat(opened.slot().ruleVersion()).isEqualTo("operating-1");
    assertThat(opened.slot().businessZone()).isEqualTo("Asia/Shanghai");
    assertThat(count("booking_service_slot")).isEqualTo(1);

    assertThatThrownBy(() -> slots.open("owner", "b".repeat(16), DAY, LocalTime.parse("11:00")))
        .extracting(error -> ((BookingException) error).reason())
        .isEqualTo("SLOT_OVERLAP");
    assertThat(count("booking_service_slot")).isEqualTo(1);

    assertThatThrownBy(
            () ->
                slots.open(
                    "owner",
                    "c".repeat(16),
                    LocalDate.parse("2026-11-01"),
                    LocalTime.parse("18:00")))
        .extracting(error -> ((BookingException) error).reason())
        .isEqualTo("SLOT_OUTSIDE_WINDOW");

    for (int index = 0; index < BookingRules.MAX_SLOTS_PER_DAY - 1; index++)
      insertOpenSlot(DAY.atTime(10, index).atZone(SlotOpeningService.BUSINESS_ZONE).toInstant());
    assertThatThrownBy(() -> slots.open("owner", "d".repeat(16), DAY, LocalTime.parse("18:00")))
        .extracting(error -> ((BookingException) error).reason())
        .isEqualTo("SLOT_DAY_FULL");
  }

  @Test
  void aWeekKeepsTheSlotsThatDoNotOverlap() throws Exception {
    rules.save("owner", 0, "k".repeat(16), RULE);
    slots.open("owner", "a".repeat(16), DAY, TEN);
    var items =
        slots.openWeek(
            "owner", "b".repeat(16), List.of(DAY, LocalDate.parse("2026-11-04")), List.of(TEN));
    assertThat(items).extracting(item -> item.ok()).containsExactly(false, true);
    assertThat(items.get(0).reason()).isEqualTo("SLOT_OVERLAP");
    assertThat(count("booking_service_slot")).isEqualTo(2);
  }

  @Test
  void closingRejectsOccupancyAndPendingPreferenceAndRepeatsAClosedSlot() throws Exception {
    rules.save("owner", 0, "k".repeat(16), RULE);
    var slot = slots.open("owner", "a".repeat(16), DAY, TEN).slot();
    occupy(slot.slotId());
    assertThatThrownBy(() -> slots.close("owner", "c".repeat(16), slot.slotId(), 1))
        .extracting(error -> ((BookingException) error).reason())
        .isEqualTo("SLOT_OCCUPIED");
    assertThat(status(slot.slotId())).isEqualTo("OPEN");

    clearOccupancy(slot.slotId());
    prefer(slot.slotId());
    assertThatThrownBy(() -> slots.close("owner", "d".repeat(16), slot.slotId(), 1))
        .extracting(error -> ((BookingException) error).reason())
        .isEqualTo("SLOT_PENDING_REQUEST");
    assertThat(revision(slot.slotId())).isEqualTo(1);

    clearPreference(slot.slotId());
    var closed = slots.close("owner", "e".repeat(16), slot.slotId(), 1);
    assertThat(closed.slot().status()).isEqualTo("CLOSED");
    assertThat(closed.slot().revision()).isEqualTo(2);
    var again = slots.close("owner", "f".repeat(16), slot.slotId(), 1);
    assertThat(again.replayed()).isTrue();
    assertThat(again.slot().revision()).isEqualTo(2);
    assertThat(count("booking_service_slot")).isEqualTo(1);
  }

  @Test
  void twoConcurrentOverlappingOpensLeaveOneSlot() throws Exception {
    rules.save("owner", 0, "k".repeat(16), RULE);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<String> left = pool.submit(() -> openAfter(start, "left-key-0000001"));
      Future<String> right = pool.submit(() -> openAfter(start, "right-key-000000"));
      start.countDown();
      assertThat(List.of(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("OK", "SLOT_OVERLAP");
    } finally {
      pool.shutdownNow();
    }
    assertThat(count("booking_service_slot")).isEqualTo(1);
  }

  private String openAfter(CountDownLatch start, String key) throws Exception {
    start.await(30, TimeUnit.SECONDS);
    try {
      slots.open("owner", key, DAY, TEN);
      return "OK";
    } catch (BookingException rejected) {
      return rejected.reason();
    }
  }

  private void insertOpenSlot(Instant start) throws Exception {
    String id = UUID.randomUUID().toString();
    update(
        "INSERT INTO booking_service_slot"
            + " (id, start_at, end_at, business_zone, display_offset, status, capacity,"
            + " rule_version, revision, created_at, updated_at)"
            + " VALUES (?, ?, ?, 'Asia/Shanghai', '+08:00', 'OPEN', 1, 'seed', 1, ?, ?)",
        id,
        Timestamp.from(start),
        Timestamp.from(start.plusSeconds(60)),
        Timestamp.from(NOW),
        Timestamp.from(NOW));
  }

  private void occupy(String slotId) throws Exception {
    String customer = UUID.randomUUID().toString();
    String reservation = UUID.randomUUID().toString();
    update(
        "INSERT INTO booking_customer"
            + " (id, status, display_name, language, created_at, updated_at, version)"
            + " VALUES (?, 'ACTIVE', 'Integration Customer', 'zh', ?, ?, 1)",
        customer,
        Timestamp.from(NOW),
        Timestamp.from(NOW));
    update(
        "INSERT INTO booking_reservation"
            + " (id, customer_id, package_version_ref, source, status, expires_at, slot_id,"
            + " schedule_revision, confirmed_at, confirmed_by, created_at, updated_at, version)"
            + " VALUES (?, ?, 'pkg-v1', 'STAFF_OFFLINE', 'CONFIRMED', ?, NULL, 0, ?, 'owner', ?, ?, 2)",
        reservation,
        customer,
        Timestamp.from(NOW.plusSeconds(3600)),
        Timestamp.from(NOW),
        Timestamp.from(NOW),
        Timestamp.from(NOW));
    update(
        "INSERT INTO booking_slot_occupancy (slot_id, reservation_id, assigned_at, revision)"
            + " VALUES (?, ?, ?, 1)",
        slotId,
        reservation,
        Timestamp.from(NOW));
  }

  private void prefer(String slotId) throws Exception {
    String customer = UUID.randomUUID().toString();
    String reservation = UUID.randomUUID().toString();
    update(
        "INSERT INTO booking_customer"
            + " (id, status, display_name, language, created_at, updated_at, version)"
            + " VALUES (?, 'ACTIVE', 'Pending Customer', 'zh', ?, ?, 1)",
        customer,
        Timestamp.from(NOW),
        Timestamp.from(NOW));
    update(
        "INSERT INTO booking_reservation"
            + " (id, customer_id, package_version_ref, source, status, expires_at, slot_id,"
            + " schedule_revision, confirmed_at, confirmed_by, created_at, updated_at, version)"
            + " VALUES (?, ?, 'pkg-v1', 'CUSTOMER_ONLINE', 'PENDING_CONFIRMATION', ?, NULL, 0,"
            + " NULL, NULL, ?, ?, 1)",
        reservation,
        customer,
        Timestamp.from(NOW.plusSeconds(3600)),
        Timestamp.from(NOW),
        Timestamp.from(NOW));
    update(
        "INSERT INTO booking_request_preference (reservation_id, slot_id, requested_at)"
            + " VALUES (?, ?, ?)",
        reservation,
        slotId,
        Timestamp.from(NOW));
  }

  private void clearOccupancy(String slotId) throws Exception {
    update("DELETE FROM booking_slot_occupancy WHERE slot_id = ?", slotId);
  }

  private void clearPreference(String slotId) throws Exception {
    update("DELETE FROM booking_request_preference WHERE slot_id = ?", slotId);
  }

  private String status(String slotId) throws Exception {
    return queryString("SELECT status FROM booking_service_slot WHERE id = ?", slotId);
  }

  private int revision(String slotId) throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement statement =
            connection.prepareStatement("SELECT revision FROM booking_service_slot WHERE id = ?")) {
      statement.setString(1, slotId);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) return -1;
        return rows.getInt(1);
      }
    }
  }

  private int count(String table) throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement statement =
            connection.prepareStatement("SELECT COUNT(*) FROM `" + table + "`");
        ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getInt(1);
    }
  }

  private String queryString(String sql, String id) throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getString(1) : "";
      }
    }
  }

  private void update(String sql, Object... parameters) throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++)
        statement.setObject(index + 1, parameters[index]);
      statement.executeUpdate();
    }
  }

  private static final class SimpleDataSource implements DataSource {
    @Override
    public Connection getConnection() throws java.sql.SQLException {
      return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    @Override
    public Connection getConnection(String username, String password) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.io.PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(java.io.PrintWriter out) {}

    @Override
    public void setLoginTimeout(int seconds) {}

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public java.util.logging.Logger getParentLogger() {
      return java.util.logging.Logger.getGlobal();
    }

    @Override
    public <T> T unwrap(Class<T> iface) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
