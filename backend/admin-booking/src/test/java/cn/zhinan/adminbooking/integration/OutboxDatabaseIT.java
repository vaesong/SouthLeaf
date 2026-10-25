package cn.zhinan.adminbooking.integration;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhinan.adminbooking.adapter.jdbc.JdbcConnectionProvider;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcOutboxWorker;
import cn.zhinan.adminbooking.application.OutboxDispatcher;
import cn.zhinan.adminbooking.domain.OutboxRules;
import cn.zhinan.adminbooking.port.BookingPorts.DeliveryOutcome;
import cn.zhinan.adminbooking.port.BookingPorts.NotificationDelivery;
import cn.zhinan.adminbooking.port.BookingPorts.OutboxMessage;
import cn.zhinan.adminbooking.port.BookingPorts.OutboxWorker;
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
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The notification outbox against a real schema.
 *
 * <p>The worker claims a message with a lease, so a worker that dies leaves the message
 * recoverable, and two workers never deliver the same message at the same time. The suite asserts
 * the durable facts: the message status, the attempt counter and the lease owner.
 */
class OutboxDatabaseIT {
  private static final String URL = required("SOUTHLEAF_BOOKING_IT_URL");
  private static final String USER = required("SOUTHLEAF_BOOKING_IT_USERNAME");
  private static final String PASSWORD = required("SOUTHLEAF_BOOKING_IT_PASSWORD");
  private static final String INSTANCE_ID = required("SOUTHLEAF_BOOKING_IT_INSTANCE_ID");
  private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");

  private static cn.zhinan.adminbooking.application.OutboxPolicy policy(int maxAttempts) {
    return new cn.zhinan.adminbooking.application.OutboxPolicy(
        maxAttempts, Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofHours(1), 100);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Required integration environment: " + name);
    return value;
  }

