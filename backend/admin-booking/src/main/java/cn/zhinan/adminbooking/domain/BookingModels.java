package cn.zhinan.adminbooking.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Application read/write models. No transport, JDBC or storage types. */
public final class BookingModels {
  private BookingModels() {}

  /** Reservation lifecycle. A pending reservation never holds a hard slot. */
  public enum ReservationStatus {
    PENDING_CONFIRMATION,
    CONFIRMED,
    REJECTED,
    EXPIRED,
    CANCELLED
  }

  /** Minimum order lifecycle. There is no payment, deposit, refund or delivery state. */
  public enum OrderLifecycle {
    ACTIVE,
    CANCELLED
  }

  /** Minimal stage-two fulfilment state (P02 S5). Not a complete fulfilment model. */
  public enum FulfilmentStatus {
    UNSCHEDULED,
    SCHEDULED,
    NEEDS_ATTENTION
  }

  /** Customer role inside one reservation or order. */
  public enum PartyRole {
    BOOKER,
    CONTACT,
    SUBJECT,
    PARTICIPANT,
    GUARDIAN
  }

  /** How a reservation entered the system. */
  public enum ReservationSource {
    CUSTOMER_ONLINE,
    STAFF_OFFLINE
  }

  /** Relationship roles between two customer subjects. */
  public enum RelationshipType {
    FAMILY,
    ASSISTANT,
    ORGANIZER,
    OTHER
  }

  public record Customer(
      String customerId,
      String status,
      String displayName,
      String language,
      Instant createdAt,
      int version) {}

  public record ExternalIdentity(
      String customerId,
      String provider,
      String appScope,
      String subjectFingerprint,
      String status,
      Instant verifiedAt) {}

  public record ContactPoint(
      String contactPointId,
      String customerId,
      String type,
      String maskedValue,
      String verificationStatus,
      Instant createdAt) {}

  public record CustomerRelationship(
      String relationshipId,
      String customerId,
      String relatedCustomerId,
      RelationshipType relationType,
      String purpose,
      String verificationStatus,
      Instant validFrom,
      Instant validUntil,
      boolean revoked) {}

  public record CustomerLinkRequest(
      String linkRequestId,
      String requesterCustomerId,
      String candidateRef,
      String method,
      String status,
      Instant expiresAt,
      Instant reviewedAt) {}

  /** A materialised bookable slot. Times are UTC instants; the zone is a business label. */
  public record ServiceSlot(
      String slotId,
      Instant startAt,
      Instant endAt,
      String businessZone,
      String displayOffset,
      String status,
      int capacity,
      String ruleVersion,
      int revision) {}

  public record Reservation(
      String reservationId,
      String customerId,
      String packageVersionRef,
      String source,
      ReservationStatus status,
      Instant expiresAt,
      String slotId,
      int scheduleRevision,
      Instant confirmedAt,
      String confirmedBy,
      Instant createdAt,
      int version) {}

  public record SlotOccupancy(
      String slotId, String reservationId, Instant assignedAt, int revision) {}

  public record SlotAssignment(
      String id,
      String slotId,
      String reservationId,
      String previousSlotId,
      Instant assignedAt,
      String reasonCode,
      int revision) {}

  /** Immutable commercial snapshot captured at confirmation time. */
  public record OrderSnapshot(
      String snapshotId,
      String orderId,
      String packageVersionRef,
      String packageDisplayName,
      String currencyCode,
      long amountMinorUnits,
      String priceVersion,
      String slotId,
      Instant startAt,
      Instant endAt,
      String businessZone,
      String displayOffset,
      int scheduleRevision,
      String customerId,
      String source,
      Instant capturedAt) {}

  public record OrderRevision(
      int revisionNo,
      String commandId,
      String actorRef,
      String changedFields,
      String reasonCode,
      Instant createdAt) {}

  public record Order(
      String orderId,
      String sourceReservationId,
      String customerId,
      OrderLifecycle lifecycle,
      FulfilmentStatus fulfilment,
      String snapshotId,
      int currentRevision,
      Instant createdAt,
      int version) {}

  public record OrderView(
      Order order,
      OrderSnapshot snapshot,
      List<OrderRevision> revisions,
      String reservationStatus) {}

  public record ReservationCreated(String reservationId, int version, boolean replayed) {}

  public record ReservationView(
      Reservation reservation,
      List<SlotAssignment> assignments,
      String orderId,
      String linkedCustomerStatus) {}

  /** Result of the atomic confirm-and-create-order command. */
  public record ConfirmationResult(
      String commandId,
      String reservationId,
      String orderId,
      String orderRef,
      String slotId,
      int orderRevision,
      Instant completedAt,
      boolean replayed) {}

  public record CancellationResult(
      String commandId,
      String reservationId,
      String orderId,
      int orderRevision,
      Instant completedAt,
      boolean replayed) {}

  public record RejectionResult(
      String commandId, String reservationId, Instant completedAt, boolean replayed) {}

  public record RescheduleResult(
      String commandId,
      String reservationId,
      String orderId,
      String previousSlotId,
      String slotId,
      int orderRevision,
      Instant completedAt,
      boolean replayed) {}

  /** Minimal package/business fact consumed from outside this module. */
  public record PackageFact(
      String packageVersionRef,
      String displayName,
      String currencyCode,
      long amountMinorUnits,
      String priceVersion) {}

  public record SlotAvailability(String slotId, Instant startAt, boolean available) {}

  public record CustomerLinkDecision(
      String linkRequestId, String status, Optional<String> approvedRelationshipId) {}
}
