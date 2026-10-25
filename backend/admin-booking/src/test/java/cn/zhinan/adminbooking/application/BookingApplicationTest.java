package cn.zhinan.adminbooking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.BookingFailure;
import cn.zhinan.adminbooking.domain.BookingModels.ConfirmationResult;
import cn.zhinan.adminbooking.domain.BookingModels.Customer;
import cn.zhinan.adminbooking.domain.BookingModels.CustomerLinkDecision;
import cn.zhinan.adminbooking.domain.BookingModels.CustomerLinkRequest;
import cn.zhinan.adminbooking.domain.BookingModels.CustomerRelationship;
import cn.zhinan.adminbooking.domain.BookingModels.Order;
import cn.zhinan.adminbooking.domain.BookingModels.OrderLifecycle;
import cn.zhinan.adminbooking.domain.BookingModels.OrderRevision;
import cn.zhinan.adminbooking.domain.BookingModels.OrderSnapshot;
import cn.zhinan.adminbooking.domain.BookingModels.PackageFact;
import cn.zhinan.adminbooking.domain.BookingModels.Reservation;
import cn.zhinan.adminbooking.domain.BookingModels.ReservationCreated;
import cn.zhinan.adminbooking.domain.BookingModels.ReservationStatus;
import cn.zhinan.adminbooking.domain.BookingModels.ServiceSlot;
import cn.zhinan.adminbooking.domain.BookingModels.SlotAssignment;
import cn.zhinan.adminbooking.domain.BookingModels.SlotOccupancy;
import cn.zhinan.adminbooking.port.BookingPorts.BookingAudit;
import cn.zhinan.adminbooking.port.BookingPorts.BookingQueries;
import cn.zhinan.adminbooking.port.BookingPorts.CommandLedger;
import cn.zhinan.adminbooking.port.BookingPorts.CustomerRepository;
import cn.zhinan.adminbooking.port.BookingPorts.NotificationOutbox;
import cn.zhinan.adminbooking.port.BookingPorts.OrderRepository;
import cn.zhinan.adminbooking.port.BookingPorts.PackageCatalog;
import cn.zhinan.adminbooking.port.BookingPorts.ReservationRepository;
import cn.zhinan.adminbooking.port.BookingPorts.ScheduleRepository;
import cn.zhinan.adminbooking.port.BookingPorts.StoredCommand;
import cn.zhinan.adminbooking.port.BookingPorts.TransactionRunner;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Application level checks for the S4/S5 command semantics. The fakes model the database guarantees
 * that matter here: unique slot occupancy, unique order per reservation, conditional version
 * updates and idempotent command replay. They are not a substitute for the real MySQL evidence
 * required by D-703 §7.
 */
class BookingApplicationTest {

  private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
  private static final String ACTOR = "actor-1";
  private static final String CUSTOMER = "3f2b6a54-0000-4000-8000-000000000001";

  private FakeCustomers customers;
  private FakeSchedule schedule;
  private FakeReservations reservations;
  private FakeOrders orders;
  private FakeCommands commands;
  private FakeAudit audit;
  private FakeOutbox outbox;
  private BookingApplication application;

  @BeforeEach
  void setUp() {
    customers = new FakeCustomers();
    schedule = new FakeSchedule();
    reservations = new FakeReservations();
    orders = new FakeOrders();
    commands = new FakeCommands();
    audit = new FakeAudit();
    outbox = new FakeOutbox();
    application =
        new BookingApplication(
            customers,
            schedule,
            reservations,
            orders,
            new FakeCatalog(),
            commands,
            audit,
            outbox,
            new FakeTransactions(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  // ------------------------------------------------------------------ reservation

  @Test
  void creatingAReservationIsPendingAndHoldsNoSlot() {
    ReservationCreated created =
        application.createReservation(
            ACTOR, CUSTOMER, "pkg-v1", "CUSTOMER_ONLINE", "idem-create-0000001", null);

    assertThat(created.replayed()).isFalse();
    Reservation reservation = reservations.byId(created.reservationId());
    assertThat(reservation.status()).isEqualTo(ReservationStatus.PENDING_CONFIRMATION);
    assertThat(schedule.occupancies()).isEmpty();
    assertThat(orders.all()).isEmpty();
    assertThat(reservation.expiresAt()).isEqualTo(NOW.plusSeconds(48 * 3600));
  }

  @Test
  void theSameIdempotencyKeyReplaysTheSameReservation() {
    ReservationCreated first =
        application.createReservation(
            ACTOR, CUSTOMER, "pkg-v1", "CUSTOMER_ONLINE", "idem-create-0000001", null);
    ReservationCreated second =
        application.createReservation(
            ACTOR, CUSTOMER, "pkg-v1", "CUSTOMER_ONLINE", "idem-create-0000001", null);

    assertThat(second.replayed()).isTrue();
    assertThat(second.reservationId()).isEqualTo(first.reservationId());
    assertThat(reservations.all()).hasSize(1);
  }

  @Test
  void theSameIdempotencyKeyWithAnotherPayloadIsRejected() {
    application.createReservation(
        ACTOR, CUSTOMER, "pkg-v1", "CUSTOMER_ONLINE", "idem-create-0000001", null);

    assertThatThrownBy(
            () ->
                application.createReservation(
                    ACTOR, CUSTOMER, "pkg-v2", "CUSTOMER_ONLINE", "idem-create-0000001", null))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).failure())
                    .isEqualTo(BookingFailure.IDEMPOTENCY_CONFLICT));
  }