  private static final class SimpleDataSource implements DataSource {
    @Override
    public Connection getConnection() throws SQLException {
      return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return DriverManager.getConnection(URL, username, password);
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

  private JdbcConnectionProvider connections;
  private OutboxWorker worker;

  @BeforeEach
  void setUp() throws Exception {
    BookingItTarget.assertOwned(URL, USER, PASSWORD, INSTANCE_ID);
    dropBookingTables();
    applyMigration();
    connections = new JdbcConnectionProvider(new SimpleDataSource());
    worker = new JdbcOutboxWorker(connections);
  }

  private void applyMigration() throws SQLException, java.io.IOException {
    String sql;
    try (InputStream stream =
        OutboxDatabaseIT.class.getResourceAsStream(
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
      for (String one : statements) {
        statement.execute(one);
      }
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
      for (String table : tables) {
        statement.execute("DROP TABLE IF EXISTS " + table);
      }
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
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT status FROM booking_notification_outbox WHERE id = ?")) {
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

  private String leaseOwnerOf(String messageId) throws SQLException {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT lease_owner FROM booking_notification_outbox WHERE id = ?")) {
      statement.setString(1, messageId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getString(1) : null;
      }
    }
  }

  @Test
  void aDeliveredMessageLeavesThePendingQueue() throws Exception {
    String messageId = "00000000-0000-0000-0000-000000000041";
    enqueue(messageId, NOW);

    NotificationDelivery delivery = message -> DeliveryOutcome.success();
    OutboxDispatcher.DispatchResult result =
        new OutboxDispatcher(worker, delivery, policy(5)).dispatch("worker-1", 10, NOW);

    assertThat(result.delivered()).isEqualTo(1);
    assertThat(statusOf(messageId)).isEqualTo("SENT");
    assertThat(leaseOwnerOf(messageId)).isNull();
  }

  @Test
  void aFailedMessageReturnsToThePendingQueueWithALaterDueDate() throws Exception {
    String messageId = "00000000-0000-0000-0000-000000000042";
    enqueue(messageId, NOW);

    NotificationDelivery delivery = message -> DeliveryOutcome.retryable("PROVIDER_TIMEOUT");
    OutboxDispatcher.DispatchResult result =
        new OutboxDispatcher(worker, delivery, policy(5)).dispatch("worker-1", 10, NOW);

    assertThat(result.retried()).isEqualTo(1);
    assertThat(statusOf(messageId)).isEqualTo("PENDING");
    assertThat(attemptsOf(messageId)).isEqualTo(1);
  }

  @Test
  void aMessageWithAnExhaustedBudgetBecomesDead() throws Exception {
    String messageId = "00000000-0000-0000-0000-000000000043";
    enqueue(messageId, NOW);

    NotificationDelivery delivery = message -> DeliveryOutcome.retryable("PROVIDER_TIMEOUT");
    OutboxDispatcher.DispatchResult result =
        new OutboxDispatcher(worker, delivery, policy(1)).dispatch("worker-1", 10, NOW);

    assertThat(result.dead()).isEqualTo(1);
    assertThat(statusOf(messageId)).isEqualTo("FAILED");
    assertThat(worker.deadLetters(10)).hasSize(1);
  }

  @Test
  void aLeasedMessageIsNotHandedToAnotherWorkerBeforeTheLeaseExpires() throws Exception {
    String messageId = "00000000-0000-0000-0000-000000000044";
    enqueue(messageId, NOW);

    List<OutboxMessage> first = worker.lease("worker-1", 10, NOW, Duration.ofMinutes(2));
    assertThat(first).hasSize(1);
    assertThat(attemptsOf(messageId)).isEqualTo(1);
    assertThat(leaseOwnerOf(messageId)).isEqualTo("worker-1");

    // The lease is still valid, so a second worker sees nothing.
    List<OutboxMessage> second = worker.lease("worker-2", 10, NOW, Duration.ofMinutes(2));
    assertThat(second).isEmpty();
  }

  @Test
  void aMessageOfADeadWorkerIsRecoveredAfterTheLeaseExpires() throws Exception {
    String messageId = "00000000-0000-0000-0000-000000000045";
    enqueue(messageId, NOW);

    // The first worker takes the message and then dies without reporting an outcome.
    List<OutboxMessage> first = worker.lease("worker-1", 10, NOW, Duration.ofMinutes(2));
    assertThat(first).hasSize(1);

    // After the lease expires the message is available again.
    Instant afterLease = NOW.plus(Duration.ofMinutes(3));
    List<OutboxMessage> recovered = worker.lease("worker-2", 10, afterLease, Duration.ofMinutes(2));
    assertThat(recovered).hasSize(1);
    assertThat(recovered.get(0).messageId()).isEqualTo(messageId);
    assertThat(attemptsOf(messageId)).isEqualTo(2);
    assertThat(leaseOwnerOf(messageId)).isEqualTo("worker-2");
  }

  @Test
  void anExpiredWorkerCannotOverwriteTheNewLeaseOwner() throws Exception {
    String messageId = "00000000-0000-0000-0000-000000000049";
    enqueue(messageId, NOW);

    assertThat(worker.lease("worker-old", 1, NOW, Duration.ofMinutes(2))).hasSize(1);
    Instant afterLease = NOW.plus(Duration.ofMinutes(3));
    assertThat(worker.lease("worker-new", 1, afterLease, Duration.ofMinutes(2))).hasSize(1);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> worker.markDelivered(messageId, "worker-old", afterLease))
        .isInstanceOfSatisfying(
            cn.zhinan.adminbooking.domain.BookingException.class,
            failure ->
                assertThat(failure.failure())
                    .isEqualTo(cn.zhinan.adminbooking.domain.BookingFailure.VERSION_CONFLICT));

    assertThat(statusOf(messageId)).isEqualTo("LEASED");
    assertThat(leaseOwnerOf(messageId)).isEqualTo("worker-new");
  }

  @Test
  void aMessageThatIsNotDueYetIsNotLeased() throws Exception {
    String messageId = "00000000-0000-0000-0000-000000000046";
    enqueue(messageId, NOW.plus(Duration.ofHours(1)));

    assertThat(worker.lease("worker-1", 10, NOW, Duration.ofMinutes(2))).isEmpty();
    assertThat(statusOf(messageId)).isEqualTo("PENDING");
    assertThat(attemptsOf(messageId)).isZero();
  }

  @Test
  void theRetryDelayIsStoredAsTheNextDueDate() throws Exception {
    String messageId = "00000000-0000-0000-0000-000000000047";
    enqueue(messageId, NOW);

    NotificationDelivery delivery = message -> DeliveryOutcome.retryable("PROVIDER_TIMEOUT");
    new OutboxDispatcher(worker, delivery, policy(5)).dispatch("worker-1", 10, NOW);

    Instant expected =
        NOW.plus(OutboxRules.retryDelay(1, Duration.ofSeconds(30), Duration.ofHours(1)));
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT due_at FROM booking_notification_outbox WHERE id = ?")) {
      statement.setString(1, messageId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).isTrue();
        assertThat(resultSet.getTimestamp(1).toInstant()).isEqualTo(expected);
      }
    }
  }

