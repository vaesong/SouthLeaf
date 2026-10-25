package cn.zhinan.adminbooking.application;

import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.BookingFailure;
import cn.zhinan.adminbooking.domain.BookingModels;
import cn.zhinan.adminbooking.domain.BookingModels.CancellationResult;
import cn.zhinan.adminbooking.domain.BookingModels.ConfirmationResult;
import cn.zhinan.adminbooking.domain.BookingModels.ContactPoint;
import cn.zhinan.adminbooking.domain.BookingModels.Customer;
import cn.zhinan.adminbooking.domain.BookingModels.CustomerLinkDecision;
import cn.zhinan.adminbooking.domain.BookingModels.CustomerLinkRequest;
import cn.zhinan.adminbooking.domain.BookingModels.ExternalIdentity;
import cn.zhinan.adminbooking.domain.BookingModels.Order;
import cn.zhinan.adminbooking.domain.BookingModels.OrderRevision;
import cn.zhinan.adminbooking.domain.BookingModels.OrderSnapshot;
import cn.zhinan.adminbooking.domain.BookingModels.OrderView;
import cn.zhinan.adminbooking.domain.BookingModels.PackageFact;
import cn.zhinan.adminbooking.domain.BookingModels.RejectionResult;
import cn.zhinan.adminbooking.domain.BookingModels.RescheduleResult;
import cn.zhinan.adminbooking.domain.BookingModels.Reservation;
import cn.zhinan.adminbooking.domain.BookingModels.ReservationCreated;
import cn.zhinan.adminbooking.domain.BookingModels.ReservationView;
import cn.zhinan.adminbooking.domain.BookingModels.ServiceSlot;
import cn.zhinan.adminbooking.domain.BookingModels.SlotAssignment;
import cn.zhinan.adminbooking.domain.BookingRules;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * S4/S5 use cases (D-304, D-305, D-306). The application owns ordering and transaction boundaries;
 * capability checks happen in the runtime adapter before any call. Every write command is
 * idempotent under {@code actor + operation + clientKey} and never produces a second business fact
 * for a replayed key.
 */
public final class BookingApplication {
  private static final String OP_CREATE_RESERVATION = "CREATE_RESERVATION";
  private static final String OP_CONFIRM_RESERVATION = "CONFIRM_RESERVATION";
  private static final String OP_CANCEL_RESERVATION = "CANCEL_RESERVATION";
  private static final String OP_REJECT_RESERVATION = "REJECT_RESERVATION";
  private static final String OP_RESCHEDULE_RESERVATION = "RESCHEDULE_RESERVATION";
  private static final String OP_CREATE_CUSTOMER = "CREATE_CUSTOMER";
  private static final String OP_ATTACH_EXTERNAL_IDENTITY = "ATTACH_EXTERNAL_IDENTITY";
  private static final String OP_ADD_CONTACT_POINT = "ADD_CONTACT_POINT";
  private static final String OP_CREATE_LINK_REQUEST = "CREATE_LINK_REQUEST";
  private static final String OP_DECIDE_LINK_REQUEST = "DECIDE_LINK_REQUEST";

  /** Reason code the JDBC ledger uses when the same command scope is written twice. */
  private static final String IDEMPOTENCY_SCOPE_CONFLICT = "IDEMPOTENCY_SCOPE_CONFLICT";

  /**
   * How often a command is replayed after losing a write race. A loser never creates a second
   * business fact: it re-reads the durable facts and returns the answer of the winner.
   */
  private static final int MAX_CONCURRENT_RETRIES = 3;

  private final CustomerRepository customers;
  private final ScheduleRepository schedule;
  private final ReservationRepository reservations;
  private final OrderRepository orders;
  private final PackageCatalog packages;
  private final CommandLedger commands;
  private final BookingAudit audit;
  private final NotificationOutbox outbox;
  private final TransactionRunner transactions;
  private final Clock clock;

  public BookingApplication(
      CustomerRepository customers,
      ScheduleRepository schedule,
      ReservationRepository reservations,
      OrderRepository orders,
      PackageCatalog packages,
      CommandLedger commands,
      BookingAudit audit,
      NotificationOutbox outbox,
      TransactionRunner transactions,
      Clock clock) {
    this.customers = customers;
    this.schedule = schedule;
    this.reservations = reservations;
    this.orders = orders;
    this.packages = packages;
    this.commands = commands;
    this.audit = audit;
    this.outbox = outbox;
    this.transactions = transactions;
    this.clock = clock;
  }

  // ------------------------------------------------------------------ S4 customer

