package cn.zhinan.adminbooking.integration;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhinan.adminbooking.adapter.jdbc.JdbcCommandLedger;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcConnectionProvider;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcOrderRepository;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcReservationRepository;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcScheduleRepository;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcTransactionRunner;
import cn.zhinan.adminbooking.application.BookingApplication;
import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.BookingFailure;
import cn.zhinan.adminbooking.port.BookingPorts.PackageCatalog;
import cn.zhinan.adminbooking.port.BookingPorts.ReservationRepository;
import cn.zhinan.adminbooking.port.BookingPorts.ScheduleRepository;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The real concurrency evidence for the booking slice.
 *
 * <p>Every case opens two independent connections and two independent transactions and releases
 * them from a barrier, so both sides really meet at the contended row. The assertions are about the
 * final database facts, not about a status code: exactly one order, exactly one occupancy, exactly
 * one winner.
 *
 * <p>No sleep is used to create the interleaving. The barrier decides when both transactions start
 * their contended statement, and the unique keys decide the outcome.
 */
class BookingConcurrencyIT {
  private static final String URL = required("SOUTHLEAF_BOOKING_IT_URL");
  private static final String USER = required("SOUTHLEAF_BOOKING_IT_USERNAME");
  private static final String PASSWORD = required("SOUTHLEAF_BOOKING_IT_PASSWORD");
  private static final String INSTANCE_ID = required("SOUTHLEAF_BOOKING_IT_INSTANCE_ID");
  private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");

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

  /** A package catalog that always knows the same package version. */
  private static final class FixedCatalog implements PackageCatalog {
    @Override
    public Optional<cn.zhinan.adminbooking.domain.BookingModels.PackageFact> findPackageVersion(
        String packageVersionRef) {
      return Optional.of(
          new cn.zhinan.adminbooking.domain.BookingModels.PackageFact(
              packageVersionRef, "体验套餐", "CNY", 10000, "price-v1"));
    }
  }

  /** The audit and outbox sink, deliberately empty so the cases only observe the booking facts. */
  private static final class NoopAudit
      implements cn.zhinan.adminbooking.port.BookingPorts.BookingAudit,
          cn.zhinan.adminbooking.port.BookingPorts.NotificationOutbox {
    @Override
    public void record(
        String actorId,
        String commandId,
        String objectType,
        String objectId,
        String action,
        String reasonCode,
        Instant now) {}

    @Override
    public void enqueue(
        String intent, String recipientRef, String objectRef, Instant dueAt, Instant now) {}
  }

  @BeforeEach
  void setUp() throws Exception {
    BookingItTarget.assertOwned(URL, USER, PASSWORD, INSTANCE_ID);
    dropBookingTables();
    applyMigration();
  }

  private void applyMigration() throws SQLException, java.io.IOException {
    String sql;
    try (InputStream stream =
        BookingConcurrencyIT.class.getResourceAsStream(
            "/db/admin-migration/V6__admin_identity_booking.sql")) {
      if (stream == null) throw new IllegalStateException("The V6 admin migration is missing.");
      sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    List<String> statements = new ArrayList<>();
    // The comments are removed first: a comment may contain a semicolon, and splitting before the
    // comments are gone would cut a statement in half.
    for (String raw : stripComments(sql).split(";")) {
      String statement = raw.trim();
      if (!statement.isEmpty()) statements.add(statement);
    }
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        Statement statement = connection.createStatement()) {
      for (String one : statements) {
        statement.execute(one);
      }
    }
  }

