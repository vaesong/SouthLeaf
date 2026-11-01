package cn.zhinan.uniapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.uniapp.booking.PublicBookingException;
import cn.zhinan.uniapp.booking.adapter.jdbc.JdbcPublicBookingRepository;
import cn.zhinan.uniapp.booking.application.PublicBookingService;
import cn.zhinan.uniapp.booking.config.PublicBookingProperties;
import cn.zhinan.uniapp.booking.identity.TestSinkSubjectExchange;
import cn.zhinan.uniapp.booking.session.PublicSessionTokens;
import cn.zhinan.uniapp.common.GlobalExceptionHandler;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Public intake against the isolated booking schema after the real admin migrations, including the
 * operating-rule tables. No H2.
 */
class PublicBookingOperatingRuleIT {
  private static final String URL = System.getenv("SOUTHLEAF_BOOKING_IT_URL");
  private static final String USER = System.getenv("SOUTHLEAF_BOOKING_IT_USERNAME");
  private static final String PASSWORD = System.getenv("SOUTHLEAF_BOOKING_IT_PASSWORD");
  private static final String INSTANCE = System.getenv("SOUTHLEAF_BOOKING_IT_INSTANCE_ID");
  private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
  private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String REF = "pkgv-synthetic-1";
  private static final String INSIDE = "11111111-2222-4333-8444-555555555555";
  private static final String TOO_SOON = "11111111-2222-4333-8444-555555555556";

  private PublicBookingService service;

  @BeforeEach
  void setUp() throws Exception {
    if (URL == null || USER == null || PASSWORD == null || INSTANCE == null)
      throw new IllegalStateException("Required booking integration environment is missing");
    if (!URL.matches(
            "^jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):\\d+/southleaf_booking_it(?:\\?.*)?$")
        || URL.matches("^jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):3306/.*"))
      throw new IllegalStateException("Public booking IT refuses a non-isolated URL");
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
      try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT instance_id FROM southleaf_booking_it_owner WHERE owner_id = 1");
          ResultSet rows = statement.executeQuery()) {
        if (!rows.next() || !INSTANCE.equals(rows.getString(1)))
          throw new IllegalStateException("Booking IT ownership marker does not match");
      }
      AdminSchemaMigrations.replaceIsolatedSchema(connection);
    }
    DataSource source = new DriverManagerDataSource(URL, USER, PASSWORD);
    service =
        new PublicBookingService(
            properties(),
            new JdbcPublicBookingRepository(source),
            new TestSinkSubjectExchange(),
            new PublicSessionTokens(SECRET, Duration.ofMinutes(120)),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void anUnconfiguredRuleRefusesTheListAndTheRequest() throws Exception {
    var session = service.startSession("test-alice-0001");
    PublicBookingException list =
        org.assertj.core.api.Assertions.catchThrowableOfType(
            () -> service.openSlots("2026-11-01", "2026-11-20"), PublicBookingException.class);
    assertThat(list.getErrorCode()).isEqualTo(PublicBookingException.ErrorCode.RULES_UNCONFIGURED);
    assertThat(new GlobalExceptionHandler().bookingFailure(list).getBody().code())
        .isEqualTo("BOOKING_RULES_UNCONFIGURED");
    assertThatThrownBy(() -> service.createRequest(session.token(), "create-key-01", INSIDE, REF))
        .isInstanceOf(PublicBookingException.class)
        .extracting(error -> ((PublicBookingException) error).getErrorCode())
        .isEqualTo(PublicBookingException.ErrorCode.RULES_UNCONFIGURED);
    assertThat(count("booking_reservation")).isZero();
  }

  @Test
  void aConfiguredRuleFiltersTheWindowAndAcceptsASlotInsideIt() throws Exception {
    saveRule();
    insertSlot(TOO_SOON, Instant.parse("2026-11-01T04:00:00Z"));
    insertSlot(INSIDE, Instant.parse("2026-11-03T02:00:00Z"));
    var page = service.openSlots("2026-11-01", "2026-11-20");
    assertThat(page.items()).extracting(slot -> slot.slotId()).containsExactly(INSIDE);
    var session = service.startSession("test-alice-0001");
    assertThatThrownBy(() -> service.createRequest(session.token(), "create-key-02", TOO_SOON, REF))
        .extracting(error -> ((PublicBookingException) error).getErrorCode())
        .isEqualTo(PublicBookingException.ErrorCode.SLOT_UNAVAILABLE);
    var created = service.createRequest(session.token(), "create-key-03", INSIDE, REF);
    assertThat(created.requestedSlot().slotId()).isEqualTo(INSIDE);
    assertThat(created.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(48)).toString());
  }

  private static PublicBookingProperties properties() {
    Map<String, String> env = new HashMap<>();
    env.put("SOUTHLEAF_PUBLIC_BOOKING_MODE", "enabled");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_DB_URL", URL);
    env.put("SOUTHLEAF_PUBLIC_BOOKING_DB_USERNAME", "southleaf_public_booking_writer");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_DB_PASSWORD", "fixture-password");
    env.put(
        "SOUTHLEAF_PUBLIC_BOOKING_DB_EXPECTED_USER", "southleaf_public_booking_writer@localhost");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_DATA_MODE", "SYNTHETIC");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_IDENTITY_SINK", "TEST");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_APP_SCOPE", "southleaf-mp");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_SESSION_SECRET", SECRET);
    env.put("SOUTHLEAF_PUBLIC_BOOKING_SESSION_MINUTES", "120");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_REQUEST_HOURS", "48");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_BUSINESS_ZONE", "Asia/Shanghai");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_PACKAGE_REFS", REF + "=虚构演示套餐");
    return PublicBookingProperties.load(env, "southleaf_public");
  }

  private void saveRule() throws Exception {
    update(
        "INSERT INTO admin_booking_operating_rule (singleton_id, current_version) VALUES (1, 1)");
    update(
        "INSERT INTO admin_booking_operating_rule_version"
            + " (version, payload_json, updated_by, updated_at) VALUES (1, ?, 'it', ?)",
        "{\"minAdvanceHours\":24,\"maxAdvanceDays\":30,\"pendingExpiryHours\":48,\"slotDurationMinutes\":120}",
        Timestamp.from(NOW));
  }

  private void insertSlot(String id, Instant start) throws Exception {
    update(
        "INSERT INTO booking_service_slot"
            + " (id, start_at, end_at, business_zone, display_offset, status, capacity,"
            + " rule_version, revision, created_at, updated_at)"
            + " VALUES (?, ?, ?, 'Asia/Shanghai', '+08:00', 'OPEN', 1, 'operating-1', 1, ?, ?)",
        id,
        Timestamp.from(start),
        Timestamp.from(start.plusSeconds(7200)),
        Timestamp.from(NOW),
        Timestamp.from(NOW));
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

  private void update(String sql, Object... parameters) throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++)
        statement.setObject(index + 1, parameters[index]);
      statement.executeUpdate();
    }
  }
}
