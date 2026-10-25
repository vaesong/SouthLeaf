package cn.zhinan.adminbooking.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pure S4/S5 limits, canonical digests and transition rules (D-304, D-305, D-306). Nothing here
 * reads configuration, the clock, the database or the request: every value is supplied by the
 * caller so that business rules stay testable and operating numbers stay outside the code.
 */
public final class BookingRules {
  /** P02 stage-two baseline. Q02 owns the deposit timer; this is the request lifetime only. */
  public static final Duration PENDING_LIFETIME = Duration.ofHours(48);

  public static final int MAX_SLOT_MINUTES = 24 * 60;
  public static final int MIN_SLOT_MINUTES = 5;
  public static final int MAX_SLOTS_PER_DAY = 24;
  public static final int MAX_CONTACT_VALUE = 120;
  public static final int MAX_DISPLAY_NAME = 80;
  public static final int MAX_REASON_CODE = 40;
  public static final int MAX_CHANGED_FIELDS = 400;
  public static final int MAX_CANDIDATE_REF = 64;

  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{16,64}");
  private static final Pattern UUID_TEXT =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern REF = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
  private static final Pattern DISPLAY_NAME = Pattern.compile("[\\p{L}\\p{N} ._'-]{1,80}");
  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

  /** An IANA zone identifier is a slash separated path such as Asia/Taipei or UTC. */
  private static final Pattern ZONE = Pattern.compile("[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+)*");

  private BookingRules() {}

