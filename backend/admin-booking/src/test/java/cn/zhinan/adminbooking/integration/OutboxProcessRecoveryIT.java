package cn.zhinan.adminbooking.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A worker process that exits while holding a lease must leave the message recoverable by a later
 * process. The two JVMs share only the isolated schema.
 */
class OutboxProcessRecoveryIT {
  private static final String URL = required("SOUTHLEAF_BOOKING_IT_URL");
  private static final String USER = required("SOUTHLEAF_BOOKING_IT_USERNAME");
  private static final String PASSWORD = required("SOUTHLEAF_BOOKING_IT_PASSWORD");
  private static final String INSTANCE_ID = required("SOUTHLEAF_BOOKING_IT_INSTANCE_ID");
  private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
  private static final String MESSAGE_ID = "00000000-0000-0000-0000-000000000050";

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Required integration environment: " + name);
    return value;
  }

  @BeforeEach
  void setUp() throws Exception {
    BookingItTarget.assertOwned(URL, USER, PASSWORD, INSTANCE_ID);
    dropBookingTables();
    applyMigration();
  }

  @Test
  void aProcessThatExitsWhileHoldingTheLeaseIsContinuedByTheNextProcess() throws Exception {
    enqueue(MESSAGE_ID, NOW);

    assertThat(run("lease-and-exit", NOW)).isEqualTo("OUTBOX_LEASED");
    assertThat(statusOf(MESSAGE_ID)).isEqualTo("LEASED");
    assertThat(leaseOwnerOf(MESSAGE_ID)).isEqualTo("worker-a");
    assertThat(attemptsOf(MESSAGE_ID)).isEqualTo(1);

    assertThat(run("recover-and-deliver", NOW.plus(Duration.ofMinutes(3))))
        .isEqualTo("OUTBOX_RECOVERED");
    assertThat(statusOf(MESSAGE_ID)).isEqualTo("SENT");
    assertThat(leaseOwnerOf(MESSAGE_ID)).isNull();
    assertThat(attemptsOf(MESSAGE_ID)).isEqualTo(2);
  }

  private static String run(String action, Instant now) throws Exception {
    String java = ProcessHandle.current().info().command().orElseThrow();
    ProcessBuilder builder =
        new ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            OutboxLeaseProcess.class.getName(),
            action,
            MESSAGE_ID,
            now.toString());
    builder.redirectErrorStream(true);
    builder.environment().remove("JAVA_TOOL_OPTIONS");
    builder.environment().remove("JDK_JAVA_OPTIONS");
    builder.environment().remove("_JAVA_OPTIONS");
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).isZero();
    assertThat(output.strip())
        .isEqualTo(action.equals("lease-and-exit") ? "OUTBOX_LEASED" : "OUTBOX_RECOVERED");
    return output.strip();
  }

  private void applyMigration() throws Exception {
    String sql;
    try (InputStream stream =
        OutboxProcessRecoveryIT.class.getResourceAsStream(
            "/db/admin-migration/V6__admin_identity_booking.sql")) {
      if (stream == null) throw new IllegalStateException("The V6 admin migration is missing.");
      sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String line : sql.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;
      current.append(line).append('\n');
      if (trimmed.endsWith(";")) {
        statements.add(current.toString().trim());
        current.setLength(0);
      }
    }
    if (!current.toString().isBlank()) statements.add(current.toString().trim());
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        Statement statement = connection.createStatement()) {
      for (String one : statements) statement.execute(one);
    }
  }

  private void dropBookingTables() throws SQLException {
    List<String> tables =
        List.of(
            "booking_audit",
            "booking_notification_outbox",
            "booking_command",
            "booking_order_revision",
            "booking_order_snapshot",
            "booking_order",
            "booking_slot_assignment_history",
            "booking_slot_occupancy",
            "booking_reservation",
            "booking_service_slot",
            "booking_customer_link_request",
            "booking_customer_relationship",
            "booking_contact_point",
            "booking_external_identity",
            "booking_customer");
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute("SET FOREIGN_KEY_CHECKS = 0");
      for (String table : tables) statement.execute("DROP TABLE IF EXISTS " + table);
      statement.execute("SET FOREIGN_KEY_CHECKS = 1");
    }
  }

  private void enqueue(String messageId, Instant dueAt) throws SQLException {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO booking_notification_outbox (id, intent, recipient_ref, object_ref,"
                    + " due_at, status, attempts, created_at, updated_at)"
                    + " VALUES (?, 'RESERVATION_CONFIRMED', 'customer-1', 'reservation-1', ?,"
                    + " 'PENDING', 0, ?, ?)")) {
      statement.setString(1, messageId);
      statement.setObject(2, java.sql.Timestamp.from(dueAt));
      statement.setObject(3, java.sql.Timestamp.from(NOW));
      statement.setObject(4, java.sql.Timestamp.from(NOW));
      statement.executeUpdate();
    }
  }

  private String statusOf(String messageId) throws SQLException {
    return stringColumn("status", messageId);
  }

  private String leaseOwnerOf(String messageId) throws SQLException {
    return stringColumn("lease_owner", messageId);
  }

  private String stringColumn(String column, String messageId) throws SQLException {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT " + column + " FROM booking_notification_outbox WHERE id = ?")) {
      statement.setString(1, messageId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getString(1) : null;
      }
    }
  }

  private int attemptsOf(String messageId) throws SQLException {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT attempts FROM booking_notification_outbox WHERE id = ?")) {
      statement.setString(1, messageId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getInt(1) : 0;
      }
    }
  }
}