  public Customer createCustomer(
      String actorId, String idempotencyKey, String displayName, String language) {
    BookingRules.idempotencyKey(idempotencyKey);
    BookingRules.displayName(displayName);
    String digest = BookingRules.requestDigest(displayName, language == null ? "" : language);
    return transactions.inTransaction(
        () -> {
          Optional<StoredCommand> stored =
              commands.find(actorId, OP_CREATE_CUSTOMER, idempotencyKey);
          if (stored.isPresent()) {
            if (!stored.get().requestSha256().equals(digest))
              throw new BookingException(
                  BookingFailure.IDEMPOTENCY_CONFLICT, "IDEMPOTENCY_KEY_REUSE");
            return customers
                .find(stored.get().resultId())
                .orElseThrow(() -> new BookingException(BookingFailure.STORAGE_UNAVAILABLE));
          }
          String customerId = BookingRules.newId();
          Customer created = customers.create(customerId, displayName, language, clock.instant());
          recordCommand(
              actorId, OP_CREATE_CUSTOMER, idempotencyKey, digest, customerId, clock.instant());
          audit.record(
              actorId,
              customerId,
              "CUSTOMER",
              customerId,
              "CREATE",
              "CUSTOMER_CREATED",
              clock.instant());
          return created;
        });
  }

  public Customer findCustomer(String customerId) {
    return customers
        .find(BookingRules.uuid(customerId))
        .orElseThrow(() -> new BookingException(BookingFailure.NOT_FOUND, "CUSTOMER_NOT_FOUND"));
  }

  /**
   * Attaches a previously verified provider identity using only its one-way fingerprint. The raw
   * provider subject is intentionally not accepted by this application API.
   */
  public ExternalIdentity attachExternalIdentity(
      String actorId,
      String idempotencyKey,
      String customerId,
      String provider,
      String appScope,
      String subjectFingerprint,
      Instant verifiedAt) {
    BookingRules.idempotencyKey(idempotencyKey);
    BookingRules.uuid(customerId);
    BookingRules.identityLabel(provider, 32, "IDENTITY_PROVIDER_INVALID");
    BookingRules.identityLabel(appScope, 32, "IDENTITY_SCOPE_INVALID");
    BookingRules.subjectFingerprint(subjectFingerprint);
    Instant now = clock.instant();
    if (verifiedAt == null || verifiedAt.isAfter(now))
      throw new BookingException(
          BookingFailure.INVALID_REQUEST, "IDENTITY_VERIFICATION_TIME_INVALID");
    String digest =
        BookingRules.requestDigest(
            customerId,
            provider,
            appScope,
            subjectFingerprint,
            verifiedAt == null ? "" : verifiedAt.toString());
    return transactions.inTransaction(
        () -> {
          Optional<StoredCommand> stored =
              commands.find(actorId, OP_ATTACH_EXTERNAL_IDENTITY, idempotencyKey);
          if (stored.isPresent()) {
            if (!stored.get().requestSha256().equals(digest))
              throw new BookingException(
                  BookingFailure.IDEMPOTENCY_CONFLICT, "IDEMPOTENCY_KEY_REUSE");
            return customers
                .findExternalIdentity(provider, appScope, subjectFingerprint)
                .filter(identity -> identity.customerId().equals(customerId))
                .orElseThrow(() -> new BookingException(BookingFailure.STORAGE_UNAVAILABLE));
          }
          customers
              .find(customerId)
              .orElseThrow(
                  () -> new BookingException(BookingFailure.NOT_FOUND, "CUSTOMER_NOT_FOUND"));
          Optional<ExternalIdentity> existing =
              customers.findExternalIdentity(provider, appScope, subjectFingerprint);
          if (existing.isPresent()) {
            if (!existing.get().customerId().equals(customerId))
              throw new BookingException(
                  BookingFailure.CUSTOMER_LINK_EXISTS, "IDENTITY_ALREADY_LINKED");
            recordCommand(
                actorId, OP_ATTACH_EXTERNAL_IDENTITY, idempotencyKey, digest, customerId, now);
            return existing.get();
          }
          ExternalIdentity created;
          try {
            created =
                customers.createExternalIdentity(
                    customerId, provider, appScope, subjectFingerprint, verifiedAt, now);
          } catch (BookingException conflict) {
            if ("EXTERNAL_IDENTITY_CONFLICT".equals(conflict.reason()))
              throw new BookingException(
                  BookingFailure.CUSTOMER_LINK_EXISTS, "IDENTITY_ALREADY_LINKED");
            throw conflict;
          }
          recordCommand(
              actorId, OP_ATTACH_EXTERNAL_IDENTITY, idempotencyKey, digest, customerId, now);
          audit.record(
              actorId,
              idempotencyKey,
              "CUSTOMER",
              customerId,
              "ATTACH_IDENTITY",
              "EXTERNAL_IDENTITY_ATTACHED",
              now);
          return created;
        });
  }

