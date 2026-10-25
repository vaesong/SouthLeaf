package cn.zhinan.adminbooking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Pure rule checks for the S4/S5 invariants (D-304, D-305, D-306). */
class BookingRulesTest {

  private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");

  @Test
  void pendingRequestsExpireAfterTheFortyEightHourBaseline() {
    Instant expires = BookingRules.expiresAt(NOW, null);
    assertThat(expires).isEqualTo(NOW.plus(Duration.ofHours(48)));
  }

  @Test
  void anExplicitLifetimeIsHonouredButBounded() {
    assertThat(BookingRules.expiresAt(NOW, Duration.ofHours(6)))
        .isEqualTo(NOW.plus(Duration.ofHours(6)));
    assertThatThrownBy(() -> BookingRules.expiresAt(NOW, Duration.ofDays(60)))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).failure())
                    .isEqualTo(BookingFailure.INVALID_REQUEST));
  }

  @Test
  void aPendingReservationCanBeConfirmedUntilItExpires() {
    BookingRules.assertConfirmable(
        BookingModels.ReservationStatus.PENDING_CONFIRMATION, NOW.plusSeconds(60), NOW);
  }

  @Test
  void anExpiredPendingReservationCanNoLongerBeConfirmed() {
    assertThatThrownBy(
            () ->
                BookingRules.assertConfirmable(
                    BookingModels.ReservationStatus.PENDING_CONFIRMATION, NOW.minusSeconds(1), NOW))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).failure())
                    .isEqualTo(BookingFailure.RESERVATION_EXPIRED));
  }

  @Test
  void terminalReservationsCannotBeConfirmed() {
    for (BookingModels.ReservationStatus status :
        new BookingModels.ReservationStatus[] {
          BookingModels.ReservationStatus.REJECTED,
          BookingModels.ReservationStatus.EXPIRED,
          BookingModels.ReservationStatus.CANCELLED
        }) {
      assertThatThrownBy(() -> BookingRules.assertConfirmable(status, NOW.plusSeconds(600), NOW))
          .isInstanceOf(BookingException.class);
    }
  }

  @Test
  void anAlreadyConfirmedReservationIsAConflictRatherThanANotPendingError() {
    assertThatThrownBy(
            () ->
                BookingRules.assertConfirmable(
                    BookingModels.ReservationStatus.CONFIRMED, NOW.plusSeconds(600), NOW))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex -> assertThat(((BookingException) ex).failure()).isEqualTo(BookingFailure.CONFLICT));
  }

  @Test
  void onlyConfirmedReservationsCanBeCancelled() {
    assertThatThrownBy(
            () ->
                BookingRules.assertCancellable(
                    BookingModels.ReservationStatus.PENDING_CONFIRMATION))
        .isInstanceOf(BookingException.class);
    assertThatThrownBy(
            () -> BookingRules.assertCancellable(BookingModels.ReservationStatus.CANCELLED))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).failure())
                    .isEqualTo(BookingFailure.RESERVATION_ALREADY_CANCELLED));
  }

  @Test
  void aSlotOccupiedByAnotherReservationIsUnavailable() {
    BookingModels.ServiceSlot open =
        new BookingModels.ServiceSlot(
            "slot-1", NOW, NOW.plusSeconds(3600), "Asia/Taipei", "+08:00", "OPEN", 1, "v1", 1);
    assertThatThrownBy(
            () -> BookingRules.assertSlotUsable(open, "other-reservation", "my-reservation"))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).failure())
                    .isEqualTo(BookingFailure.SLOT_UNAVAILABLE));
  }

  @Test
  void aSlotOccupiedByTheSameReservationStaysUsableForReschedule() {
    BookingModels.ServiceSlot open =
        new BookingModels.ServiceSlot(
            "slot-1", NOW, NOW.plusSeconds(3600), "Asia/Taipei", "+08:00", "OPEN", 1, "v1", 1);
    BookingRules.assertSlotUsable(open, "my-reservation", "my-reservation");
  }

  @Test
  void aClosedSlotIsRejectedBeforeAnyOccupancyCheck() {
    BookingModels.ServiceSlot closed =
        new BookingModels.ServiceSlot(
            "slot-1", NOW, NOW.plusSeconds(3600), "Asia/Taipei", "+08:00", "CLOSED", 1, "v1", 1);
    assertThatThrownBy(() -> BookingRules.assertSlotUsable(closed, null, "my-reservation"))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).failure())
                    .isEqualTo(BookingFailure.SLOT_CLOSED));
  }

  @Test
  void capacityOtherThanOneIsNotSupportedInStageTwo() {
    assertThatThrownBy(() -> BookingRules.capacity(2))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex -> assertThat(((BookingException) ex).reason()).isEqualTo("CAPACITY_NOT_SUPPORTED"));
  }

  @Test
  void anUnapprovedRelationshipGrantsNoAccess() {
    BookingModels.CustomerRelationship pending =
        new BookingModels.CustomerRelationship(
            "rel-1",
            "customer-1",
            "customer-2",
            BookingModels.RelationshipType.FAMILY,
            "BOOKING",
            "PENDING",
            NOW.minusSeconds(60),
            NOW.plusSeconds(3600),
            false);
    assertThatThrownBy(() -> BookingRules.assertRelationshipUsable(pending, NOW))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).failure())
                    .isEqualTo(BookingFailure.RELATIONSHIP_REQUIRED));
  }

  @Test
  void aRevokedRelationshipGrantsNoAccessEvenWhenApproved() {
    BookingModels.CustomerRelationship revoked =
        new BookingModels.CustomerRelationship(
            "rel-1",
            "customer-1",
            "customer-2",
            BookingModels.RelationshipType.FAMILY,
            "BOOKING",
            "APPROVED",
            NOW.minusSeconds(60),
            NOW.plusSeconds(3600),
            true);
    assertThatThrownBy(() -> BookingRules.assertRelationshipUsable(revoked, NOW))
        .isInstanceOf(BookingException.class);
  }

  @Test
  void aLinkRequestIsNotApprovedUntilDecided() {
    assertThatThrownBy(() -> BookingRules.assertLinkApproved("PENDING"))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).failure())
                    .isEqualTo(BookingFailure.LINK_NOT_APPROVED));
    BookingRules.assertLinkApproved("APPROVED");
  }

  @Test
  void anIanaZoneIdentifierIsAccepted() {
    // The business zone is an IANA identifier, not a fixed offset and not a free text label.
    assertThat(BookingRules.zone("Asia/Taipei")).isEqualTo("Asia/Taipei");
    assertThat(BookingRules.zone("UTC")).isEqualTo("UTC");
    assertThat(BookingRules.zone("America/New_York")).isEqualTo("America/New_York");
  }

  @Test
  void anUnknownOrOffsetZoneIsRejected() {
    assertThatThrownBy(() -> BookingRules.zone("Asia/Tai-Pei"))
        .isInstanceOf(BookingException.class);
    assertThatThrownBy(() -> BookingRules.zone("+08:00")).isInstanceOf(BookingException.class);
    assertThatThrownBy(() -> BookingRules.zone("Not/AZone")).isInstanceOf(BookingException.class);
    assertThatThrownBy(() -> BookingRules.zone("")).isInstanceOf(BookingException.class);
  }

  @Test
  void theRequestDigestIsOrderIndependentAcrossFields() {
    String first = BookingRules.requestDigest("a", "b", "c");
    String second = BookingRules.requestDigest("a", "b", "c");
    String different = BookingRules.requestDigest("a", "c", "b");
    assertThat(first).isEqualTo(second).isNotEqualTo(different);
  }

  @Test
  void aMaskedContactValueNeverShowsTheWholeValue() {
    assertThat(BookingRules.mask("0912345678")).isEqualTo("********78");
    assertThat(BookingRules.mask("ab")).isEqualTo("*b");
  }

  @Test
  void slotIdsAreLockedInAscendingOrder() {
    assertThat(BookingRules.lockOrder(java.util.List.of("slot-c", "slot-a", "slot-b")))
        .containsExactly("slot-a", "slot-b", "slot-c");
  }

  @Test
  void anInvalidIdempotencyKeyIsRejected() {
    assertThatThrownBy(() -> BookingRules.idempotencyKey("short"))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).failure())
                    .isEqualTo(BookingFailure.INVALID_REQUEST));
  }

  @Test
  void aMalformedUuidIsNotFoundRatherThanInvalid() {
    assertThatThrownBy(() -> BookingRules.uuid("not-a-uuid"))
        .isInstanceOf(BookingException.class)
        .satisfies(
            ex ->
                assertThat(((BookingException) ex).failure()).isEqualTo(BookingFailure.NOT_FOUND));
  }

  @Test
  void slotRangesMustBeOrderedAndBounded() {
    assertThatThrownBy(() -> BookingRules.slotStart(NOW, NOW.minusSeconds(60)))
        .isInstanceOf(BookingException.class);
    assertThatThrownBy(() -> BookingRules.slotStart(NOW, NOW.plus(Duration.ofDays(2))))
        .isInstanceOf(BookingException.class);
  }
}
