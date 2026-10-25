package cn.zhinan.adminbooking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.adminbooking.application.BookingApplication;
import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.BookingFailure;
import cn.zhinan.adminbooking.domain.BookingModels.ConfirmationResult;
import cn.zhinan.adminbooking.domain.BookingModels.Order;
import cn.zhinan.adminbooking.domain.BookingModels.OrderSnapshot;
import cn.zhinan.adminbooking.domain.BookingModels.PackageFact;
import cn.zhinan.adminbooking.domain.BookingModels.Reservation;
import cn.zhinan.adminbooking.domain.BookingModels.ReservationStatus;
import cn.zhinan.adminbooking.domain.BookingModels.ServiceSlot;
import cn.zhinan.adminbooking.port.BookingPorts.BookingAudit;
import cn.zhinan.adminbooking.port.BookingPorts.CommandLedger;
import cn.zhinan.adminbooking.port.BookingPorts.CustomerRepository;
import cn.zhinan.adminbooking.port.BookingPorts.NotificationOutbox;
import cn.zhinan.adminbooking.port.BookingPorts.OrderRepository;
import cn.zhinan.adminbooking.port.BookingPorts.PackageCatalog;
import cn.zhinan.adminbooking.port.BookingPorts.ReservationRepository;
import cn.zhinan.adminbooking.port.BookingPorts.ScheduleRepository;
import cn.zhinan.adminbooking.port.BookingPorts.StoredCommand;
import cn.zhinan.adminbooking.port.BookingPorts.TransactionRunner;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The booking slice against a real MySQL schema, exercised through the production JDBC adapters. No
 * H2 and no skipped fallback: the suite refuses to run unless an explicitly named isolated schema
 * is provided, and it applies the V6 admin migration itself.
 *
 * <p>What this suite proves that the unit tests cannot:
 *
 * <ul>
 *   <li>The exclusive slot hold really comes from the unique key, not from a prior read.
 *   <li>A confirmation really produces one order and one immutable snapshot, and the snapshot can
 *       be attached to its order (the parent row must be written first).
 *   <li>One reservation can never produce a second order.
 *   <li>A replayed command never creates a second business fact.
 *   <li>A rolled back transaction leaves no half written state.
 * </ul>
 */
class BookingDatabaseIT {
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

  /** A plain JDBC data source; the module must not depend on a framework for its own storage. */
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

  private cn.zhinan.adminbooking.adapter.jdbc.JdbcConnectionProvider connections;
  private cn.zhinan.adminbooking.adapter.jdbc.JdbcCustomerRepository customerRepository;
  private ScheduleRepository schedule;
  private ReservationRepository reservations;
  private OrderRepository orders;
  private CommandLedger commands;
  private BookingApplication application;

  @BeforeEach
  void setUp() throws Exception {
    BookingItTarget.assertOwned(URL, USER, PASSWORD, INSTANCE_ID);
    dropBookingTables();
    applyMigration();
    truncate();
    connections =
        new cn.zhinan.adminbooking.adapter.jdbc.JdbcConnectionProvider(new SimpleDataSource());
    customerRepository =
        new cn.zhinan.adminbooking.adapter.jdbc.JdbcCustomerRepository(connections);
    schedule = new cn.zhinan.adminbooking.adapter.jdbc.JdbcScheduleRepository(connections);
    reservations = new cn.zhinan.adminbooking.adapter.jdbc.JdbcReservationRepository(connections);
    orders = new cn.zhinan.adminbooking.adapter.jdbc.JdbcOrderRepository(connections);
    commands = new cn.zhinan.adminbooking.adapter.jdbc.JdbcCommandLedger(connections);
    TransactionRunner transactions =
        new cn.zhinan.adminbooking.adapter.jdbc.JdbcTransactionRunner(connections);
    cn.zhinan.adminbooking.adapter.jdbc.JdbcBookingAudit audit =
        new cn.zhinan.adminbooking.adapter.jdbc.JdbcBookingAudit(connections);
    application =
        new BookingApplication(
            new NoopCustomers(),
            schedule,
            reservations,
            orders,
            new FixedCatalog(),
            commands,
            audit,
            audit,
            transactions,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  /** Applies the V6 admin migration to the isolated schema. */
  private void applyMigration() throws SQLException, java.io.IOException {
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    try (InputStream stream =
        BookingDatabaseIT.class.getResourceAsStream(
            "/db/admin-migration/V6__admin_identity_booking.sql")) {
      if (stream == null)
        throw new IllegalStateException(
            "V6 admin migration resource is missing from the classpath");
      String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      for (String line : text.split("\\R")) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;
        current.append(line).append('\n');
        if (trimmed.endsWith(";")) {
          statements.add(current.toString());
          current.setLength(0);
        }
      }
    }
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        Statement statement = connection.createStatement()) {
      for (String sql : statements) {
        statement.execute(sql);
      }
    }
  }