  /**
   * Stores an already-encrypted contact value and a redacted display value. Plaintext contact data
   * is intentionally not accepted by this capability boundary or persisted in the database.
   */
  public ContactPoint addContactPoint(
      String actorId,
      String idempotencyKey,
      String customerId,
      String type,
      byte[] encryptedValue,
      String maskedValue,
      String verificationStatus) {
    BookingRules.idempotencyKey(idempotencyKey);
    BookingRules.uuid(customerId);
    BookingRules.contactPointType(type);
    byte[] cipher = BookingRules.encryptedContactValue(encryptedValue);
    BookingRules.maskedContactValue(maskedValue);
    BookingRules.verificationStatus(verificationStatus);
    String digest =
        BookingRules.requestDigest(
            customerId,
            type,
            BookingRules.encryptedValueDigest(cipher),
            maskedValue,
            verificationStatus);
    Instant now = clock.instant();
    try {
      return transactions.inTransaction(
          () -> {
            Optional<StoredCommand> stored =
                commands.find(actorId, OP_ADD_CONTACT_POINT, idempotencyKey);
            if (stored.isPresent()) {
              if (!stored.get().requestSha256().equals(digest))
                throw new BookingException(
                    BookingFailure.IDEMPOTENCY_CONFLICT, "IDEMPOTENCY_KEY_REUSE");
              return customers
                  .findContactPoint(stored.get().resultId())
                  .orElseThrow(() -> new BookingException(BookingFailure.STORAGE_UNAVAILABLE));
            }
            customers
                .find(customerId)
                .orElseThrow(
                    () -> new BookingException(BookingFailure.NOT_FOUND, "CUSTOMER_NOT_FOUND"));
            String contactPointId = BookingRules.newId();
            ContactPoint created =
                customers.createContactPoint(
                    contactPointId, customerId, type, cipher, maskedValue, verificationStatus, now);
            recordCommand(
                actorId, OP_ADD_CONTACT_POINT, idempotencyKey, digest, contactPointId, now);
            audit.record(
                actorId,
                idempotencyKey,
                "CUSTOMER",
                customerId,
                "ADD_CONTACT",
                "CONTACT_POINT_ADDED",
                now);
            return created;
          });
    } finally {
      java.util.Arrays.fill(cipher, (byte) 0);
    }
  }

  public List<ContactPoint> contactPoints(String customerId) {
    BookingRules.uuid(customerId);
    customers
        .find(customerId)
        .orElseThrow(() -> new BookingException(BookingFailure.NOT_FOUND, "CUSTOMER_NOT_FOUND"));
    return customers.listContactPoints(customerId);
  }

  /**
   * Requests a link between two subjects. The request never grants access; only an approved
   * decision does. Automatic merging by phone, name or provider subject is not implemented (D-304
   * §5, Q05).
   */
  public CustomerLinkRequest requestCustomerLink(
      String actorId,
      String requesterCustomerId,
      String candidateRef,
      String method,
      Duration lifetime) {
    BookingRules.uuid(requesterCustomerId);
    BookingRules.ref(candidateRef);
    if (method == null || method.isBlank() || method.length() > BookingRules.MAX_REASON_CODE)
      throw new BookingException(BookingFailure.INVALID_REQUEST, "LINK_METHOD_INVALID");
    Instant now = clock.instant();
    Instant expiresAt = BookingRules.expiresAt(now, lifetime);
    return transactions.inTransaction(
        () -> {
          String linkRequestId = BookingRules.newId();
          CustomerLinkRequest request =
              customers.createLinkRequest(
                  linkRequestId, requesterCustomerId, candidateRef, method, expiresAt, now);
          audit.record(
              actorId,
              linkRequestId,
              "CUSTOMER_LINK_REQUEST",
              linkRequestId,
              "REQUEST",
              "LINK_REQUESTED",
              now);
          return request;
        });
  }

  public CustomerLinkDecision decideCustomerLink(
      String actorId, String linkRequestId, boolean approve) {
    BookingRules.uuid(linkRequestId);
    Instant now = clock.instant();
    return transactions.inTransaction(
        () -> {
          CustomerLinkRequest request =
              customers
                  .findLinkRequest(linkRequestId)
                  .orElseThrow(
                      () ->
                          new BookingException(BookingFailure.NOT_FOUND, "LINK_REQUEST_NOT_FOUND"));
          if (!"PENDING".equals(request.status()))
            throw new BookingException(BookingFailure.CONFLICT, "LINK_REQUEST_ALREADY_DECIDED");
          if (request.expiresAt() != null && !now.isBefore(request.expiresAt()))
            throw new BookingException(BookingFailure.CONFLICT, "LINK_REQUEST_EXPIRED");
          CustomerLinkDecision decision =
              customers.decideLinkRequest(linkRequestId, approve, actorId, now);
          audit.record(
              actorId,
              linkRequestId,
              "CUSTOMER_LINK_REQUEST",
              linkRequestId,
              approve ? "APPROVE" : "REJECT",
              approve ? "LINK_APPROVED" : "LINK_REJECTED",
              now);
          return decision;
        });
  }

  // ------------------------------------------------------------------ S4 schedule

