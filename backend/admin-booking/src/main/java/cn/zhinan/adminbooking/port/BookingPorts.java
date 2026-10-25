package cn.zhinan.adminbooking.port;

import cn.zhinan.adminbooking.domain.BookingModels.ConfirmationResult;
import cn.zhinan.adminbooking.domain.BookingModels.ContactPoint;
import cn.zhinan.adminbooking.domain.BookingModels.Customer;
import cn.zhinan.adminbooking.domain.BookingModels.CustomerLinkDecision;
import cn.zhinan.adminbooking.domain.BookingModels.CustomerLinkRequest;
import cn.zhinan.adminbooking.domain.BookingModels.CustomerRelationship;
import cn.zhinan.adminbooking.domain.BookingModels.ExternalIdentity;
import cn.zhinan.adminbooking.domain.BookingModels.Order;
import cn.zhinan.adminbooking.domain.BookingModels.OrderRevision;
import cn.zhinan.adminbooking.domain.BookingModels.OrderSnapshot;
import cn.zhinan.adminbooking.domain.BookingModels.OrderView;
import cn.zhinan.adminbooking.domain.BookingModels.PackageFact;
import cn.zhinan.adminbooking.domain.BookingModels.Reservation;
import cn.zhinan.adminbooking.domain.BookingModels.ReservationCreated;
import cn.zhinan.adminbooking.domain.BookingModels.ReservationView;
import cn.zhinan.adminbooking.domain.BookingModels.ServiceSlot;
import cn.zhinan.adminbooking.domain.BookingModels.SlotAssignment;
import cn.zhinan.adminbooking.domain.BookingModels.SlotOccupancy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Ports owned by the booking application. Each adapter has exactly one database identity; the
 * reservation writer never touches order tables and the order writer never touches reservation
 * tables (D-306 §4).
 */
public final class BookingPorts {
  private BookingPorts() {}

  /** Identity and relationship facts. Reads never expose raw contact values. */
  public interface CustomerRepository {
    Optional<Customer> find(String customerId);

    Customer create(String customerId, String displayName, String language, Instant now);

    Optional<ExternalIdentity> findExternalIdentity(
        String provider, String appScope, String subjectFingerprint);

    ExternalIdentity createExternalIdentity(
        String customerId,
        String provider,
        String appScope,
        String subjectFingerprint,
        Instant verifiedAt,
        Instant now);

    Optional<ContactPoint> findContactPoint(String contactPointId);

    ContactPoint createContactPoint(
        String contactPointId,
        String customerId,
        String type,
        byte[] encryptedValue,
        String maskedValue,
        String verificationStatus,
        Instant now);

    List<ContactPoint> listContactPoints(String customerId);

    Optional<CustomerRelationship> findRelationship(String relationshipId);

    Optional<CustomerRelationship> findRelationship(String customerId, String relatedCustomerId);

    CustomerRelationship approveRelationship(
        String relationshipId,
        String customerId,
        String relatedCustomerId,
        String relationType,
        String purpose,
        Instant validFrom,
        Instant validUntil,
        String actorId,
        Instant now);

    Optional<CustomerLinkRequest> findLinkRequest(String linkRequestId);

    CustomerLinkRequest createLinkRequest(
        String linkRequestId,
        String requesterCustomerId,
        String candidateRef,
        String method,
        Instant expiresAt,
        Instant now);

    CustomerLinkDecision decideLinkRequest(
        String linkRequestId, boolean approve, String actorId, Instant now);

    List<CustomerRelationship> listRelationships(String customerId);
  }

  /** Materialised slots and their single current occupancy. */
  public interface ScheduleRepository {
    Optional<ServiceSlot> findSlot(String slotId);

    /**
     * The same slot, read with a row lock.
     *
     * <p>Any transaction that is about to write an occupancy must go through this method: a plain
     * read returns the pre-close snapshot, so a slot closed by a concurrent transaction would still
     * look OPEN and the confirmation would succeed on a slot that no longer exists for booking.
     */
    Optional<ServiceSlot> findSlotForUpdate(String slotId);