  private static String stripComments(String sql) {
    StringBuilder kept = new StringBuilder();
    for (String line : sql.split("\n")) {
      String trimmed = line.trim();
      if (trimmed.startsWith("--")) continue;
      kept.append(line).append('\n');
    }
    return kept.toString();
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

  /**
   * Builds a booking application on its own connection, so two instances never share a connection.
   */
  private BookingApplication application() {
    JdbcConnectionProvider connections = new JdbcConnectionProvider(new SimpleDataSource());
    ScheduleRepository schedule = new JdbcScheduleRepository(connections);
    ReservationRepository reservations = new JdbcReservationRepository(connections);
    JdbcOrderRepository orders = new JdbcOrderRepository(connections);
    return new BookingApplication(
        new cn.zhinan.adminbooking.port.BookingPorts.CustomerRepository() {
          @Override
          public Optional<cn.zhinan.adminbooking.domain.BookingModels.Customer> find(
              String customerId) {
            return Optional.empty();
          }

          @Override
          public cn.zhinan.adminbooking.domain.BookingModels.Customer create(
              String customerId, String displayName, String language, Instant now) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Optional<cn.zhinan.adminbooking.domain.BookingModels.ExternalIdentity>
              findExternalIdentity(String provider, String appScope, String subjectFingerprint) {
            return Optional.empty();
          }

          @Override
          public cn.zhinan.adminbooking.domain.BookingModels.ExternalIdentity
              createExternalIdentity(
                  String customerId,
                  String provider,
                  String appScope,
                  String subjectFingerprint,
                  Instant verifiedAt,
                  Instant now) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Optional<cn.zhinan.adminbooking.domain.BookingModels.ContactPoint>
              findContactPoint(String contactPointId) {
            return Optional.empty();
          }

          @Override
          public cn.zhinan.adminbooking.domain.BookingModels.ContactPoint createContactPoint(
              String contactPointId,
              String customerId,
              String type,
              byte[] encryptedValue,
              String maskedValue,
              String verificationStatus,
              Instant now) {
            throw new UnsupportedOperationException();
          }

          @Override
          public List<cn.zhinan.adminbooking.domain.BookingModels.ContactPoint> listContactPoints(
              String customerId) {
            return List.of();
          }

          @Override
          public Optional<cn.zhinan.adminbooking.domain.BookingModels.CustomerRelationship>
              findRelationship(String relationshipId) {
            return Optional.empty();
          }

          @Override
          public Optional<cn.zhinan.adminbooking.domain.BookingModels.CustomerRelationship>
              findRelationship(String customerId, String relatedCustomerId) {
            return Optional.empty();
          }

          @Override
          public cn.zhinan.adminbooking.domain.BookingModels.CustomerRelationship
              approveRelationship(
                  String relationshipId,
                  String customerId,
                  String relatedCustomerId,
                  String relationType,
                  String purpose,
                  Instant validFrom,
                  Instant validUntil,
                  String actorId,
                  Instant now) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Optional<cn.zhinan.adminbooking.domain.BookingModels.CustomerLinkRequest>
              findLinkRequest(String linkRequestId) {
            return Optional.empty();
          }

          @Override
          public cn.zhinan.adminbooking.domain.BookingModels.CustomerLinkRequest createLinkRequest(
              String linkRequestId,
              String requesterCustomerId,
              String candidateRef,
              String method,
              Instant expiresAt,
              Instant now) {
            throw new UnsupportedOperationException();
          }

          @Override
          public cn.zhinan.adminbooking.domain.BookingModels.CustomerLinkDecision decideLinkRequest(
              String linkRequestId, boolean approve, String actorId, Instant now) {
            throw new UnsupportedOperationException();
          }

          @Override
          public List<cn.zhinan.adminbooking.domain.BookingModels.CustomerRelationship>
              listRelationships(String customerId) {
            return List.of();
          }
        },
        schedule,
        reservations,
        orders,
        new FixedCatalog(),
        new JdbcCommandLedger(connections),
        new cn.zhinan.adminbooking.adapter.jdbc.JdbcBookingAudit(connections),
        new cn.zhinan.adminbooking.adapter.jdbc.JdbcBookingAudit(connections),
        new JdbcTransactionRunner(connections),
        java.time.Clock.systemUTC());
  }

  private void insertCustomer(String customerId) throws SQLException {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO booking_customer (id, status, display_name, language, created_at,"
                    + " updated_at, version) VALUES (?, 'ACTIVE', 'test', 'zh', ?, ?, 1)")) {
      statement.setString(1, customerId);
      statement.setObject(2, java.sql.Timestamp.from(NOW));
      statement.setObject(3, java.sql.Timestamp.from(NOW));
      statement.executeUpdate();
    }
  }