  public ServiceSlot createSlot(
      String actorId,
      Instant startAt,
      Instant endAt,
      String businessZone,
      String displayOffset,
      String ruleVersion,
      int capacity) {
    BookingRules.slotStart(startAt, endAt);
    BookingRules.zone(businessZone);
    BookingRules.capacity(capacity);
    BookingRules.ref(ruleVersion);
    Instant now = clock.instant();
    return transactions.inTransaction(
        () -> {
          String slotId = BookingRules.newId();
          ServiceSlot slot =
              schedule.createSlot(
                  slotId, startAt, endAt, businessZone, displayOffset, ruleVersion, capacity, now);
          audit.record(actorId, slotId, "SERVICE_SLOT", slotId, "CREATE", "SLOT_CREATED", now);
          return slot;
        });
  }

  public int closeSlot(String actorId, String slotId) {
    BookingRules.uuid(slotId);
    Instant now = clock.instant();
    return transactions.inTransaction(
        () -> {
          int closed = schedule.closeSlot(slotId, actorId, now);
          if (closed == 0) throw new BookingException(BookingFailure.NOT_FOUND, "SLOT_NOT_FOUND");
          audit.record(actorId, slotId, "SERVICE_SLOT", slotId, "CLOSE", "SLOT_CLOSED", now);
          return closed;
        });
  }

  public List<ServiceSlot> openSlots(Instant from, Instant to, int limit) {
    return schedule.listOpenSlots(from, to, limit);
  }

  // ------------------------------------------------------------------ S5 reservation

  /**
   * Creates a pending request. A pending request holds no hard slot and creates no order; it can
   * only be confirmed, rejected, expired or cancelled.
   */
  public ReservationCreated createReservation(
      String actorId,
      String customerId,
      String packageVersionRef,
      String source,
      String idempotencyKey,
      Duration lifetime) {
    BookingRules.idempotencyKey(idempotencyKey);
    BookingRules.uuid(customerId);
    BookingRules.ref(packageVersionRef);
    if (!"CUSTOMER_ONLINE".equals(source) && !"STAFF_OFFLINE".equals(source))
      throw new BookingException(BookingFailure.INVALID_REQUEST, "SOURCE_INVALID");
    PackageFact fact =
        packages
            .findPackageVersion(packageVersionRef)
            .orElseThrow(() -> new BookingException(BookingFailure.NOT_FOUND, "PACKAGE_NOT_FOUND"));
    Instant now = clock.instant();
    Instant expiresAt = BookingRules.expiresAt(now, lifetime);
    String digest =
        BookingRules.requestDigest(customerId, packageVersionRef, source, fact.priceVersion());
    return transactions.inTransaction(
        () -> {
          Optional<StoredCommand> stored =
              commands.find(actorId, OP_CREATE_RESERVATION, idempotencyKey);
          if (stored.isPresent()) {
            if (!stored.get().requestSha256().equals(digest))
              throw new BookingException(
                  BookingFailure.IDEMPOTENCY_CONFLICT, "IDEMPOTENCY_KEY_REUSE");
            Reservation replayed =
                reservations
                    .find(stored.get().resultId())
                    .orElseThrow(() -> new BookingException(BookingFailure.STORAGE_UNAVAILABLE));
            return new ReservationCreated(replayed.reservationId(), replayed.version(), true);
          }
          String reservationId = BookingRules.newId();
          ReservationCreated created =
              reservations.create(
                  reservationId,
                  customerId,
                  packageVersionRef,
                  source,
                  expiresAt,
                  actorId,
                  idempotencyKey,
                  digest,
                  now);
          recordCommand(actorId, OP_CREATE_RESERVATION, idempotencyKey, digest, reservationId, now);
          audit.record(
              actorId,
              reservationId,
              "RESERVATION",
              reservationId,
              "CREATE",
              "RESERVATION_CREATED",
              now);
          outbox.enqueue("RESERVATION_PENDING_CONFIRMATION", customerId, reservationId, now, now);
          return created;
        });
  }