  @Test
  void theSameMessageIsNeverDeliveredTwiceByOneWorker() throws Exception {
    String messageId = "00000000-0000-0000-0000-000000000048";
    enqueue(messageId, NOW);

    AtomicInteger deliveries = new AtomicInteger();
    NotificationDelivery delivery =
        message -> {
          deliveries.incrementAndGet();
          return DeliveryOutcome.success();
        };
    OutboxDispatcher dispatcher = new OutboxDispatcher(worker, delivery, policy(5));
    dispatcher.dispatch("worker-1", 10, NOW);
    dispatcher.dispatch("worker-1", 10, NOW);

    assertThat(deliveries.get()).isEqualTo(1);
  }

  @Test
  void aDeadLetterCanBeManuallyRecoveredOnceWithAtomicAuditAndIdempotency() throws Exception {
    String messageId = "00000000-0000-0000-0000-000000000050";
    enqueue(messageId, NOW);
    new OutboxDispatcher(
            worker, message -> DeliveryOutcome.retryable("PROVIDER_TIMEOUT"), policy(1))
        .dispatch("worker-1", 1, NOW);
    assertThat(statusOf(messageId)).isEqualTo("FAILED");
    assertThat(attemptsOf(messageId)).isEqualTo(1);

    var operations =
        new cn.zhinan.adminbooking.application.OutboxOperations(
            worker,
            new cn.zhinan.adminbooking.adapter.jdbc.JdbcCommandLedger(connections),
            new cn.zhinan.adminbooking.adapter.jdbc.JdbcBookingAudit(connections),
            new cn.zhinan.adminbooking.adapter.jdbc.JdbcTransactionRunner(connections),
            java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    var recovered =
        operations.retryDeadLetter(
            "operator-1", "manual-recovery-key-0001", messageId, "PROVIDER_FIXED");
    var replay =
        operations.retryDeadLetter(
            "operator-1", "manual-recovery-key-0001", messageId, "PROVIDER_FIXED");

    assertThat(recovered.replayed()).isFalse();
    assertThat(replay.commandId()).isEqualTo(recovered.commandId());
    assertThat(replay.replayed()).isTrue();
    assertThat(statusOf(messageId)).isEqualTo("PENDING");
    assertThat(attemptsOf(messageId)).isEqualTo(1);
    assertThat(worker.deadLetters(10)).isEmpty();
    assertThat(worker.pendingCount(NOW)).isEqualTo(1);
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement audit =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM booking_audit WHERE object_id = ? AND action = 'MANUAL_RETRY'");
        PreparedStatement command =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM booking_command WHERE actor_id = ?"
                    + " AND operation = 'OUTBOX_MANUAL_RECOVERY'")) {
      audit.setString(1, messageId);
      command.setString(1, "operator-1");
      try (ResultSet result = audit.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getInt(1)).isEqualTo(1);
      }
      try (ResultSet result = command.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getInt(1)).isEqualTo(1);
      }
    }
  }
}