  /** Removes any earlier version of the booking tables so the migration always applies cleanly. */
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

  private void truncate() throws SQLException {
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
        statement.execute("DELETE FROM " + table);
      }
      statement.execute("SET FOREIGN_KEY_CHECKS = 1");
    }
  }

  // ------------------------------------------------------------------ collaborators

  private static final class FixedCatalog implements PackageCatalog {
    @Override
    public Optional<PackageFact> findPackageVersion(String packageVersionRef) {
      return Optional.of(
          new PackageFact(packageVersionRef, "Portrait Session", "TWD", 12000L, "price-v1"));
    }
  }

  private static final class NoopAudit implements BookingAudit {
    @Override
    public void record(
        String actorId,
        String commandId,
        String objectType,
        String objectId,
        String action,
        String reasonCode,
        Instant now) {}
  }

  private static final class NoopOutbox implements NotificationOutbox {
    @Override
    public void enqueue(
        String intent, String recipientRef, String objectRef, Instant dueAt, Instant now) {}
  }

  private static final class NoopCustomers implements CustomerRepository {
    @Override
    public Optional<cn.zhinan.adminbooking.domain.BookingModels.Customer> find(String customerId) {
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
    public cn.zhinan.adminbooking.domain.BookingModels.ExternalIdentity createExternalIdentity(
        String customerId,
        String provider,
        String appScope,
        String subjectFingerprint,
        Instant verifiedAt,
        Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<cn.zhinan.adminbooking.domain.BookingModels.ContactPoint> findContactPoint(
        String contactPointId) {
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
    public cn.zhinan.adminbooking.domain.BookingModels.CustomerRelationship approveRelationship(
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
    public List<cn.zhinan.adminbooking.domain.BookingModels.CustomerRelationship> listRelationships(
        String customerId) {
      return List.of();
    }
  }

  // ------------------------------------------------------------------ helpers

  private long count(String table) throws SQLException {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      resultSet.next();
      return resultSet.getLong(1);
    }
  }

  private int update(String sql, Object... parameters) throws SQLException {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      return statement.executeUpdate();
    }
  }

  private void insertCustomer(String customerId) throws SQLException {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO booking_customer"
                    + " (id, status, display_name, language, created_at, updated_at, version)"
                    + " VALUES (?, 'ACTIVE', 'Integration Customer', 'zh-TW', ?, ?, 1)")) {
      statement.setString(1, customerId);
      statement.setObject(2, java.sql.Timestamp.from(NOW));
      statement.setObject(3, java.sql.Timestamp.from(NOW));
      statement.executeUpdate();
    }
  }

  private String insertReservation(String customerId) throws SQLException {
    String reservationId = java.util.UUID.randomUUID().toString();
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO booking_reservation"
                    + " (id, customer_id, package_version_ref, source, status, expires_at,"
                    + " slot_id, schedule_revision, confirmed_at, confirmed_by, created_at,"
                    + " updated_at, version)"
                    + " VALUES (?, ?, 'pkg-v1', 'STAFF_OFFLINE', 'PENDING_CONFIRMATION', ?,"
                    + " NULL, 0, NULL, NULL, ?, ?, 1)")) {
      statement.setString(1, reservationId);
      statement.setString(2, customerId);
      statement.setObject(3, java.sql.Timestamp.from(NOW.plusSeconds(48 * 3600)));
      statement.setObject(4, java.sql.Timestamp.from(NOW));
      statement.setObject(5, java.sql.Timestamp.from(NOW));
      statement.executeUpdate();
    }
    return reservationId;
  }

  private String newId() {
    return java.util.UUID.randomUUID().toString();
  }

  // ------------------------------------------------------------------ tests

  @Test
  void aConfirmationProducesOneOrderOneSnapshotAndOneOccupancy() throws SQLException {
    String customerId = newId();
    insertCustomer(customerId);
    String reservationId = insertReservation(customerId);
    String slotId = newId();
    schedule.createSlot(
        slotId,
        NOW.plusSeconds(86400),
        NOW.plusSeconds(90000),
        "Asia/Taipei",
        "+08:00",
        "rule-v1",
        1,
        NOW);

    ConfirmationResult result =
        application.confirmReservation(
            "actor-1", reservationId, slotId, 1, "confirm-key-0000000001");

    assertThat(result.orderId()).isNotBlank();
    assertThat(count("booking_order")).isEqualTo(1);
    assertThat(count("booking_order_snapshot")).isEqualTo(1);
    assertThat(count("booking_slot_occupancy")).isEqualTo(1);
    assertThat(count("booking_audit")).isEqualTo(1);
    assertThat(count("booking_notification_outbox")).isEqualTo(1);

    Reservation reservation = reservations.find(reservationId).orElseThrow();
    assertThat(reservation.status()).isEqualTo(ReservationStatus.CONFIRMED);

    Order order = orders.find(result.orderId()).orElseThrow();
    assertThat(order.sourceReservationId()).isEqualTo(reservationId);

    OrderSnapshot snapshot = orders.findSnapshot(order.snapshotId()).orElseThrow();
    assertThat(snapshot.packageVersionRef()).isEqualTo("pkg-v1");
    assertThat(snapshot.amountMinorUnits()).isEqualTo(12000L);
    assertThat(snapshot.currencyCode()).isEqualTo("TWD");
    assertThat(snapshot.slotId()).isEqualTo(slotId);
    assertThat(snapshot.customerId()).isEqualTo(customerId);
  }

  @Test
  void aSlotCanHoldOnlyOneOccupancy() throws SQLException {
    String customerId = newId();
    insertCustomer(customerId);
    String first = insertReservation(customerId);
    String second = insertReservation(customerId);
    String slotId = newId();
    schedule.createSlot(
        slotId,
        NOW.plusSeconds(86400),
        NOW.plusSeconds(90000),
        "Asia/Taipei",
        "+08:00",
        "rule-v1",
        1,
        NOW);

    schedule.occupy(slotId, first, NOW);

    assertThatThrownBy(() -> schedule.occupy(slotId, second, NOW))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).reason()).isEqualTo("SLOT_OCCUPANCY_CONFLICT"));
    assertThat(count("booking_slot_occupancy")).isEqualTo(1);
  }

  @Test
  void oneReservationCanNeverProduceASecondOrder() throws SQLException {
    String customerId = newId();
    insertCustomer(customerId);
    String reservationId = insertReservation(customerId);
    String slotId = newId();
    schedule.createSlot(
        slotId,
        NOW.plusSeconds(86400),
        NOW.plusSeconds(90000),
        "Asia/Taipei",
        "+08:00",
        "rule-v1",
        1,
        NOW);

    ConfirmationResult first =
        application.confirmReservation(
            "actor-1", reservationId, slotId, 1, "confirm-key-0000000002");
    assertThat(count("booking_order")).isEqualTo(1);

    // A different command key for the same reservation must not create a second order.
    assertThatThrownBy(
            () ->
                application.confirmReservation(
                    "actor-1", reservationId, slotId, 2, "confirm-key-0000000003"))
        .isInstanceOf(BookingException.class);
    assertThat(count("booking_order")).isEqualTo(1);
    assertThat(count("booking_order_snapshot")).isEqualTo(1);

    // The replayed key returns the same order and creates nothing new.
    ConfirmationResult replay =
        application.confirmReservation(
            "actor-1", reservationId, slotId, 1, "confirm-key-0000000002");
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.orderId()).isEqualTo(first.orderId());
    assertThat(count("booking_order")).isEqualTo(1);
    assertThat(count("booking_slot_occupancy")).isEqualTo(1);
  }

  @Test
  void theSameKeyWithAnotherPayloadIsRejected() throws SQLException {
    String customerId = newId();
    insertCustomer(customerId);
    String reservationId = insertReservation(customerId);
    String slotId = newId();
    schedule.createSlot(
        slotId,
        NOW.plusSeconds(86400),
        NOW.plusSeconds(90000),
        "Asia/Taipei",
        "+08:00",
        "rule-v1",
        1,
        NOW);

    application.confirmReservation("actor-1", reservationId, slotId, 1, "confirm-key-0000000004");

    // The same key with a different payload is a conflict, not a second command.
    String otherSlot = newId();
    schedule.createSlot(
        otherSlot,
        NOW.plusSeconds(86400),
        NOW.plusSeconds(90000),
        "Asia/Taipei",
        "+08:00",
        "rule-v1",
        1,
        NOW);
    assertThatThrownBy(
            () ->
                application.confirmReservation(
                    "actor-1", reservationId, otherSlot, 1, "confirm-key-0000000004"))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).failure())
                    .isEqualTo(BookingFailure.IDEMPOTENCY_CONFLICT));
    assertThat(count("booking_order")).isEqualTo(1);
  }

  @Test
  void aFailedConfirmationLeavesNoHalfWrittenState() throws SQLException {
    String customerId = newId();
    insertCustomer(customerId);
    String reservationId = insertReservation(customerId);
    String slotId = newId();
    schedule.createSlot(
        slotId,
        NOW.plusSeconds(86400),
        NOW.plusSeconds(90000),
        "Asia/Taipei",
        "+08:00",
        "rule-v1",
        1,
        NOW);

    // Occupy the slot with somebody else first.
    schedule.occupy(slotId, insertReservation(customerId), NOW);

    assertThatThrownBy(
            () ->
                application.confirmReservation(
                    "actor-1", reservationId, slotId, 1, "confirm-key-0000000005"))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).failure())
                    .isEqualTo(BookingFailure.SLOT_UNAVAILABLE));

    assertThat(count("booking_order")).isZero();
    assertThat(count("booking_order_snapshot")).isZero();
    assertThat(count("booking_audit")).isZero();
    assertThat(count("booking_notification_outbox")).isZero();
    Reservation reservation = reservations.find(reservationId).orElseThrow();
    assertThat(reservation.status()).isEqualTo(ReservationStatus.PENDING_CONFIRMATION);
  }

  @Test
  void aRolledBackTransactionLeavesNothingBehind() throws SQLException {
    String customerId = newId();
    insertCustomer(customerId);
    String reservationId = insertReservation(customerId);
    String slotId = newId();
    schedule.createSlot(
        slotId,
        NOW.plusSeconds(86400),
        NOW.plusSeconds(90000),
        "Asia/Taipei",
        "+08:00",
        "rule-v1",
        1,
        NOW);

    assertThatThrownBy(
            () ->
                new cn.zhinan.adminbooking.adapter.jdbc.JdbcTransactionRunner(connections)
                    .inTransaction(
                        () -> {
                          schedule.occupy(slotId, reservationId, NOW);
                          cn.zhinan.adminbooking.adapter.jdbc.JdbcBookingAudit audit =
                              new cn.zhinan.adminbooking.adapter.jdbc.JdbcBookingAudit(connections);
                          audit.record(
                              "actor-1",
                              "00000000-0000-0000-0000-000000000099",
                              "RESERVATION",
                              reservationId,
                              "CONFIRM",
                              "TEST_ROLLBACK",
                              NOW);
                          audit.enqueue("TEST_ROLLBACK", customerId, reservationId, NOW, NOW);
                          throw new BookingException(BookingFailure.CONFLICT, "INJECTED_FAILURE");
                        }))
        .isInstanceOf(BookingException.class);

    assertThat(count("booking_slot_occupancy")).isZero();
    assertThat(count("booking_audit")).isZero();
    assertThat(count("booking_notification_outbox")).isZero();
  }

  @Test
  void theExpiryTransitionIsGuardedByStatusAndTime() throws SQLException {
    String customerId = newId();
    insertCustomer(customerId);
    String reservationId = insertReservation(customerId);

    int tooEarly =
        update(
            "UPDATE booking_reservation SET status = 'EXPIRED', updated_at = ?, version = version +"
                + " 1 WHERE id = ? AND status = 'PENDING_CONFIRMATION' AND expires_at <= ?",
            java.sql.Timestamp.from(NOW),
            reservationId,
            java.sql.Timestamp.from(NOW));
    assertThat(tooEarly).isZero();

    assertThat(application.expireReservation(reservationId, NOW.plusSeconds(49 * 3600))).isTrue();
    assertThat(application.expireReservation(reservationId, NOW.plusSeconds(50 * 3600))).isFalse();
    assertThat(reservations.find(reservationId).orElseThrow().status())
        .isEqualTo(ReservationStatus.EXPIRED);
  }

  @Test
  void aCancellationReleasesTheOccupancyAndAppendsAnOrderRevision() throws SQLException {
    String customerId = newId();
    insertCustomer(customerId);
    String reservationId = insertReservation(customerId);
    String slotId = newId();
    schedule.createSlot(
        slotId,
        NOW.plusSeconds(86400),
        NOW.plusSeconds(90000),
        "Asia/Taipei",
        "+08:00",
        "rule-v1",
        1,
        NOW);
    application.confirmReservation("actor-1", reservationId, slotId, 1, "confirm-key-0000000011");

    var cancelled =
        application.cancelReservation(
            "actor-1", reservationId, 2, "CUSTOMER_REQUEST", "cancel-key-0000000011");

    assertThat(cancelled.orderRevision()).isEqualTo(2);
    assertThat(reservations.find(reservationId).orElseThrow().status())
        .isEqualTo(ReservationStatus.CANCELLED);
    assertThat(schedule.findOccupancy(slotId)).isEmpty();
    assertThat(orders.findByReservation(reservationId).orElseThrow().lifecycle())
        .isEqualTo(cn.zhinan.adminbooking.domain.BookingModels.OrderLifecycle.CANCELLED);
    assertThat(orders.listRevisions(cancelled.orderId())).hasSize(1);
  }

  @Test
  void aSuccessfulRescheduleMovesTheOccupancyAndAppendsAnOrderRevision() throws SQLException {
    String customerId = newId();
    insertCustomer(customerId);
    String reservationId = insertReservation(customerId);
    String oldSlot = newId();
    String newSlot = newId();
    schedule.createSlot(
        oldSlot,
        NOW.plusSeconds(86400),
        NOW.plusSeconds(90000),
        "Asia/Taipei",
        "+08:00",
        "rule-v1",
        1,
        NOW);
    schedule.createSlot(
        newSlot,
        NOW.plusSeconds(100000),
        NOW.plusSeconds(104000),
        "Asia/Taipei",
        "+08:00",
        "rule-v1",
        1,
        NOW);
    application.confirmReservation("actor-1", reservationId, oldSlot, 1, "confirm-key-0000000012");
    assertThat(schedule.findSlot(newSlot).orElseThrow().status()).isEqualTo("OPEN");
    assertThat(schedule.findOccupancy(newSlot)).isEmpty();

    var rescheduled =
        application.rescheduleReservation(
            "actor-1", reservationId, newSlot, 2, "CUSTOMER_REQUEST", "resched-key-00000012");

    assertThat(rescheduled.previousSlotId()).isEqualTo(oldSlot);
    assertThat(rescheduled.slotId()).isEqualTo(newSlot);
    assertThat(schedule.findOccupancy(oldSlot)).isEmpty();
    assertThat(schedule.findOccupancy(newSlot).orElseThrow().reservationId())
        .isEqualTo(reservationId);
    assertThat(reservations.find(reservationId).orElseThrow().slotId()).isEqualTo(newSlot);
    assertThat(orders.listRevisions(rescheduled.orderId())).hasSize(1);
  }

  @Test
  void aFailedRescheduleKeepsTheOriginalOccupancyAndReservationSlot() throws SQLException {
    String firstCustomer = newId();
    String secondCustomer = newId();
    insertCustomer(firstCustomer);
    insertCustomer(secondCustomer);
    String firstReservation = insertReservation(firstCustomer);
    String secondReservation = insertReservation(secondCustomer);
    String originalSlot = newId();
    String contestedSlot = newId();
    schedule.createSlot(
        originalSlot,
        NOW.plusSeconds(86400),
        NOW.plusSeconds(90000),
        "Asia/Taipei",
        "+08:00",
        "rule-v1",
        1,
        NOW);
    schedule.createSlot(
        contestedSlot,
        NOW.plusSeconds(100000),
        NOW.plusSeconds(104000),
        "Asia/Taipei",
        "+08:00",
        "rule-v1",
        1,
        NOW);
    application.confirmReservation(
        "actor-1", firstReservation, originalSlot, 1, "confirm-key-0000000013");
    application.confirmReservation(
        "actor-1", secondReservation, contestedSlot, 1, "confirm-key-0000000014");

    assertThatThrownBy(
            () ->
                application.rescheduleReservation(
                    "actor-1",
                    firstReservation,
                    contestedSlot,
                    2,
                    "CUSTOMER_REQUEST",
                    "resched-key-00000013"))
        .isInstanceOf(BookingException.class)
        .satisfies(
            failure ->
                assertThat(((BookingException) failure).failure())
                    .isEqualTo(BookingFailure.SLOT_UNAVAILABLE));

    assertThat(schedule.findOccupancy(originalSlot).orElseThrow().reservationId())
        .isEqualTo(firstReservation);
    assertThat(schedule.findOccupancy(contestedSlot).orElseThrow().reservationId())
        .isEqualTo(secondReservation);
    assertThat(reservations.find(firstReservation).orElseThrow().slotId()).isEqualTo(originalSlot);
    assertThat(
            orders.listRevisions(
                orders.findByReservation(firstReservation).orElseThrow().orderId()))
        .isEmpty();
  }

  @Test
  void theIdempotencyScopeIsUniqueInTheSchema() throws SQLException {
    String customerId = newId();
    insertCustomer(customerId);
    String reservationId = insertReservation(customerId);
    String slotId = newId();
    schedule.createSlot(
        slotId,
        NOW.plusSeconds(86400),
        NOW.plusSeconds(90000),
        "Asia/Taipei",
        "+08:00",
        "rule-v1",
        1,
        NOW);

    application.confirmReservation("actor-1", reservationId, slotId, 1, "confirm-key-0000000006");
    assertThat(count("booking_command")).isEqualTo(1);
    assertThat(commands.find("actor-1", "CONFIRM_RESERVATION", "confirm-key-0000000006"))
        .isPresent();
  }

  @Test
  void aClosedSlotCannotBeConfirmed() throws SQLException {
    String customerId = newId();
    insertCustomer(customerId);
    String reservationId = insertReservation(customerId);
    String slotId = newId();
    schedule.createSlot(
        slotId,
        NOW.plusSeconds(86400),
        NOW.plusSeconds(90000),
        "Asia/Taipei",
        "+08:00",
        "rule-v1",
        1,
        NOW);
    schedule.closeSlot(slotId, "actor-1", NOW);

    assertThatThrownBy(
            () ->
                application.confirmReservation(
                    "actor-1", reservationId, slotId, 1, "confirm-key-0000000007"))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).failure())
                    .isEqualTo(BookingFailure.SLOT_CLOSED));
    assertThat(count("booking_order")).isZero();
  }

  @Test
  void theSlotListOnlyShowsOpenUnoccupiedSlots() throws SQLException {
    String open = newId();
    String closed = newId();
    String occupied = newId();
    schedule.createSlot(
        open,
        NOW.plusSeconds(86400),
        NOW.plusSeconds(90000),
        "Asia/Taipei",
        "+08:00",
        "rule-v1",
        1,
        NOW);
    schedule.createSlot(
        closed,
        NOW.plusSeconds(99000),
        NOW.plusSeconds(99900),
        "Asia/Taipei",
        "+08:00",
        "rule-v1",
        1,
        NOW);
    schedule.createSlot(
        occupied,
        NOW.plusSeconds(108000),
        NOW.plusSeconds(111600),
        "Asia/Taipei",
        "+08:00",
        "rule-v1",
        1,
        NOW);
    schedule.closeSlot(closed, "actor-1", NOW);

    String customerId = newId();
    insertCustomer(customerId);
    String reservationId = insertReservation(customerId);
    application.confirmReservation("actor-1", reservationId, occupied, 1, "confirm-key-0000000099");

    List<ServiceSlot> slots = schedule.listOpenSlots(NOW, NOW.plusSeconds(200000), 100);
    assertThat(slots)
        .extracting(ServiceSlot::slotId)
        .contains(open)
        .doesNotContain(closed, occupied);
  }

  @Test
  void externalIdentityUniquenessIsEnforcedByTheRealSchema() throws SQLException {
    String firstCustomer = newId();
    String secondCustomer = newId();
    insertCustomer(firstCustomer);
    insertCustomer(secondCustomer);
    String fingerprint = "a".repeat(64);

    var identity =
        customerRepository.createExternalIdentity(
            firstCustomer, "WECHAT", "mini-app", fingerprint, NOW, NOW);

    assertThat(identity.customerId()).isEqualTo(firstCustomer);
    assertThat(identity.status()).isEqualTo("VERIFIED");
    assertThat(customerRepository.findExternalIdentity("WECHAT", "mini-app", fingerprint))
        .contains(identity);
    assertThatThrownBy(
            () ->
                customerRepository.createExternalIdentity(
                    secondCustomer, "WECHAT", "mini-app", fingerprint, NOW, NOW))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).reason())
                    .isEqualTo("EXTERNAL_IDENTITY_CONFLICT"));
    assertThat(count("booking_external_identity")).isEqualTo(1);
  }

  @Test
  void contactPointCiphertextIsStoredButReadsExposeOnlyTheMaskedValue() throws SQLException {
    String customerId = newId();
    insertCustomer(customerId);
    String contactId = newId();
    byte[] cipher = new byte[] {0x03, 0x11, 0x5a, (byte) 0xe7};

    var contact =
        customerRepository.createContactPoint(
            contactId, customerId, "PHONE", cipher, "********1234", "VERIFIED", NOW);

    assertThat(contact.maskedValue()).isEqualTo("********1234");
    assertThat(customerRepository.findContactPoint(contactId)).contains(contact);
    assertThat(customerRepository.listContactPoints(customerId)).containsExactly(contact);
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT value_cipher, value_masked FROM booking_contact_point WHERE id = ?")) {
      statement.setString(1, contactId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).isTrue();
        assertThat(resultSet.getBytes("value_cipher")).containsExactly(cipher);
        assertThat(resultSet.getString("value_masked")).isEqualTo("********1234");
      }
    }
  }

  @Test
  void theCommandLedgerStoresTheRequestDigest() {
    commands.record(
        "actor-1", "CONFIRM_RESERVATION", "key-0000000000000001", "digest-1", "result-1", NOW);
    Optional<StoredCommand> stored =
        commands.find("actor-1", "CONFIRM_RESERVATION", "key-0000000000000001");
    assertThat(stored).isPresent();
    assertThat(stored.get().requestSha256()).isEqualTo("digest-1");
    assertThat(stored.get().resultId()).isEqualTo("result-1");
    Map<String, StoredCommand> scope = new LinkedHashMap<>();
    scope.put("actor-1", stored.get());
    assertThat(scope).hasSize(1);
  }
}