  /**
   * Confirms a pending request and creates the order in one transaction (D-305 §7, D-306 §5.1). The
   * exclusive slot hold comes from the database unique constraint, not from a prior read.
   */
  public ConfirmationResult confirmReservation(
      String actorId,
      String reservationId,
      String slotId,
      int expectedRevision,
      String idempotencyKey) {
    BookingRules.idempotencyKey(idempotencyKey);
    BookingRules.uuid(reservationId);
    BookingRules.uuid(slotId);
    String digest =
        BookingRules.requestDigest(reservationId, slotId, String.valueOf(expectedRevision));
    Instant now = clock.instant();
    return withConcurrencyRetry(
        () -> {
          try {
            return transactions.inTransaction(
                () -> {
                  Optional<StoredCommand> stored =
                      commands.find(actorId, OP_CONFIRM_RESERVATION, idempotencyKey);
                  if (stored.isPresent()) {
                    if (!stored.get().requestSha256().equals(digest))
                      throw new BookingException(
                          BookingFailure.IDEMPOTENCY_CONFLICT, "IDEMPOTENCY_KEY_REUSE");
                    return replayConfirmation(stored.get().resultId(), now);
                  }
                  Reservation reservation =
                      reservations
                          .find(reservationId)
                          .orElseThrow(
                              () ->
                                  new BookingException(
                                      BookingFailure.NOT_FOUND, "RESERVATION_NOT_FOUND"));
                  if (reservation.version() != expectedRevision)
                    throw new BookingException(
                        BookingFailure.VERSION_CONFLICT, "RESERVATION_REVISION_MISMATCH");
                  BookingRules.assertConfirmable(
                      reservation.status(), reservation.expiresAt(), now);

                  PackageFact fact =
                      packages
                          .findPackageVersion(reservation.packageVersionRef())
                          .orElseThrow(
                              () ->
                                  new BookingException(
                                      BookingFailure.NOT_FOUND, "PACKAGE_NOT_FOUND"));
                  // Locked: an occupancy is about to be written, so a concurrent close must
                  // either happen strictly before this check or strictly after this commit.
                  ServiceSlot slot =
                      schedule
                          .findSlotForUpdate(slotId)
                          .orElseThrow(
                              () ->
                                  new BookingException(BookingFailure.NOT_FOUND, "SLOT_NOT_FOUND"));
                  Optional<String> occupiedBy =
                      schedule.findOccupancy(slotId).map(occupancy -> occupancy.reservationId());
                  BookingRules.assertSlotUsable(slot, occupiedBy.orElse(null), reservationId);

                  // The unique slot occupancy is the last line of defence against a concurrent
                  // winner.
                  try {
                    schedule.occupy(slotId, reservationId, now);
                  } catch (BookingException race) {
                    if (!"SLOT_OCCUPANCY_CONFLICT".equals(race.reason())) throw race;
                    throw new BookingException(BookingFailure.SLOT_UNAVAILABLE, "SLOT_UNAVAILABLE");
                  }

                  int updated =
                      reservations.markConfirmed(
                          reservationId, expectedRevision, slotId, slot.revision(), actorId, now);
                  if (updated == 0)
                    throw new BookingException(
                        BookingFailure.VERSION_CONFLICT, "RESERVATION_REVISION_MISMATCH");

                  String orderId = BookingRules.newId();
                  String snapshotId = BookingRules.newId();
                  String commandId = BookingRules.newId();
                  OrderSnapshot snapshot =
                      new OrderSnapshot(
                          snapshotId,
                          orderId,
                          fact.packageVersionRef(),
                          fact.displayName(),
                          fact.currencyCode(),
                          fact.amountMinorUnits(),
                          fact.priceVersion(),
                          slotId,
                          slot.startAt(),
                          slot.endAt(),
                          slot.businessZone(),
                          slot.displayOffset(),
                          slot.revision(),
                          reservation.customerId(),
                          reservation.source(),
                          now);
                  Order order =
                      orders.create(
                          orderId,
                          reservationId,
                          reservation.customerId(),
                          snapshot,
                          commandId,
                          actorId,
                          now);

                  audit.record(
                      actorId,
                      commandId,
                      "RESERVATION",
                      reservationId,
                      "CONFIRM",
                      "RESERVATION_CONFIRMED",
                      now);
                  outbox.enqueue(
                      "RESERVATION_CONFIRMED", reservation.customerId(), reservationId, now, now);
                  recordCommand(
                      actorId,
                      OP_CONFIRM_RESERVATION,
                      idempotencyKey,
                      digest,
                      order.orderId(),
                      now);

                  return new ConfirmationResult(
                      commandId,
                      reservationId,
                      order.orderId(),
                      order.orderId(),
                      slotId,
                      order.currentRevision(),
                      now,
                      false);
                });
          } catch (BookingException race) {
            if (race.failure() != BookingFailure.SLOT_UNAVAILABLE) throw race;
            Optional<StoredCommand> committed =
                transactions.inTransaction(
                    () -> commands.find(actorId, OP_CONFIRM_RESERVATION, idempotencyKey));
            if (committed.isEmpty()) throw race;
            if (!committed.get().requestSha256().equals(digest))
              throw new BookingException(
                  BookingFailure.IDEMPOTENCY_CONFLICT, "IDEMPOTENCY_KEY_REUSE");
            return replayConfirmation(committed.get().resultId(), clock.instant());
          }
        });
  }

  /**
   * A replayed confirmation rebuilds the same answer from the durable facts instead of running the
   * command again, so no second order and no second occupancy can appear.
   */
  /**
   * Runs a command and replays it after losing a write race.
   *
   * <p>Two writers can reach the same slot at the same time. The loser does not decide the outcome
   * by failing: it runs the command again, and the second run either finds the durable fact of the
   * winner and returns the same answer, or meets the constraint and reports the conflict. The
   * number of retries is bounded, so a permanent conflict still surfaces.
   */
  private <T> T withConcurrencyRetry(java.util.function.Supplier<T> work) {
    BookingException last = null;
    for (int attempt = 0; attempt < MAX_CONCURRENT_RETRIES; attempt++) {
      try {
        return work.get();
      } catch (BookingException failure) {
        if (failure.failure() != BookingFailure.VERSION_CONFLICT) throw failure;
        last = failure;
      }
    }
    throw last;
  }