  private void insertReservation(String reservationId, String customerId, Instant expiresAt)
      throws SQLException {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO booking_reservation (id, customer_id, package_version_ref, source,"
                    + " status, expires_at, created_at, updated_at, version)"
                    + " VALUES (?, ?, 'pkg-v1', 'CUSTOMER_ONLINE', 'PENDING_CONFIRMATION', ?, ?, ?,"
                    + " 1)")) {
      statement.setString(1, reservationId);
      statement.setString(2, customerId);
      statement.setObject(3, java.sql.Timestamp.from(expiresAt));
      statement.setObject(4, java.sql.Timestamp.from(NOW));
      statement.setObject(5, java.sql.Timestamp.from(NOW));
      statement.executeUpdate();
    }
  }

  private void insertSlot(String slotId) throws SQLException {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO booking_service_slot (id, start_at, end_at, business_zone,"
                    + " display_offset, status, capacity, rule_version, created_at, updated_at,"
                    + " revision) VALUES (?, ?, ?, 'Asia/Taipei', '+08:00', 'OPEN', 1, 'rule-v1',"
                    + " ?, ?, 1)")) {
      statement.setString(1, slotId);
      statement.setObject(2, java.sql.Timestamp.from(NOW.plusSeconds(3600)));
      statement.setObject(3, java.sql.Timestamp.from(NOW.plusSeconds(7200)));
      statement.setObject(4, java.sql.Timestamp.from(NOW));
      statement.setObject(5, java.sql.Timestamp.from(NOW));
      statement.executeUpdate();
    }
  }

  private int count(String table) throws SQLException {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      resultSet.next();
      return resultSet.getInt(1);
    }
  }

  private String reservationStatus(String reservationId) throws SQLException {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement statement =
            connection.prepareStatement("SELECT status FROM booking_reservation WHERE id = ?")) {
      statement.setString(1, reservationId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getString(1) : null;
      }
    }
  }

  private String occupantOf(String slotId) throws SQLException {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT reservation_id FROM booking_slot_occupancy WHERE slot_id = ?")) {
      statement.setString(1, slotId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getString(1) : null;
      }
    }
  }

  @Test
  void twoConfirmationsForTheSameSlotProduceExactlyOneOrder() throws Exception {
    String slotId = "00000000-0000-0000-0000-000000000001";
    insertSlot(slotId);
    insertCustomer("00000000-0000-0000-0000-000000000011");
    insertCustomer("00000000-0000-0000-0000-000000000012");
    insertReservation(
        "00000000-0000-0000-0000-000000000021",
        "00000000-0000-0000-0000-000000000011",
        NOW.plusSeconds(172800));
    insertReservation(
        "00000000-0000-0000-0000-000000000022",
        "00000000-0000-0000-0000-000000000012",
        NOW.plusSeconds(172800));

    AtomicInteger winners = new AtomicInteger();
    AtomicInteger losers = new AtomicInteger();
    List<String> winnerReservations = java.util.Collections.synchronizedList(new ArrayList<>());

    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> tasks = new ArrayList<>();
      for (int index = 1; index <= 2; index++) {
        final String reservationId =
            index == 1
                ? "00000000-0000-0000-0000-000000000021"
                : "00000000-0000-0000-0000-000000000022";
        final String key = "confirm-key-00000000000000000" + index;
        tasks.add(
            pool.submit(
                () -> {
                  BookingApplication application = application();
                  barrier.await(20, TimeUnit.SECONDS);
                  try {
                    application.confirmReservation("actor-1", reservationId, slotId, 1, key);
                    winners.incrementAndGet();
                    winnerReservations.add(reservationId);
                  } catch (BookingException failure) {
                    if (failure.failure() == BookingFailure.SLOT_UNAVAILABLE
                        || failure.failure() == BookingFailure.CONFLICT) {
                      losers.incrementAndGet();
                    } else {
                      throw failure;
                    }
                  }
                  return null;
                }));
      }
      for (Future<?> task : tasks) {
        task.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(winners.get()).isEqualTo(1);
    assertThat(losers.get()).isEqualTo(1);
    assertThat(count("booking_order")).isEqualTo(1);
    assertThat(count("booking_order_snapshot")).isEqualTo(1);
    assertThat(count("booking_slot_occupancy")).isEqualTo(1);
    assertThat(occupantOf(slotId)).isEqualTo(winnerReservations.get(0));
  }

  @Test
  void twoConfirmationsWithTheSameIdempotencyKeyProduceExactlyOneOrder() throws Exception {
    String slotId = "00000000-0000-0000-0000-000000000003";
    insertSlot(slotId);
    insertCustomer("00000000-0000-0000-0000-000000000013");
    insertReservation(
        "00000000-0000-0000-0000-000000000023",
        "00000000-0000-0000-0000-000000000013",
        NOW.plusSeconds(172800));

    AtomicInteger succeeded = new AtomicInteger();
    List<String> orderIds = java.util.Collections.synchronizedList(new ArrayList<>());

    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> tasks = new ArrayList<>();
      for (int index = 0; index < 2; index++) {
        tasks.add(
            pool.submit(
                () -> {
                  BookingApplication application = application();
                  barrier.await(20, TimeUnit.SECONDS);
                  String result =
                      application
                          .confirmReservation(
                              "actor-1",
                              "00000000-0000-0000-0000-000000000023",
                              slotId,
                              1,
                              "confirm-key-same-000000000")
                          .orderId();
                  succeeded.incrementAndGet();
                  orderIds.add(result);
                  return null;
                }));
      }
      for (Future<?> task : tasks) {
        task.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(succeeded.get()).isEqualTo(2);
    assertThat(orderIds.get(0)).isEqualTo(orderIds.get(1));
    assertThat(count("booking_order")).isEqualTo(1);
    assertThat(count("booking_order_snapshot")).isEqualTo(1);
    assertThat(count("booking_slot_occupancy")).isEqualTo(1);
    assertThat(count("booking_audit")).isEqualTo(1);
    assertThat(count("booking_notification_outbox")).isEqualTo(1);
  }

  /**
   * D-703 §8.4: a cancel and a reschedule of the same reservation overlap. Both present the same
   * expected revision, so the row version decides: exactly one command wins, the other is refused,
   * and the order gains at most one revision.
   */
  @Test
  void cancellingWhileReschedulingLeavesExactlyOneTerminalAnswer() throws Exception {
    String originalSlot = "00000000-0000-0000-0000-000000000101";
    String targetSlot = "00000000-0000-0000-0000-000000000102";
    insertSlot(originalSlot);
    insertSlot(targetSlot);
    String customer = "00000000-0000-0000-0000-000000000111";
    String reservation = "00000000-0000-0000-0000-000000000121";
    insertCustomer(customer);
    insertReservation(reservation, customer, NOW.plusSeconds(172800));
    application()
        .confirmReservation("actor-1", reservation, originalSlot, 1, "confirm-key-race-0000001");

    int revisionsBefore = count("booking_order_revision");
    AtomicInteger cancelled = new AtomicInteger();
    AtomicInteger rescheduled = new AtomicInteger();
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> cancel =
          pool.submit(
              () -> {
                BookingApplication application = application();
                barrier.await(20, TimeUnit.SECONDS);
                try {
                  application.cancelReservation(
                      "actor-1", reservation, 2, "CUSTOMER_REQUEST", "cancel-key-race-00000001");
                  cancelled.incrementAndGet();
                } catch (BookingException refused) {
                  // The competing writer already moved the row.
                }
                return null;
              });
      Future<?> reschedule =
          pool.submit(
              () -> {
                BookingApplication application = application();
                barrier.await(20, TimeUnit.SECONDS);
                try {
                  application.rescheduleReservation(
                      "actor-1",
                      reservation,
                      targetSlot,
                      2,
                      "OPERATOR_RESCHEDULE",
                      "resched-key-race-0000001");
                  rescheduled.incrementAndGet();
                } catch (BookingException refused) {
                  // The competing writer already closed the row.
                }
                return null;
              });
      cancel.get(60, TimeUnit.SECONDS);
      reschedule.get(60, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    assertThat(cancelled.get() + rescheduled.get()).isEqualTo(1);
    assertThat(count("booking_order_revision")).isEqualTo(revisionsBefore + 1);
    assertThat(count("booking_order")).isEqualTo(1);
    if (cancelled.get() == 1) {
      assertThat(reservationStatus(reservation)).isEqualTo("CANCELLED");
      assertThat(count("booking_slot_occupancy")).isZero();
      assertThat(occupantOf(targetSlot)).isNull();
    } else {
      assertThat(reservationStatus(reservation)).isEqualTo("CONFIRMED");
      assertThat(occupantOf(targetSlot)).isEqualTo(reservation);
      assertThat(occupantOf(originalSlot)).isNull();
    }
  }

  /**
   * D-703 §8.4: a request that reached its deadline while it waited must never become an order. The
   * expiry check runs inside the transaction, before any occupancy is written.
   */
  @Test
  void aRequestThatExpiredWhileItWaitedNeverProducesAnOrder() throws Exception {
    String slot = "00000000-0000-0000-0000-000000000201";
    insertSlot(slot);
    String customer = "00000000-0000-0000-0000-000000000211";
    String reservation = "00000000-0000-0000-0000-000000000221";
    insertCustomer(customer);
    insertReservation(reservation, customer, NOW.minusSeconds(1));

    BookingFailure refusal = null;
    try {
      application()
          .confirmReservation("actor-1", reservation, slot, 1, "confirm-key-expired-0000001");
    } catch (BookingException refused) {
      refusal = refused.failure();
    }
    assertThat(refusal).isEqualTo(BookingFailure.RESERVATION_EXPIRED);
    assertThat(count("booking_order")).isZero();
    assertThat(count("booking_order_snapshot")).isZero();
    assertThat(count("booking_slot_occupancy")).isZero();
    assertThat(reservationStatus(reservation)).isEqualTo("PENDING_CONFIRMATION");
  }

  @Test
  void twoWorkersLeaseTheSameOutboxMessageOnlyOnce() throws Exception {
    BookingApplication application = application();
    insertCustomer("00000000-0000-0000-0000-000000000014");
    insertSlot("00000000-0000-0000-0000-000000000004");
    application.createSlot(
        "actor-1",
        NOW.plusSeconds(3600),
        NOW.plusSeconds(7200),
        "Asia/Taipei",
        "+08:00",
        "rule-v1",
        1);
    application.createReservation(
        "actor-1",
        "00000000-0000-0000-0000-000000000014",
        "pkg-v1",
        "CUSTOMER_ONLINE",
        "create-key-0000000000000004",
        java.time.Duration.ofHours(48));

    // One queued message, two workers racing for the lease.
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO booking_notification_outbox (id, intent, recipient_ref, object_ref,"
                    + " due_at, status, attempts, created_at, updated_at)"
                    + " VALUES ('00000000-0000-0000-0000-000000000031', 'RESERVATION_PENDING',"
                    + " 'customer-1', 'reservation-1', ?, 'PENDING', 0, ?, ?)")) {
      statement.setObject(1, java.sql.Timestamp.from(NOW));
      statement.setObject(2, java.sql.Timestamp.from(NOW));
      statement.setObject(3, java.sql.Timestamp.from(NOW));
      statement.executeUpdate();
    }

    JdbcConnectionProvider connections = new JdbcConnectionProvider(new SimpleDataSource());
    cn.zhinan.adminbooking.adapter.jdbc.JdbcOutboxWorker worker =
        new cn.zhinan.adminbooking.adapter.jdbc.JdbcOutboxWorker(connections);

    AtomicInteger leases = new AtomicInteger();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> tasks = new ArrayList<>();
      for (int index = 0; index < 2; index++) {
        final String owner = "worker-" + index;
        tasks.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await(20, TimeUnit.SECONDS);
                  List<cn.zhinan.adminbooking.port.BookingPorts.OutboxMessage> leased =
                      worker.lease(owner, 10, NOW, java.time.Duration.ofMinutes(2));
                  leases.addAndGet(leased.size());
                  return null;
                }));
      }
      assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      for (Future<?> task : tasks) {
        task.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(leases.get()).isEqualTo(1);
  }
}