  @Test
  void anUnknownPackageIsRejectedBeforeAnythingIsWritten() {
    assertThatThrownBy(
            () ->
                application.createReservation(
                    ACTOR, CUSTOMER, "pkg-missing", "CUSTOMER_ONLINE", "idem-create-0000002", null))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).failure()).isEqualTo(BookingFailure.NOT_FOUND));
    assertThat(reservations.all()).isEmpty();
    assertThat(commands.all()).isEmpty();
  }

  // ------------------------------------------------------------------ confirmation

  @Test
  void confirmingCreatesTheOrderInTheSameTransaction() {
    ReservationCreated created = pendingReservation("idem-create-0000003");
    String slotId = schedule.addOpenSlot(NOW.plusSeconds(86400), "Asia/Taipei");

    ConfirmationResult result =
        application.confirmReservation(
            ACTOR, created.reservationId(), slotId, 1, "idem-confirm-000001");

    assertThat(result.replayed()).isFalse();
    assertThat(reservations.byId(created.reservationId()).status())
        .isEqualTo(ReservationStatus.CONFIRMED);
    assertThat(schedule.occupancy(slotId).reservationId()).isEqualTo(created.reservationId());
    assertThat(orders.all()).hasSize(1);
    Order order = orders.all().get(0);
    assertThat(order.sourceReservationId()).isEqualTo(created.reservationId());
    assertThat(order.lifecycle()).isEqualTo(OrderLifecycle.ACTIVE);
    assertThat(outbox.intents()).contains("RESERVATION_CONFIRMED");
  }

  @Test
  void theOrderSnapshotIsImmutableAndCapturesTheConfirmationFacts() {
    ReservationCreated created = pendingReservation("idem-create-0000004");
    String slotId = schedule.addOpenSlot(NOW.plusSeconds(86400), "Asia/Taipei");

    application.confirmReservation(
        ACTOR, created.reservationId(), slotId, 1, "idem-confirm-000002");

    OrderSnapshot snapshot = orders.snapshotOf(orders.all().get(0));
    assertThat(snapshot).isNotNull();
    assertThat(snapshot.packageVersionRef()).isEqualTo("pkg-v1");
    assertThat(snapshot.currencyCode()).isEqualTo("TWD");
    assertThat(snapshot.amountMinorUnits()).isEqualTo(12000L);
    assertThat(snapshot.slotId()).isEqualTo(slotId);
    assertThat(snapshot.customerId()).isEqualTo(CUSTOMER);
    // The snapshot is a copy, not a live reference to the current package fact.
    assertThat(snapshot.priceVersion()).isEqualTo("price-v1");
  }

  @Test
  void aReplayedConfirmationReturnsTheSameOrderWithoutCreatingAnother() {
    ReservationCreated created = pendingReservation("idem-create-0000005");
    String slotId = schedule.addOpenSlot(NOW.plusSeconds(86400), "Asia/Taipei");

    ConfirmationResult first =
        application.confirmReservation(
            ACTOR, created.reservationId(), slotId, 1, "idem-confirm-000003");
    ConfirmationResult second =
        application.confirmReservation(
            ACTOR, created.reservationId(), slotId, 1, "idem-confirm-000003");

    assertThat(second.replayed()).isTrue();
    assertThat(second.orderId()).isEqualTo(first.orderId());
    assertThat(orders.all()).hasSize(1);
    assertThat(schedule.occupancies()).hasSize(1);
  }

  @Test
  void aSecondConfirmationWithAnotherKeyIsRejectedBecauseTheReservationIsConfirmed() {
    ReservationCreated created = pendingReservation("idem-create-0000006");
    String slotId = schedule.addOpenSlot(NOW.plusSeconds(86400), "Asia/Taipei");
    application.confirmReservation(
        ACTOR, created.reservationId(), slotId, 1, "idem-confirm-000004");

    assertThatThrownBy(
            () ->
                application.confirmReservation(
                    ACTOR, created.reservationId(), slotId, 2, "idem-confirm-000005"))
        .isInstanceOf(BookingException.class);
    assertThat(orders.all()).hasSize(1);
  }

  @Test
  void confirmingAgainstATakenSlotFailsAndLeavesNoOrder() {
    ReservationCreated created = pendingReservation("idem-create-0000007");
    String slotId = schedule.addOpenSlot(NOW.plusSeconds(86400), "Asia/Taipei");
    schedule.addOccupancy(slotId, "another-reservation");

    assertThatThrownBy(
            () ->
                application.confirmReservation(
                    ACTOR, created.reservationId(), slotId, 1, "idem-confirm-000006"))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).failure())
                    .isEqualTo(BookingFailure.SLOT_UNAVAILABLE));
    assertThat(orders.all()).isEmpty();
    assertThat(reservations.byId(created.reservationId()).status())
        .isEqualTo(ReservationStatus.PENDING_CONFIRMATION);
  }

  @Test
  void confirmingAgainstAClosedSlotFails() {
    ReservationCreated created = pendingReservation("idem-create-0000008");
    String slotId = schedule.addOpenSlot(NOW.plusSeconds(86400), "Asia/Taipei");
    schedule.close(slotId);

    assertThatThrownBy(
            () ->
                application.confirmReservation(
                    ACTOR, created.reservationId(), slotId, 1, "idem-confirm-000007"))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).failure())
                    .isEqualTo(BookingFailure.SLOT_CLOSED));
  }

  @Test
  void aStaleReservationRevisionIsRejected() {
    ReservationCreated created = pendingReservation("idem-create-0000009");
    String slotId = schedule.addOpenSlot(NOW.plusSeconds(86400), "Asia/Taipei");

    assertThatThrownBy(
            () ->
                application.confirmReservation(
                    ACTOR, created.reservationId(), slotId, 7, "idem-confirm-000008"))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).failure())
                    .isEqualTo(BookingFailure.VERSION_CONFLICT));
  }

  @Test
  void confirmingAnExpiredReservationFails() {
    ReservationCreated created = pendingReservation("idem-create-0000010");
    String slotId = schedule.addOpenSlot(NOW.plusSeconds(86400), "Asia/Taipei");
    reservations.setExpiresAt(created.reservationId(), NOW.minusSeconds(1));

    assertThatThrownBy(
            () ->
                application.confirmReservation(
                    ACTOR, created.reservationId(), slotId, 1, "idem-confirm-000009"))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).failure())
                    .isEqualTo(BookingFailure.RESERVATION_EXPIRED));
  }

  // ------------------------------------------------------------------ expiry

  @Test
  void expiryIsTerminalAndOnlyWinsOnce() {
    ReservationCreated created = pendingReservation("idem-create-0000011");

    Instant afterExpiry = NOW.plusSeconds(49 * 3600);
    assertThat(application.expireReservation(created.reservationId(), afterExpiry)).isTrue();
    assertThat(application.expireReservation(created.reservationId(), afterExpiry)).isFalse();
    assertThat(reservations.byId(created.reservationId()).status())
        .isEqualTo(ReservationStatus.EXPIRED);
    assertThat(schedule.occupancies()).isEmpty();
  }

  // ------------------------------------------------------------------ cancel

  @Test
  void cancellingReleasesTheSlotAndAppendsAnOrderRevision() {
    ReservationCreated created = pendingReservation("idem-create-0000012");
    String slotId = schedule.addOpenSlot(NOW.plusSeconds(86400), "Asia/Taipei");
    application.confirmReservation(
        ACTOR, created.reservationId(), slotId, 1, "idem-confirm-000010");

    var result =
        application.cancelReservation(
            ACTOR, created.reservationId(), 2, "CUSTOMER_REQUEST", "idem-cancel-00001");

    assertThat(result.replayed()).isFalse();
    assertThat(schedule.occupancies()).isEmpty();
    assertThat(reservations.byId(created.reservationId()).status())
        .isEqualTo(ReservationStatus.CANCELLED);
    Order order = orders.all().get(0);
    assertThat(order.lifecycle()).isEqualTo(OrderLifecycle.CANCELLED);
    assertThat(orders.revisions(order.orderId())).hasSize(1);
  }

  @Test
  void cancellingTwiceWithAnotherKeyIsRejected() {
    ReservationCreated created = pendingReservation("idem-create-0000013");
    String slotId = schedule.addOpenSlot(NOW.plusSeconds(86400), "Asia/Taipei");
    application.confirmReservation(
        ACTOR, created.reservationId(), slotId, 1, "idem-confirm-000011");
    application.cancelReservation(
        ACTOR, created.reservationId(), 2, "CUSTOMER_REQUEST", "idem-cancel-00002");

    assertThatThrownBy(
            () ->
                application.cancelReservation(
                    ACTOR, created.reservationId(), 3, "CUSTOMER_REQUEST", "idem-cancel-00003"))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).failure())
                    .isEqualTo(BookingFailure.RESERVATION_ALREADY_CANCELLED));
  }

  @Test
  void aReplayedCancellationReturnsTheSameResult() {
    ReservationCreated created = pendingReservation("idem-create-0000014");
    String slotId = schedule.addOpenSlot(NOW.plusSeconds(86400), "Asia/Taipei");
    application.confirmReservation(
        ACTOR, created.reservationId(), slotId, 1, "idem-confirm-000012");

    var first =
        application.cancelReservation(
            ACTOR, created.reservationId(), 2, "CUSTOMER_REQUEST", "idem-cancel-00004");
    var second =
        application.cancelReservation(
            ACTOR, created.reservationId(), 2, "CUSTOMER_REQUEST", "idem-cancel-00004");

    assertThat(second.replayed()).isTrue();
    assertThat(second.commandId()).isEqualTo(first.commandId());
  }

  // ------------------------------------------------------------------ reschedule

  @Test
  void reschedulingMovesTheOccupancyAndKeepsTheReservationConfirmed() {
    ReservationCreated created = pendingReservation("idem-create-0000015");
    String firstSlot = schedule.addOpenSlot(NOW.plusSeconds(86400), "Asia/Taipei");
    String secondSlot = schedule.addOpenSlot(NOW.plusSeconds(90000), "Asia/Taipei");
    application.confirmReservation(
        ACTOR, created.reservationId(), firstSlot, 1, "idem-confirm-000013");

    var result =
        application.rescheduleReservation(
            ACTOR,
            created.reservationId(),
            secondSlot,
            2,
            "CUSTOMER_REQUEST",
            "idem-resched-00001");

    assertThat(result.previousSlotId()).isEqualTo(firstSlot);
    assertThat(result.slotId()).isEqualTo(secondSlot);
    assertThat(schedule.occupancy(firstSlot)).isNull();
    assertThat(schedule.occupancy(secondSlot).reservationId()).isEqualTo(created.reservationId());
    assertThat(reservations.byId(created.reservationId()).status())
        .isEqualTo(ReservationStatus.CONFIRMED);
    assertThat(orders.revisions(orders.all().get(0).orderId())).hasSize(1);
  }

  @Test
  void aFailedRescheduleKeepsTheOriginalSlot() {
    ReservationCreated created = pendingReservation("idem-create-0000016");
    String firstSlot = schedule.addOpenSlot(NOW.plusSeconds(86400), "Asia/Taipei");
    String takenSlot = schedule.addOpenSlot(NOW.plusSeconds(90000), "Asia/Taipei");
    application.confirmReservation(
        ACTOR, created.reservationId(), firstSlot, 1, "idem-confirm-000014");
    schedule.addOccupancy(takenSlot, "someone-else");

    assertThatThrownBy(
            () ->
                application.rescheduleReservation(
                    ACTOR,
                    created.reservationId(),
                    takenSlot,
                    2,
                    "CUSTOMER_REQUEST",
                    "idem-resched-00002"))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).failure())
                    .isEqualTo(BookingFailure.SLOT_UNAVAILABLE));
    assertThat(schedule.occupancy(firstSlot)).isNotNull();
    assertThat(reservations.byId(created.reservationId()).slotId()).isEqualTo(firstSlot);
  }

  @Test
  void reschedulingToTheSameSlotIsRejected() {
    ReservationCreated created = pendingReservation("idem-create-0000017");
    String slotId = schedule.addOpenSlot(NOW.plusSeconds(86400), "Asia/Taipei");
    application.confirmReservation(
        ACTOR, created.reservationId(), slotId, 1, "idem-confirm-000015");

    assertThatThrownBy(
            () ->
                application.rescheduleReservation(
                    ACTOR,
                    created.reservationId(),
                    slotId,
                    2,
                    "CUSTOMER_REQUEST",
                    "idem-resched-00003"))
        .isInstanceOf(BookingException.class);
  }

  // ------------------------------------------------------------------ customer link

  @Test
  void aVerifiedExternalIdentityIsAttachedByFingerprintAndReplaysSafely() {
    customers.create(CUSTOMER, "Test Customer", "zh", NOW);
    String fingerprint = "a".repeat(64);

    var first =
        application.attachExternalIdentity(
            ACTOR, "idem-identity-000001", CUSTOMER, "WECHAT", "mini-app", fingerprint, NOW);
    var replay =
        application.attachExternalIdentity(
            ACTOR, "idem-identity-000001", CUSTOMER, "WECHAT", "mini-app", fingerprint, NOW);

    assertThat(first.status()).isEqualTo("VERIFIED");
    assertThat(first.subjectFingerprint()).isEqualTo(fingerprint);
    assertThat(replay).isEqualTo(first);
    assertThat(customers.externalIdentities).hasSize(1);
  }

  @Test
  void anExternalIdentityCannotBeLinkedToAnotherCustomer() {
    customers.create(CUSTOMER, "Test Customer", "zh", NOW);
    String secondCustomer = "3f2b6a54-0000-4000-8000-000000000002";
    customers.create(secondCustomer, "Other Customer", "zh", NOW);
    String fingerprint = "b".repeat(64);
    application.attachExternalIdentity(
        ACTOR, "idem-identity-000001", CUSTOMER, "WECHAT", "mini-app", fingerprint, NOW);

    assertThatThrownBy(
            () ->
                application.attachExternalIdentity(
                    ACTOR,
                    "idem-identity-000002",
                    secondCustomer,
                    "WECHAT",
                    "mini-app",
                    fingerprint,
                    NOW))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).failure())
                    .isEqualTo(BookingFailure.CUSTOMER_LINK_EXISTS));
    assertThat(customers.externalIdentities).hasSize(1);
  }

  @Test
  void aRawOrMalformedExternalSubjectIsNotAcceptedAsAFingerprint() {
    customers.create(CUSTOMER, "Test Customer", "zh", NOW);

    assertThatThrownBy(
            () ->
                application.attachExternalIdentity(
                    ACTOR,
                    "idem-identity-000001",
                    CUSTOMER,
                    "WECHAT",
                    "mini-app",
                    "raw-open-id",
                    NOW))
        .isInstanceOf(BookingException.class);
    assertThat(customers.externalIdentities).isEmpty();
  }

  @Test
  void contactPointsPersistOnlyEncryptedInputAndExposeOnlyTheMaskedValue() {
    customers.create(CUSTOMER, "Test Customer", "zh", NOW);
    byte[] encrypted = new byte[] {1, 2, 3, 4};

    var contact =
        application.addContactPoint(
            ACTOR, "idem-contact-000001", CUSTOMER, "PHONE", encrypted, "********1234", "VERIFIED");
    var listed = application.contactPoints(CUSTOMER);

    assertThat(contact.maskedValue()).isEqualTo("********1234");
    assertThat(listed).containsExactly(contact);
  }

  @Test
  void aContactPointReplayDoesNotCreateAnotherAndChangedCiphertextConflicts() {
    customers.create(CUSTOMER, "Test Customer", "zh", NOW);
    byte[] encrypted = new byte[] {9, 8, 7};
    var first =
        application.addContactPoint(
            ACTOR,
            "idem-contact-000001",
            CUSTOMER,
            "EMAIL",
            encrypted,
            "a***@example.test",
            "UNVERIFIED");
    var replay =
        application.addContactPoint(
            ACTOR,
            "idem-contact-000001",
            CUSTOMER,
            "EMAIL",
            encrypted,
            "a***@example.test",
            "UNVERIFIED");

    assertThat(replay).isEqualTo(first);
    assertThat(customers.contactPoints).hasSize(1);
    assertThatThrownBy(
            () ->
                application.addContactPoint(
                    ACTOR,
                    "idem-contact-000001",
                    CUSTOMER,
                    "EMAIL",
                    new byte[] {9, 8, 6},
                    "a***@example.test",
                    "UNVERIFIED"))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).failure())
                    .isEqualTo(BookingFailure.IDEMPOTENCY_CONFLICT));
  }

  @Test
  void aLinkRequestGrantsNoAccessBeforeItIsApproved() {
    var request =
        application.requestCustomerLink(ACTOR, CUSTOMER, "candidate-ref", "MANUAL_REVIEW", null);
    assertThat(request.status()).isEqualTo("PENDING");

    var decision = application.decideCustomerLink(ACTOR, request.linkRequestId(), true);
    assertThat(decision.status()).isEqualTo("APPROVED");
    assertThat(decision.approvedRelationshipId()).isPresent();
  }

  @Test
  void aRejectedLinkRequestIsTerminal() {
    var request =
        application.requestCustomerLink(ACTOR, CUSTOMER, "candidate-ref", "MANUAL_REVIEW", null);
    var decision = application.decideCustomerLink(ACTOR, request.linkRequestId(), false);
    assertThat(decision.status()).isEqualTo("REJECTED");

    assertThatThrownBy(() -> application.decideCustomerLink(ACTOR, request.linkRequestId(), true))
        .isInstanceOf(BookingException.class);
  }

  @Test
  void aLinkRequestCannotBeDecidedTwice() {
    var request =
        application.requestCustomerLink(ACTOR, CUSTOMER, "candidate-ref", "MANUAL_REVIEW", null);
    application.decideCustomerLink(ACTOR, request.linkRequestId(), true);

    assertThatThrownBy(() -> application.decideCustomerLink(ACTOR, request.linkRequestId(), false))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex -> assertThat(((BookingException) ex).failure()).isEqualTo(BookingFailure.CONFLICT));
  }

  // ------------------------------------------------------------------ helpers

  private ReservationCreated pendingReservation(String idempotencyKey) {
    return application.createReservation(
        ACTOR, CUSTOMER, "pkg-v1", "CUSTOMER_ONLINE", idempotencyKey, null);
  }

  // ------------------------------------------------------------------ fakes

  private static final class FakeTransactions implements TransactionRunner {
    @Override
    public <T> T inTransaction(java.util.function.Supplier<T> work) {
      return work.get();
    }

    @Override
    public void inTransaction(Runnable work) {
      work.run();
    }
  }

  private static final class FakeCatalog implements PackageCatalog {
    @Override
    public Optional<PackageFact> findPackageVersion(String packageVersionRef) {
      if ("pkg-v1".equals(packageVersionRef))
        return Optional.of(
            new PackageFact("pkg-v1", "Portrait Session", "TWD", 12000L, "price-v1"));
      if ("pkg-v2".equals(packageVersionRef))
        return Optional.of(
            new PackageFact("pkg-v2", "Portrait Session", "TWD", 13000L, "price-v2"));
      return Optional.empty();
    }
  }

  private static final class FakeCommands implements CommandLedger {
    private final Map<String, StoredCommand> byScope = new LinkedHashMap<>();
    private final Map<String, String> resultByScope = new LinkedHashMap<>();

    private static String scope(String actorId, String operation, String key) {
      return actorId + "|" + operation + "|" + key;
    }

    @Override
    public Optional<StoredCommand> find(String actorId, String operation, String idempotencyKey) {
      return Optional.ofNullable(byScope.get(scope(actorId, operation, idempotencyKey)));
    }

    @Override
    public void record(
        String actorId,
        String operation,
        String idempotencyKey,
        String requestSha256,
        String resultId,
        Instant completedAt) {
      byScope.put(
          scope(actorId, operation, idempotencyKey), new StoredCommand(requestSha256, resultId));
      resultByScope.put(scope(actorId, operation, idempotencyKey), resultId);
    }

    List<StoredCommand> all() {
      return new ArrayList<>(byScope.values());
    }
  }

  private static final class FakeAudit implements BookingAudit {
    private final List<String> events = new ArrayList<>();

    @Override
    public void record(
        String actorId,
        String commandId,
        String objectType,
        String objectId,
        String action,
        String reasonCode,
        Instant now) {
      events.add(action + ":" + objectType + ":" + objectId + ":" + reasonCode);
    }
  }

  private static final class FakeOutbox implements NotificationOutbox {
    private final List<String> intents = new ArrayList<>();

    @Override
    public void enqueue(
        String intent, String recipientRef, String objectRef, Instant dueAt, Instant now) {
      intents.add(intent);
    }

    List<String> intents() {
      return intents;
    }
  }

  private static final class FakeCustomers implements CustomerRepository {
    private final Map<String, Customer> byId = new LinkedHashMap<>();
    private final Map<String, cn.zhinan.adminbooking.domain.BookingModels.ExternalIdentity>
        externalIdentities = new LinkedHashMap<>();
    private final Map<String, cn.zhinan.adminbooking.domain.BookingModels.ContactPoint>
        contactPoints = new LinkedHashMap<>();
    private final Map<String, CustomerRelationship> relationships = new LinkedHashMap<>();
    private final Map<String, CustomerLinkRequest> linkRequests = new LinkedHashMap<>();

    @Override
    public Optional<Customer> find(String customerId) {
      return Optional.ofNullable(byId.get(customerId));
    }

    @Override
    public Customer create(String customerId, String displayName, String language, Instant now) {
      Customer customer = new Customer(customerId, "ACTIVE", displayName, language, now, 1);
      byId.put(customerId, customer);
      return customer;
    }

    @Override
    public Optional<cn.zhinan.adminbooking.domain.BookingModels.ExternalIdentity>
        findExternalIdentity(String provider, String appScope, String subjectFingerprint) {
      return Optional.ofNullable(
          externalIdentities.get(provider + ":" + appScope + ":" + subjectFingerprint));
    }

    @Override
    public cn.zhinan.adminbooking.domain.BookingModels.ExternalIdentity createExternalIdentity(
        String customerId,
        String provider,
        String appScope,
        String subjectFingerprint,
        Instant verifiedAt,
        Instant now) {
      var identity =
          new cn.zhinan.adminbooking.domain.BookingModels.ExternalIdentity(
              customerId,
              provider,
              appScope,
              subjectFingerprint,
              verifiedAt == null ? "PENDING" : "VERIFIED",
              verifiedAt);
      externalIdentities.put(provider + ":" + appScope + ":" + subjectFingerprint, identity);
      return identity;
    }

    @Override
    public Optional<cn.zhinan.adminbooking.domain.BookingModels.ContactPoint> findContactPoint(
        String contactPointId) {
      return Optional.ofNullable(contactPoints.get(contactPointId));
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
      var contact =
          new cn.zhinan.adminbooking.domain.BookingModels.ContactPoint(
              contactPointId, customerId, type, maskedValue, verificationStatus, now);
      contactPoints.put(contactPointId, contact);
      return contact;
    }

    @Override
    public List<cn.zhinan.adminbooking.domain.BookingModels.ContactPoint> listContactPoints(
        String customerId) {
      return contactPoints.values().stream()
          .filter(contact -> contact.customerId().equals(customerId))
          .toList();
    }

    @Override
    public Optional<CustomerRelationship> findRelationship(String relationshipId) {
      return Optional.ofNullable(relationships.get(relationshipId));
    }

    @Override
    public Optional<CustomerRelationship> findRelationship(
        String customerId, String relatedCustomerId) {
      return relationships.values().stream()
          .filter(
              relationship ->
                  relationship.customerId().equals(customerId)
                      && relationship.relatedCustomerId().equals(relatedCustomerId))
          .findFirst();
    }

    @Override
    public CustomerRelationship approveRelationship(
        String relationshipId,
        String customerId,
        String relatedCustomerId,
        String relationType,
        String purpose,
        Instant validFrom,
        Instant validUntil,
        String actorId,
        Instant now) {
      CustomerRelationship relationship =
          new CustomerRelationship(
              relationshipId,
              customerId,
              relatedCustomerId,
              cn.zhinan.adminbooking.domain.BookingModels.RelationshipType.valueOf(relationType),
              purpose,
              "APPROVED",
              validFrom,
              validUntil,
              false);
      relationships.put(relationshipId, relationship);
      return relationship;
    }

    @Override
    public Optional<CustomerLinkRequest> findLinkRequest(String linkRequestId) {
      return Optional.ofNullable(linkRequests.get(linkRequestId));
    }

    @Override
    public CustomerLinkRequest createLinkRequest(
        String linkRequestId,
        String requesterCustomerId,
        String candidateRef,
        String method,
        Instant expiresAt,
        Instant now) {
      CustomerLinkRequest request =
          new CustomerLinkRequest(
              linkRequestId, requesterCustomerId, candidateRef, method, "PENDING", expiresAt, null);
      linkRequests.put(linkRequestId, request);
      return request;
    }

    @Override
    public CustomerLinkDecision decideLinkRequest(
        String linkRequestId, boolean approve, String actorId, Instant now) {
      CustomerLinkRequest request = linkRequests.get(linkRequestId);
      String status = approve ? "APPROVED" : "REJECTED";
      linkRequests.put(
          linkRequestId,
          new CustomerLinkRequest(
              request.linkRequestId(),
              request.requesterCustomerId(),
              request.candidateRef(),
              request.method(),
              status,
              request.expiresAt(),
              now));
      String relationshipId = null;
      if (approve) {
        relationshipId = "rel-" + linkRequestId;
        approveRelationship(
            relationshipId,
            request.requesterCustomerId(),
            request.candidateRef(),
            "OTHER",
            "BOOKING",
            now,
            null,
            actorId,
            now);
      }
      return new CustomerLinkDecision(linkRequestId, status, Optional.ofNullable(relationshipId));
    }

    @Override
    public List<CustomerRelationship> listRelationships(String customerId) {
      return relationships.values().stream()
          .filter(relationship -> relationship.customerId().equals(customerId))
          .toList();
    }
  }

  private static final class FakeSchedule implements ScheduleRepository {
    private final Map<String, ServiceSlot> slots = new LinkedHashMap<>();
    private final Map<String, SlotOccupancy> occupancies = new LinkedHashMap<>();
    private final List<SlotAssignment> assignments = new ArrayList<>();

    String addOpenSlot(Instant start, String zone) {
      // Slot ids are server generated, so they are UUIDs like the real adapter produces.
      String slotId = String.format("00000000-0000-4000-8000-%012d", slots.size() + 1);
      slots.put(
          slotId,
          new ServiceSlot(
              slotId, start, start.plusSeconds(3600), zone, "+08:00", "OPEN", 1, "rule-v1", 1));
      return slotId;
    }

    void close(String slotId) {
      ServiceSlot slot = slots.get(slotId);
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
    }

    void addOccupancy(String slotId, String reservationId) {
      occupancies.put(slotId, new SlotOccupancy(slotId, reservationId, NOW, 1));
    }

    SlotOccupancy occupancy(String slotId) {
      return occupancies.get(slotId);
    }

    List<SlotOccupancy> occupancies() {
      return new ArrayList<>(occupancies.values());
    }

    @Override
    public Optional<ServiceSlot> findSlot(String slotId) {
      return Optional.ofNullable(slots.get(slotId));
    }

    @Override
    public List<ServiceSlot> listOpenSlots(Instant from, Instant to, int limit) {
      return slots.values().stream()
          .filter(slot -> "OPEN".equals(slot.status()))
          .filter(slot -> !slot.startAt().isBefore(from) && slot.startAt().isBefore(to))
          .limit(limit)
          .toList();
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
      if (!slots.containsKey(slotId)) return 0;
      close(slotId);
      return 1;
    }

    @Override
    public Optional<SlotOccupancy> findOccupancy(String slotId) {
      return Optional.ofNullable(occupancies.get(slotId));
    }

    @Override
    public SlotOccupancy occupy(String slotId, String reservationId, Instant now) {
      if (occupancies.containsKey(slotId))
        throw new BookingException(BookingFailure.CONFLICT, "SLOT_OCCUPANCY_CONFLICT");
      SlotOccupancy occupancy = new SlotOccupancy(slotId, reservationId, now, 1);
      occupancies.put(slotId, occupancy);
      return occupancy;
    }

    @Override
    public SlotOccupancy moveOccupancy(
        String previousSlotId, String newSlotId, String reservationId, Instant now) {
      SlotOccupancy previous = occupancies.get(previousSlotId);
      if (previous == null || !previous.reservationId().equals(reservationId))
        throw new BookingException(BookingFailure.VERSION_CONFLICT, "OCCUPANCY_MOVED");
      if (occupancies.containsKey(newSlotId))
        throw new BookingException(BookingFailure.CONFLICT, "SLOT_OCCUPANCY_CONFLICT");
      occupancies.remove(previousSlotId);
      SlotOccupancy moved =
          new SlotOccupancy(newSlotId, reservationId, now, previous.revision() + 1);
      occupancies.put(newSlotId, moved);
      return moved;
    }

    @Override
    public void release(String slotId, Instant now) {
      occupancies.remove(slotId);
    }

    @Override
    public void appendAssignment(SlotAssignment assignment) {
      assignments.add(assignment);
    }
  }

  private static final class FakeReservations implements ReservationRepository {
    private final Map<String, Reservation> byId = new LinkedHashMap<>();
    private final Map<String, List<SlotAssignment>> assignments = new LinkedHashMap<>();

    Reservation byId(String reservationId) {
      return byId.get(reservationId);
    }

    void setExpiresAt(String reservationId, Instant expiresAt) {
      Reservation reservation = byId.get(reservationId);
      byId.put(
          reservationId,
          new Reservation(
              reservation.reservationId(),
              reservation.customerId(),
              reservation.packageVersionRef(),
              reservation.source(),
              reservation.status(),
              expiresAt,
              reservation.slotId(),
              reservation.scheduleRevision(),
              reservation.confirmedAt(),
              reservation.confirmedBy(),
              reservation.createdAt(),
              reservation.version()));
    }

    List<Reservation> all() {
      return new ArrayList<>(byId.values());
    }

    @Override
    public Optional<Reservation> find(String reservationId) {
      return Optional.ofNullable(byId.get(reservationId));
    }

    @Override
    public ReservationCreated create(
        String reservationId,
        String customerId,
        String packageVersionRef,
        String source,
        Instant expiresAt,
        String actorId,
        String idempotencyKey,
        String requestSha256,
        Instant now) {
      Reservation reservation =
          new Reservation(
              reservationId,
              customerId,
              packageVersionRef,
              source,
              ReservationStatus.PENDING_CONFIRMATION,
              expiresAt,
              null,
              0,
              null,
              null,
              now,
              1);
      byId.put(reservationId, reservation);
      return new ReservationCreated(reservationId, 1, false);
    }

    @Override
    public List<Reservation> listPending(int limit) {
      return byId.values().stream()
          .filter(reservation -> reservation.status() == ReservationStatus.PENDING_CONFIRMATION)
          .limit(limit)
          .toList();
    }

    @Override
    public List<Reservation> listByCustomer(String customerId, int limit) {
      return byId.values().stream()
          .filter(reservation -> reservation.customerId().equals(customerId))
          .limit(limit)
          .toList();
    }

    @Override
    public List<SlotAssignment> listAssignments(String reservationId) {
      return assignments.getOrDefault(reservationId, List.of());
    }

    @Override
    public int markConfirmed(
        String reservationId,
        int expectedVersion,
        String slotId,
        int scheduleRevision,
        String actorId,
        Instant now) {
      Reservation reservation = byId.get(reservationId);
      if (reservation == null || reservation.version() != expectedVersion) return 0;
      byId.put(
          reservationId,
          new Reservation(
              reservation.reservationId(),
              reservation.customerId(),
              reservation.packageVersionRef(),
              reservation.source(),
              ReservationStatus.CONFIRMED,
              reservation.expiresAt(),
              slotId,
              scheduleRevision,
              now,
              actorId,
              reservation.createdAt(),
              reservation.version() + 1));
      return 1;
    }

    @Override
    public int markRejected(
        String reservationId, int expectedVersion, String reasonCode, String actorId, Instant now) {
      Reservation reservation = byId.get(reservationId);
      if (reservation == null || reservation.version() != expectedVersion) return 0;
      byId.put(
          reservationId,
          new Reservation(
              reservation.reservationId(),
              reservation.customerId(),
              reservation.packageVersionRef(),
              reservation.source(),
              ReservationStatus.REJECTED,
              reservation.expiresAt(),
              null,
              reservation.scheduleRevision(),
              null,
              null,
              reservation.createdAt(),
              reservation.version() + 1));
      return 1;
    }

    @Override
    public int markCancelled(
        String reservationId, int expectedVersion, String actorId, Instant now) {
      Reservation reservation = byId.get(reservationId);
      if (reservation == null || reservation.version() != expectedVersion) return 0;
      byId.put(
          reservationId,
          new Reservation(
              reservation.reservationId(),
              reservation.customerId(),
              reservation.packageVersionRef(),
              reservation.source(),
              ReservationStatus.CANCELLED,
              reservation.expiresAt(),
              reservation.slotId(),
              reservation.scheduleRevision(),
              reservation.confirmedAt(),
              reservation.confirmedBy(),
              reservation.createdAt(),
              reservation.version() + 1));
      return 1;
    }

    @Override
    public int markExpired(String reservationId, Instant dbNow) {
      Reservation reservation = byId.get(reservationId);
      if (reservation == null) return 0;
      if (reservation.status() != ReservationStatus.PENDING_CONFIRMATION) return 0;
      if (reservation.expiresAt() != null && dbNow.isBefore(reservation.expiresAt())) return 0;
      byId.put(
          reservationId,
          new Reservation(
              reservation.reservationId(),
              reservation.customerId(),
              reservation.packageVersionRef(),
              reservation.source(),
              ReservationStatus.EXPIRED,
              reservation.expiresAt(),
              null,
              reservation.scheduleRevision(),
              null,
              null,
              reservation.createdAt(),
              reservation.version() + 1));
      return 1;
    }

    @Override
    public int reschedule(
        String reservationId,
        int expectedVersion,
        String slotId,
        int scheduleRevision,
        String actorId,
        Instant now) {
      Reservation reservation = byId.get(reservationId);
      if (reservation == null || reservation.version() != expectedVersion) return 0;
      byId.put(
          reservationId,
          new Reservation(
              reservation.reservationId(),
              reservation.customerId(),
              reservation.packageVersionRef(),
              reservation.source(),
              ReservationStatus.CONFIRMED,
              reservation.expiresAt(),
              slotId,
              scheduleRevision,
              reservation.confirmedAt(),
              reservation.confirmedBy(),
              reservation.createdAt(),
              reservation.version() + 1));
      return 1;
    }
  }

  private static final class FakeOrders implements OrderRepository {
    private final Map<String, Order> byId = new LinkedHashMap<>();
    private final Map<String, OrderSnapshot> snapshots = new LinkedHashMap<>();
    private final Map<String, List<OrderRevision>> revisions = new LinkedHashMap<>();
    private final AtomicInteger revisionNo = new AtomicInteger(1);

    List<Order> all() {
      return new ArrayList<>(byId.values());
    }

    List<OrderRevision> revisions(String orderId) {
      return revisions.getOrDefault(orderId, List.of());
    }

    OrderSnapshot snapshotOf(Order order) {
      return snapshots.get(order.snapshotId());
    }

    @Override
    public Optional<Order> find(String orderId) {
      return Optional.ofNullable(byId.get(orderId));
    }

    @Override
    public Optional<Order> findByReservation(String reservationId) {
      return byId.values().stream()
          .filter(order -> order.sourceReservationId().equals(reservationId))
          .findFirst();
    }

    @Override
    public Optional<OrderSnapshot> findSnapshot(String snapshotId) {
      return Optional.ofNullable(snapshots.get(snapshotId));
    }

    @Override
    public Order create(
        String orderId,
        String sourceReservationId,
        String customerId,
        OrderSnapshot snapshot,
        String commandId,
        String actorId,
        Instant now) {
      snapshots.put(snapshot.snapshotId(), snapshot);
      Order order =
          new Order(
              orderId,
              sourceReservationId,
              customerId,
              OrderLifecycle.ACTIVE,
              cn.zhinan.adminbooking.domain.BookingModels.FulfilmentStatus.UNSCHEDULED,
              snapshot.snapshotId(),
              1,
              now,
              1);
      byId.put(orderId, order);
      return order;
    }

    @Override
    public List<OrderRevision> listRevisions(String orderId) {
      return revisions.getOrDefault(orderId, List.of());
    }

    @Override
    public OrderRevision appendRevision(
        String orderId,
        int expectedRevision,
        String commandId,
        String actorId,
        String changedFields,
        String reasonCode,
        Instant now) {
      OrderRevision revision =
          new OrderRevision(
              revisionNo.getAndIncrement(), commandId, actorId, changedFields, reasonCode, now);
      revisions.computeIfAbsent(orderId, key -> new ArrayList<>()).add(revision);
      Order order = byId.get(orderId);
      byId.put(
          orderId,
          new Order(
              order.orderId(),
              order.sourceReservationId(),
              order.customerId(),
              order.lifecycle(),
              order.fulfilment(),
              order.snapshotId(),
              order.currentRevision() + 1,
              order.createdAt(),
              order.version() + 1));
      return revision;
    }

    @Override
    public int markCancelled(
        String orderId, int expectedRevision, String commandId, String actorId, Instant now) {
      Order order = byId.get(orderId);
      if (order == null || order.currentRevision() != expectedRevision) return 0;
      byId.put(
          orderId,
          new Order(
              order.orderId(),
              order.sourceReservationId(),
              order.customerId(),
              OrderLifecycle.CANCELLED,
              order.fulfilment(),
              order.snapshotId(),
              order.currentRevision(),
              order.createdAt(),
              order.version() + 1));
      return 1;
    }
  }

  private static final class FakeQueries implements BookingQueries {
    @Override
    public cn.zhinan.adminbooking.domain.BookingModels.ReservationView reservationView(
        String reservationId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public cn.zhinan.adminbooking.domain.BookingModels.OrderView orderView(String orderId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Reservation> pendingReservations(int limit) {
      return List.of();
    }

    @Override
    public List<ServiceSlot> openSlots(Instant from, Instant to, int limit) {
      return List.of();
    }

    @Override
    public ConfirmationResult lastConfirmation(String commandId) {
      return null;
    }
  }
}