  /**
   * Records the command in the idempotency ledger. A duplicate scope means another writer won the
   * race for the same key, which is an idempotency conflict rather than a storage failure.
   */
  private void recordCommand(
      String actorId,
      String operation,
      String idempotencyKey,
      String requestSha256,
      String resultId,
      Instant now) {
    try {
      commands.record(actorId, operation, idempotencyKey, requestSha256, resultId, now);
    } catch (BookingException failure) {
      if (IDEMPOTENCY_SCOPE_CONFLICT.equals(failure.reason()))
        throw new BookingException(BookingFailure.IDEMPOTENCY_CONFLICT, "IDEMPOTENCY_KEY_REUSE");
      throw failure;
    }
  }

  private ConfirmationResult replayConfirmation(String orderId, Instant now) {
    Order order =
        orders
            .find(orderId)
            .orElseThrow(() -> new BookingException(BookingFailure.STORAGE_UNAVAILABLE));
    Reservation reservation =
        reservations
            .find(order.sourceReservationId())
            .orElseThrow(() -> new BookingException(BookingFailure.STORAGE_UNAVAILABLE));
    return new ConfirmationResult(
        orderId,
        reservation.reservationId(),
        order.orderId(),
        order.orderId(),
        reservation.slotId(),
        order.currentRevision(),
        order.createdAt(),
        true);
  }

  /**
   * Cancels a confirmed reservation. The occupancy is released and an order revision is appended in
   * the same transaction. Stage two has no refund semantics.
   */
  public CancellationResult cancelReservation(
      String actorId,
      String reservationId,
      int expectedRevision,
      String reasonCode,
      String idempotencyKey) {
    BookingRules.idempotencyKey(idempotencyKey);
    BookingRules.uuid(reservationId);
    BookingRules.reasonCode(reasonCode);
    String digest =
        BookingRules.requestDigest(reservationId, String.valueOf(expectedRevision), reasonCode);
    Instant now = clock.instant();
    return transactions.inTransaction(
        () -> {
          Optional<StoredCommand> stored =
              commands.find(actorId, OP_CANCEL_RESERVATION, idempotencyKey);
          if (stored.isPresent()) {
            if (!stored.get().requestSha256().equals(digest))
              throw new BookingException(
                  BookingFailure.IDEMPOTENCY_CONFLICT, "IDEMPOTENCY_KEY_REUSE");
            Reservation reservation =
                reservations
                    .find(reservationId)
                    .orElseThrow(
                        () ->
                            new BookingException(
                                BookingFailure.NOT_FOUND, "RESERVATION_NOT_FOUND"));
            Order order =
                orders
                    .findByReservation(reservationId)
                    .orElseThrow(
                        () -> new BookingException(BookingFailure.NOT_FOUND, "ORDER_NOT_FOUND"));
            return new CancellationResult(
                stored.get().resultId(),
                reservationId,
                order.orderId(),
                order.currentRevision(),
                now,
                true);
          }
          Reservation reservation =
              reservations
                  .find(reservationId)
                  .orElseThrow(
                      () ->
                          new BookingException(BookingFailure.NOT_FOUND, "RESERVATION_NOT_FOUND"));
          if (reservation.version() != expectedRevision)
            throw new BookingException(
                BookingFailure.VERSION_CONFLICT, "RESERVATION_REVISION_MISMATCH");
          BookingRules.assertCancellable(reservation.status());

          Order order =
              orders
                  .findByReservation(reservationId)
                  .orElseThrow(
                      () -> new BookingException(BookingFailure.NOT_FOUND, "ORDER_NOT_FOUND"));
          BookingRules.assertOrderCancellable(order.lifecycle());

          int updated = reservations.markCancelled(reservationId, expectedRevision, actorId, now);
          if (updated == 0)
            throw new BookingException(
                BookingFailure.VERSION_CONFLICT, "RESERVATION_REVISION_MISMATCH");
          if (reservation.slotId() != null) schedule.release(reservation.slotId(), now);
          orders.markCancelled(order.orderId(), order.currentRevision(), "CANCEL", actorId, now);
          orders.appendRevision(
              order.orderId(),
              order.currentRevision(),
              "CANCEL",
              actorId,
              "lifecycle:CANCELLED",
              reasonCode,
              now);

          String commandId = BookingRules.newId();
          recordCommand(actorId, OP_CANCEL_RESERVATION, idempotencyKey, digest, commandId, now);
          audit.record(actorId, commandId, "RESERVATION", reservationId, "CANCEL", reasonCode, now);
          outbox.enqueue(
              "RESERVATION_CANCELLED", reservation.customerId(), reservationId, now, now);
          return new CancellationResult(
              commandId, reservationId, order.orderId(), order.currentRevision() + 1, now, false);
        });
  }