    List<ServiceSlot> listOpenSlots(Instant from, Instant to, int limit);

    ServiceSlot createSlot(
        String slotId,
        Instant startAt,
        Instant endAt,
        String businessZone,
        String displayOffset,
        String ruleVersion,
        int capacity,
        Instant now);

    /** Closes a slot for new confirmations. Existing confirmed reservations are untouched. */
    int closeSlot(String slotId, String actorId, Instant now);

    Optional<SlotOccupancy> findOccupancy(String slotId);

    SlotOccupancy occupy(String slotId, String reservationId, Instant now);

    /** Atomically moves the reservation's one exclusive occupancy to another slot. */
    SlotOccupancy moveOccupancy(
        String previousSlotId, String newSlotId, String reservationId, Instant now);

    void release(String slotId, Instant now);

    void appendAssignment(SlotAssignment assignment);
  }

  /** Reservation facts and their current state. */
  public interface ReservationRepository {
    Optional<Reservation> find(String reservationId);

    ReservationCreated create(
        String reservationId,
        String customerId,
        String packageVersionRef,
        String source,
        Instant expiresAt,
        String actorId,
        String idempotencyKey,
        String requestSha256,
        Instant now);

    List<Reservation> listPending(int limit);

    List<Reservation> listByCustomer(String customerId, int limit);

    List<SlotAssignment> listAssignments(String reservationId);

    /**
     * Conditional transition; returns the number of affected rows so the caller can detect a race.
     */
    int markConfirmed(
        String reservationId,
        int expectedVersion,
        String slotId,
        int scheduleRevision,
        String actorId,
        Instant now);

    int markRejected(
        String reservationId, int expectedVersion, String reasonCode, String actorId, Instant now);

    int markCancelled(String reservationId, int expectedVersion, String actorId, Instant now);

    /** Expiry is a conditional update so that exactly one terminal transition can win. */
    int markExpired(String reservationId, Instant dbNow);

    /** Moves a confirmed reservation to another slot while keeping the confirmed status. */
    int reschedule(
        String reservationId,
        int expectedVersion,
        String slotId,
        int scheduleRevision,
        String actorId,
        Instant now);
  }

  /** Order facts and their immutable snapshot plus append-only revisions. */
  public interface OrderRepository {
    Optional<Order> find(String orderId);

    Optional<Order> findByReservation(String reservationId);

    Optional<OrderSnapshot> findSnapshot(String snapshotId);

    Order create(
        String orderId,
        String sourceReservationId,
        String customerId,
        OrderSnapshot snapshot,
        String commandId,
        String actorId,
        Instant now);

    List<OrderRevision> listRevisions(String orderId);

    OrderRevision appendRevision(
        String orderId,
        int expectedRevision,
        String commandId,
        String actorId,
        String changedFields,
        String reasonCode,
        Instant now);

    int markCancelled(
        String orderId, int expectedRevision, String commandId, String actorId, Instant now);
  }

  /**
   * Business facts owned by another module. The booking slice consumes a package version by
   * reference and never joins the content tables directly.
   */
  public interface PackageCatalog {
    Optional<PackageFact> findPackageVersion(String packageVersionRef);
  }

  /**
   * Command idempotency. The scope is actor + operation + client key; a replayed key with a
   * different request digest is a conflict, never a second business fact.
   */
  public interface CommandLedger {
    Optional<StoredCommand> find(String actorId, String operation, String idempotencyKey);

    void record(
        String actorId,
        String operation,
        String idempotencyKey,
        String requestSha256,
        String resultId,
        Instant completedAt);
  }

  public record StoredCommand(String requestSha256, String resultId) {}

  /** Audit and notification outbox share the business transaction (D-106 §8). */
  public interface BookingAudit {
    void record(
        String actorId,
        String commandId,
        String objectType,
        String objectId,
        String action,
        String reasonCode,
        Instant now);
  }

  public interface NotificationOutbox {
    void enqueue(String intent, String recipientRef, String objectRef, Instant dueAt, Instant now);
  }