  public static String idempotencyKey(String value) {
    if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches())
      throw new BookingException(BookingFailure.INVALID_REQUEST, "IDEMPOTENCY_KEY_INVALID");
    return value;
  }

  public static String uuid(String value) {
    if (value == null || !UUID_TEXT.matcher(value).matches())
      throw new BookingException(BookingFailure.NOT_FOUND, "ID_INVALID");
    return value;
  }

  public static String newId() {
    return UUID.randomUUID().toString();
  }

  public static String ref(String value) {
    if (value == null || !REF.matcher(value).matches())
      throw new BookingException(BookingFailure.INVALID_REQUEST, "REFERENCE_INVALID");
    return value;
  }

  public static String currency(String value) {
    if (value == null || !CURRENCY.matcher(value).matches())
      throw new BookingException(BookingFailure.INVALID_REQUEST, "CURRENCY_INVALID");
    return value;
  }

  /**
   * Validates a business time zone.
   *
   * <p>The zone is an IANA identifier such as {@code Asia/Taipei} or {@code UTC}. It is checked
   * against the zone database of the runtime, so an unknown identifier is rejected instead of being
   * stored and later silently interpreted as the platform default. A fixed offset such as {@code
   * +08:00} is not a zone and is rejected here; the display offset travels in its own field.
   */
  /** Validates a provider or application scope without accepting raw platform subject values. */
  public static String identityLabel(String value, int maxLength, String reason) {
    if (value == null
        || value.length() > maxLength
        || !Pattern.matches("[A-Za-z0-9._:-]{1," + maxLength + "}", value))
      throw new BookingException(BookingFailure.INVALID_REQUEST, reason);
    return value;
  }

  /** A subject fingerprint is a fixed-width lowercase SHA-256 value, never the raw subject. */
  public static String subjectFingerprint(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}"))
      throw new BookingException(BookingFailure.INVALID_REQUEST, "SUBJECT_FINGERPRINT_INVALID");
    return value;
  }

  public static String contactPointType(String value) {
    if (value == null || !List.of("PHONE", "EMAIL", "WECHAT", "OTHER").contains(value))
      throw new BookingException(BookingFailure.INVALID_REQUEST, "CONTACT_TYPE_INVALID");
    return value;
  }

  public static String verificationStatus(String value) {
    if (value == null || !List.of("UNVERIFIED", "VERIFIED", "REJECTED").contains(value))
      throw new BookingException(BookingFailure.INVALID_REQUEST, "CONTACT_STATUS_INVALID");
    return value;
  }

  public static String maskedContactValue(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 160
        || !(value.contains("*") || value.contains("•") || value.contains("…")))
      throw new BookingException(BookingFailure.INVALID_REQUEST, "CONTACT_MASK_INVALID");
    return value;
  }

  /** A digest of ciphertext for idempotency only; plaintext is never accepted in this module. */
  public static String encryptedValueDigest(byte[] value) {
    byte[] copy = encryptedContactValue(value);
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(copy));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    } finally {
      java.util.Arrays.fill(copy, (byte) 0);
    }
  }

  public static byte[] encryptedContactValue(byte[] value) {
    if (value == null || value.length < 1 || value.length > 512)
      throw new BookingException(BookingFailure.INVALID_REQUEST, "CONTACT_CIPHER_INVALID");
    return java.util.Arrays.copyOf(value, value.length);
  }

  public static String zone(String value) {
    if (value == null || value.length() > 64 || !ZONE.matcher(value).matches())
      throw new BookingException(BookingFailure.INVALID_REQUEST, "ZONE_INVALID");
    if (!ZoneId.getAvailableZoneIds().contains(value))
      throw new BookingException(BookingFailure.INVALID_REQUEST, "ZONE_UNKNOWN");
    return value;
  }

  public static String displayName(String value) {
    if (value == null || !DISPLAY_NAME.matcher(value).matches())
      throw new BookingException(BookingFailure.INVALID_REQUEST, "DISPLAY_NAME_INVALID");
    return value;
  }

  public static String reasonCode(String value) {
    if (value == null || value.isBlank() || value.length() > MAX_REASON_CODE)
      throw new BookingException(BookingFailure.INVALID_REQUEST, "REASON_CODE_INVALID");
    return value;
  }

  /** Non-negative amount in the currency's minor unit. No floating point is ever accepted. */
  public static long amountMinorUnits(long value) {
    if (value < 0) throw new BookingException(BookingFailure.INVALID_REQUEST, "AMOUNT_INVALID");
    return value;
  }

  public static Instant slotStart(Instant start, Instant end) {
    if (start == null || end == null || !end.isAfter(start))
      throw new BookingException(BookingFailure.INVALID_REQUEST, "SLOT_RANGE_INVALID");
    long minutes = Duration.between(start, end).toMinutes();
    if (minutes < MIN_SLOT_MINUTES || minutes > MAX_SLOT_MINUTES)
      throw new BookingException(BookingFailure.INVALID_REQUEST, "SLOT_DURATION_INVALID");
    return start;
  }

  public static int capacity(int value) {
    if (value != 1)
      // Stage two fixes capacity at one; a different capacity needs its own design (D-305 §2).
      throw new BookingException(BookingFailure.INVALID_REQUEST, "CAPACITY_NOT_SUPPORTED");
    return value;
  }

  /** Canonical digest of a request. Field order is fixed so replays compare equal. */
  public static String requestDigest(String... parts) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String part : parts) {
        if (part == null) continue;
        digest.update(part.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0x1f);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  public static String digestOf(String... parts) {
    return requestDigest(parts);
  }

  /**
   * A pending request expires {@link #PENDING_LIFETIME} after creation unless the caller supplies
   * an explicit, already approved window. Q02 owns the deposit rules; this never models a deposit.
   */
  public static Instant expiresAt(Instant createdAt, Duration lifetime) {
    if (createdAt == null)
      throw new BookingException(BookingFailure.INVALID_REQUEST, "NOW_MISSING");
    Duration window = lifetime == null ? PENDING_LIFETIME : lifetime;
    if (window.isNegative() || window.isZero() || window.compareTo(Duration.ofDays(30)) > 0)
      throw new BookingException(BookingFailure.INVALID_REQUEST, "LIFETIME_INVALID");
    return createdAt.plus(window);
  }

  /** A pending reservation may still be confirmed only while it is neither expired nor terminal. */
  public static void assertConfirmable(
      BookingModels.ReservationStatus status, Instant expiresAt, Instant now) {
    if (status == BookingModels.ReservationStatus.CONFIRMED)
      throw new BookingException(BookingFailure.CONFLICT, "ALREADY_CONFIRMED");
    if (status != BookingModels.ReservationStatus.PENDING_CONFIRMATION)
      throw new BookingException(BookingFailure.RESERVATION_NOT_PENDING, "NOT_PENDING");
    if (expiresAt != null && !now.isBefore(expiresAt))
      throw new BookingException(BookingFailure.RESERVATION_EXPIRED, "EXPIRED");
  }

  /** Expiry uses a conditional update; the caller re-reads the winner. */
  public static boolean isExpired(Instant expiresAt, Instant dbNow) {
    return expiresAt != null && !dbNow.isBefore(expiresAt);
  }

  /** Only a confirmed reservation may be cancelled, and only once. */
  public static void assertCancellable(BookingModels.ReservationStatus status) {
    if (status == BookingModels.ReservationStatus.CANCELLED)
      throw new BookingException(BookingFailure.RESERVATION_ALREADY_CANCELLED, "ALREADY_CANCELLED");
    if (status != BookingModels.ReservationStatus.CONFIRMED)
      throw new BookingException(BookingFailure.INVALID_REQUEST, "NOT_CONFIRMED");
  }

  public static void assertOrderCancellable(BookingModels.OrderLifecycle lifecycle) {
    if (lifecycle == BookingModels.OrderLifecycle.CANCELLED)
      throw new BookingException(BookingFailure.ORDER_ALREADY_CANCELLED, "ALREADY_CANCELLED");
  }

  /** A slot must exist, be open and not already be occupied by another reservation. */
  public static void assertSlotUsable(
      BookingModels.ServiceSlot slot, String occupancyReservationId, String reservationId) {
    if (slot == null) throw new BookingException(BookingFailure.NOT_FOUND, "SLOT_NOT_FOUND");
    if (!"OPEN".equals(slot.status()))
      throw new BookingException(BookingFailure.SLOT_CLOSED, "SLOT_CLOSED");
    if (occupancyReservationId != null && !occupancyReservationId.equals(reservationId))
      throw new BookingException(BookingFailure.SLOT_UNAVAILABLE, "SLOT_UNAVAILABLE");
  }

  /** The customer relationship must be approved and inside its validity window. */
  public static void assertRelationshipUsable(
      BookingModels.CustomerRelationship relationship, Instant now) {
    if (relationship == null)
      throw new BookingException(BookingFailure.RELATIONSHIP_REQUIRED, "RELATIONSHIP_REQUIRED");
    if (relationship.revoked())
      throw new BookingException(BookingFailure.RELATIONSHIP_REQUIRED, "RELATIONSHIP_REVOKED");
    if (!"APPROVED".equals(relationship.verificationStatus()))
      throw new BookingException(BookingFailure.RELATIONSHIP_REQUIRED, "RELATIONSHIP_NOT_APPROVED");
    if (relationship.validFrom() != null && now.isBefore(relationship.validFrom()))
      throw new BookingException(
          BookingFailure.RELATIONSHIP_REQUIRED, "RELATIONSHIP_NOT_YET_VALID");
    if (relationship.validUntil() != null && !now.isBefore(relationship.validUntil()))
      throw new BookingException(BookingFailure.RELATIONSHIP_REQUIRED, "RELATIONSHIP_EXPIRED");
  }

  /** A link request never grants access before it is approved (D-304 §5). */
  public static void assertLinkApproved(String status) {
    if (!"APPROVED".equals(status))
      throw new BookingException(BookingFailure.LINK_NOT_APPROVED, "LINK_NOT_APPROVED");
  }

  /**
   * The masked contact value is what list views may show; the raw value never leaves the adapter.
   */
  public static String mask(String value) {
    if (value == null || value.isEmpty()) return "";
    if (value.length() == 1) return "*";
    int visible = value.length() == 2 ? 1 : 2;
    return "*".repeat(value.length() - visible) + value.substring(value.length() - visible);
  }

  /** Ordered slot ids are locked in ascending id order to keep one global lock sequence. */
  public static List<String> lockOrder(List<String> slotIds) {
    return slotIds.stream().distinct().sorted().toList();
  }
}