  /**
   * Refuses a pending request. Nothing is occupied and no order exists yet, so the decision is the
   * terminal status plus its audit record and notification intent: a rejection is a recorded
   * answer, never a silent delete, and it can never hold a slot.
   */
  public RejectionResult rejectReservation(
      String actorId,
      String reservationId,
      int expectedRevision,
      String reasonCode,
      String idempotencyKey) {
    BookingRules.idempotencyKey(idempotencyKey);
    BookingRules.uuid(reservationId);
    BookingRules.reasonCode(reasonCode);
    String digest =
        BookingRules.requestDigest(reservationId, String.valueOf(expectedRevision), reasonCode);
    Instant now = clock.instant();
    return transactions.inTransaction(
        () -> {
          Optional<StoredCommand> stored =
              commands.find(actorId, OP_REJECT_RESERVATION, idempotencyKey);
          if (stored.isPresent()) {
            if (!stored.get().requestSha256().equals(digest))
              throw new BookingException(
                  BookingFailure.IDEMPOTENCY_CONFLICT, "IDEMPOTENCY_KEY_REUSE");
            return new RejectionResult(stored.get().resultId(), reservationId, now, true);
          }
          Reservation reservation =
              reservations
                  .find(reservationId)
                  .orElseThrow(
                      () ->
                          new BookingException(BookingFailure.NOT_FOUND, "RESERVATION_NOT_FOUND"));
          if (reservation.version() != expectedRevision)
            throw new BookingException(
                BookingFailure.VERSION_CONFLICT, "RESERVATION_REVISION_MISMATCH");
          BookingRules.assertRejectable(reservation.status(), reservation.expiresAt(), now);

          int updated =
              reservations.markRejected(reservationId, expectedRevision, reasonCode, actorId, now);
          if (updated == 0)
            throw new BookingException(
                BookingFailure.VERSION_CONFLICT, "RESERVATION_REVISION_MISMATCH");

          String commandId = BookingRules.newId();
          recordCommand(actorId, OP_REJECT_RESERVATION, idempotencyKey, digest, commandId, now);
          audit.record(actorId, commandId, "RESERVATION", reservationId, "REJECT", reasonCode, now);
          outbox.enqueue("RESERVATION_REJECTED", reservation.customerId(), reservationId, now, now);
          return new RejectionResult(commandId, reservationId, now, false);
        });
  }

  /**
   * Moves a confirmed reservation to another slot. The new occupancy is established before the old
   * one is released and a failure keeps the original slot intact.
   */
  public RescheduleResult rescheduleReservation(
      String actorId,
      String reservationId,
      String newSlotId,
      int expectedRevision,
      String reasonCode,
      String idempotencyKey) {
    BookingRules.idempotencyKey(idempotencyKey);
    BookingRules.uuid(reservationId);
    BookingRules.uuid(newSlotId);
    BookingRules.reasonCode(reasonCode);
    String digest =
        BookingRules.requestDigest(
            reservationId, newSlotId, String.valueOf(expectedRevision), reasonCode);
    Instant now = clock.instant();
    return transactions.inTransaction(
        () -> {
          Optional<StoredCommand> stored =
              commands.find(actorId, OP_RESCHEDULE_RESERVATION, idempotencyKey);
          if (stored.isPresent()) {
            if (!stored.get().requestSha256().equals(digest))
              throw new BookingException(
                  BookingFailure.IDEMPOTENCY_CONFLICT, "IDEMPOTENCY_KEY_REUSE");
            Reservation reservation =
                reservations
                    .find(reservationId)
                    .orElseThrow(
                        () ->
                            new BookingException(
                                BookingFailure.NOT_FOUND, "RESERVATION_NOT_FOUND"));
            Order order =
                orders
                    .findByReservation(reservationId)
                    .orElseThrow(
                        () -> new BookingException(BookingFailure.NOT_FOUND, "ORDER_NOT_FOUND"));
            return new RescheduleResult(
                stored.get().resultId(),
                reservationId,
                order.orderId(),
                reservation.slotId(),
                reservation.slotId(),
                order.currentRevision(),
                now,
                true);
          }
          Reservation reservation =
              reservations
                  .find(reservationId)
                  .orElseThrow(
                      () ->
                          new BookingException(BookingFailure.NOT_FOUND, "RESERVATION_NOT_FOUND"));
          if (reservation.version() != expectedRevision)
            throw new BookingException(
                BookingFailure.VERSION_CONFLICT, "RESERVATION_REVISION_MISMATCH");
          if (reservation.status() != BookingModels.ReservationStatus.CONFIRMED)
            throw new BookingException(BookingFailure.INVALID_REQUEST, "NOT_CONFIRMED");
          if (newSlotId.equals(reservation.slotId()))
            throw new BookingException(BookingFailure.INVALID_REQUEST, "SLOT_UNCHANGED");

          ServiceSlot newSlot =
              schedule
                  .findSlotForUpdate(newSlotId)
                  .orElseThrow(
                      () -> new BookingException(BookingFailure.NOT_FOUND, "SLOT_NOT_FOUND"));
          Optional<String> occupiedBy =
              schedule.findOccupancy(newSlotId).map(occupancy -> occupancy.reservationId());
          BookingRules.assertSlotUsable(newSlot, occupiedBy.orElse(null), reservationId);

          try {
            if (reservation.slotId() == null) {
              schedule.occupy(newSlotId, reservationId, now);
            } else {
              // Updating the occupancy primary key is one atomic row move. The target unique key is
              // claimed before the reservation changes; any later failure rolls the move back with
              // the transaction, leaving the original occupancy intact.
              schedule.moveOccupancy(reservation.slotId(), newSlotId, reservationId, now);
            }
          } catch (BookingException race) {
            if (!"SLOT_OCCUPANCY_CONFLICT".equals(race.reason())) throw race;
            throw new BookingException(BookingFailure.SLOT_UNAVAILABLE, "SLOT_UNAVAILABLE");
          }

          int updated =
              reservations.reschedule(
                  reservationId, expectedRevision, newSlotId, newSlot.revision(), actorId, now);
          if (updated == 0)
            throw new BookingException(
                BookingFailure.VERSION_CONFLICT, "RESERVATION_REVISION_MISMATCH");
          schedule.appendAssignment(
              new SlotAssignment(
                  BookingRules.newId(),
                  newSlotId,
                  reservationId,
                  reservation.slotId(),
                  now,
                  reasonCode,
                  newSlot.revision()));

          Order order =
              orders
                  .findByReservation(reservationId)
                  .orElseThrow(
                      () -> new BookingException(BookingFailure.NOT_FOUND, "ORDER_NOT_FOUND"));
          orders.appendRevision(
              order.orderId(),
              order.currentRevision(),
              "RESCHEDULE",
              actorId,
              "slotId:" + reservation.slotId() + "->" + newSlotId,
              reasonCode,
              now);

          String commandId = BookingRules.newId();
          recordCommand(actorId, OP_RESCHEDULE_RESERVATION, idempotencyKey, digest, commandId, now);
          audit.record(
              actorId, commandId, "RESERVATION", reservationId, "RESCHEDULE", reasonCode, now);
          outbox.enqueue(
              "RESERVATION_RESCHEDULED", reservation.customerId(), reservationId, now, now);
          return new RescheduleResult(
              commandId,
              reservationId,
              order.orderId(),
              reservation.slotId(),
              newSlotId,
              order.currentRevision() + 1,
              now,
              false);
        });
  }