  /**
   * Notification delivery. The worker leases a due message, hands it to a transport and reports the
   * outcome. The transport may fail; the worker decides whether the failure is worth another try.
   */
  public interface NotificationDelivery {
    /**
     * Attempts one delivery.
     *
     * @return the outcome; a retryable failure must not be treated as a permanent one
     */
    DeliveryOutcome deliver(OutboxMessage message);
  }

  /**
   * A leased outbox message. The lease owner and the lease deadline are part of the message so a
   * worker can prove that it still holds the lease before it reports an outcome.
   */
  public record OutboxMessage(
      String messageId,
      String intent,
      String recipientRef,
      String objectRef,
      String dedupKey,
      Instant dueAt,
      String status,
      int attempts,
      Instant leaseUntil,
      String leaseOwner,
      String lastError) {

    /** A stable key a transport can use to drop a repeated delivery of the same business fact. */
    public String dedupKey() {
      if (dedupKey != null && !dedupKey.isBlank()) return dedupKey;
      return intent + ':' + objectRef;
    }
  }

  /**
   * The outcome of one delivery attempt.
   *
   * @param delivered whether the transport accepted the message
   * @param retryable whether another attempt may succeed
   * @param reasonCode a stable, non-sensitive reason
   */
  public record DeliveryOutcome(boolean delivered, boolean retryable, String reasonCode) {
    public static DeliveryOutcome success() {
      return new DeliveryOutcome(true, false, "DELIVERED");
    }

    public static DeliveryOutcome retryable(String reasonCode) {
      return new DeliveryOutcome(false, true, reasonCode);
    }

    public static DeliveryOutcome permanent(String reasonCode) {
      return new DeliveryOutcome(false, false, reasonCode);
    }
  }

  /**
   * The outbox worker side of the port: leasing, outcome reporting and the dead letter queue.
   *
   * <p>A lease is a claim on one message for a bounded time. A worker that dies mid delivery leaves
   * the lease behind; the next worker takes the message over once the lease expires.
   */
  public interface OutboxWorker {
    /**
     * Leases the messages that are due and not leased by somebody else.
     *
     * @param owner the worker identity that will hold the lease
     * @param limit the maximum number of messages to lease
     * @param now the current instant
     * @param leaseDuration how long the lease stays valid
     */
    List<OutboxMessage> lease(
        String owner, int limit, Instant now, java.time.Duration leaseDuration);

    /** Reports a successful delivery. The message leaves the pending queue. */
    void markDelivered(String messageId, String owner, Instant now);

    /**
     * Reports a failed delivery. A retryable failure schedules the next attempt; a permanent one or
     * an exhausted attempt budget moves the message to the dead letter state.
     */
    void markFailed(
        String messageId,
        String owner,
        String reasonCode,
        boolean retryable,
        Instant now,
        Instant retryAt);

    /** Moves a message to the dead letter state without attempting delivery again. */
    void markDead(String messageId, String owner, String reasonCode, Instant now);

    /** The messages that will never be delivered again without an operator decision. */
    List<OutboxMessage> deadLetters(int limit);

    /**
     * Requeues one failed message after an explicit operator decision. The attempt count is kept,
     * so each manual recovery grants at most one further attempt before it dead-letters again.
     *
     * @return true only when this call moved a FAILED row back to PENDING
     */
    boolean retryDeadLetter(String messageId, Instant now);

    /** The number of messages that are still waiting for delivery. */
    int pendingCount(Instant now);
  }

  /** Transactional facade: the confirm command must commit or roll back as one unit. */
  public interface TransactionRunner {
    <T> T inTransaction(java.util.function.Supplier<T> work);

    void inTransaction(Runnable work);
  }

  /** Read model assembled for the management UI. */
  public interface BookingQueries {
    ReservationView reservationView(String reservationId);

    OrderView orderView(String orderId);

    List<Reservation> pendingReservations(int limit);

    List<ServiceSlot> openSlots(Instant from, Instant to, int limit);

    ConfirmationResult lastConfirmation(String commandId);
  }
}