  /**
   * Expires a pending request using a conditional update. Exactly one terminal transition can win
   * because the update is guarded by the current status and the database clock.
   */
  public boolean expireReservation(String reservationId, Instant dbNow) {
    BookingRules.uuid(reservationId);
    return transactions.inTransaction(
        () -> {
          int updated = reservations.markExpired(reservationId, dbNow);
          if (updated > 0) {
            audit.record(
                "SYSTEM",
                reservationId,
                "RESERVATION",
                reservationId,
                "EXPIRE",
                "RESERVATION_EXPIRED",
                dbNow);
            outbox.enqueue("RESERVATION_EXPIRED", reservationId, reservationId, dbNow, dbNow);
          }
          return updated == 1;
        });
  }

  public ReservationView reservationView(String reservationId) {
    BookingRules.uuid(reservationId);
    Reservation reservation =
        reservations
            .find(reservationId)
            .orElseThrow(
                () -> new BookingException(BookingFailure.NOT_FOUND, "RESERVATION_NOT_FOUND"));
    List<SlotAssignment> assignments = reservations.listAssignments(reservationId);
    String orderId = orders.findByReservation(reservationId).map(Order::orderId).orElse(null);
    return new ReservationView(reservation, assignments, orderId, null);
  }

  public OrderView orderView(String orderId) {
    BookingRules.uuid(orderId);
    Order order =
        orders
            .find(orderId)
            .orElseThrow(() -> new BookingException(BookingFailure.NOT_FOUND, "ORDER_NOT_FOUND"));
    OrderSnapshot snapshot =
        orders
            .findSnapshot(order.snapshotId())
            .orElseThrow(
                () -> new BookingException(BookingFailure.NOT_FOUND, "SNAPSHOT_NOT_FOUND"));
    List<OrderRevision> revisions = orders.listRevisions(orderId);
    String reservationStatus =
        orders.findByReservation(order.sourceReservationId()).isEmpty()
            ? null
            : reservations
                .find(order.sourceReservationId())
                .map(r -> r.status().name())
                .orElse(null);
    return new OrderView(order, snapshot, revisions, reservationStatus);
  }

  public List<Reservation> pendingReservations(int limit) {
    return reservations.listPending(limit);
  }

  public List<OrderRevision> orderRevisions(String orderId) {
    BookingRules.uuid(orderId);
    return orders.listRevisions(orderId);
  }
}
