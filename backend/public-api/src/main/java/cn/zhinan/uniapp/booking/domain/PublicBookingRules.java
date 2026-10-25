package cn.zhinan.uniapp.booking.domain;

import cn.zhinan.uniapp.booking.PublicBookingException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;

/** Pure rules for the customer entry: no framework, no database and never a payment concept. */
public final class PublicBookingRules {
  public static final String SOURCE = "CUSTOMER_ONLINE";
  public static final String OPERATION_CREATE = "CREATE_RESERVATION_PUBLIC";
  public static final Duration MAX_WINDOW = Duration.ofDays(31);

  private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

  private PublicBookingRules() {}

  public static String loginCode(String value) {
    if (value == null) throw invalid("LOGIN_CODE_MISSING");
    String trimmed = value.strip();
    if (trimmed.length() < 6
        || trimmed.length() > 512
        || trimmed.chars().anyMatch(Character::isISOControl)) throw invalid("LOGIN_CODE_INVALID");
    return trimmed;
  }

  public static String idempotencyKey(String value) {
    if (value == null || !value.matches("[A-Za-z0-9._:-]{8,64}")) throw invalid("IDEMPOTENCY_KEY");
    return value;
  }

  public static String slotId(String value) {
    if (value == null || !value.matches("[A-Za-z0-9-]{8,64}")) throw invalid("SLOT_ID");
    return value;
  }

  public static String packageVersionRef(String value) {
    if (value == null || !value.matches("[A-Za-z0-9._:-]{1,64}")) throw invalid("PACKAGE_REF");
    return value;
  }

  public static String reservationId(String value) {
    if (value == null || !value.matches("[A-Za-z0-9-]{8,64}")) throw invalid("RESERVATION_ID");
    return value;
  }

  /** A requested window is expressed in the business zone and may never exceed 31 local days. */
  public static Instant[] window(String from, String to, ZoneId zone, Instant now) {
    LocalDate start = date(from, "WINDOW_FROM");
    LocalDate end = date(to, "WINDOW_TO");
    if (end.isBefore(start)
        || Duration.between(start.atStartOfDay(), end.atStartOfDay()).toDays() > 30)
      throw invalid("WINDOW_RANGE");
    Instant lower = start.atStartOfDay(zone).toInstant();
    if (lower.isBefore(now.minus(Duration.ofDays(1)))) throw invalid("WINDOW_PAST");
    Instant upper = end.plusDays(1).atStartOfDay(zone).toInstant();
    return new Instant[] {lower, upper};
  }

  public static ZoneId zone(String businessZone, String displayOffset) {
    try {
      if (businessZone != null && !businessZone.isBlank()) return ZoneId.of(businessZone);
      if (displayOffset != null && !displayOffset.isBlank())
        return java.time.ZoneOffset.of(displayOffset);
    } catch (RuntimeException unsupported) {
      // A malformed stored zone must not invent a different display zone for the customer.
    }
    return java.time.ZoneOffset.UTC;
  }

  public static String instant(Instant value) {
    return value == null ? null : value.toString();
  }

  public static String localDate(Instant value, ZoneId zone) {
    return value == null ? null : value.atZone(zone).toLocalDate().format(DATE);
  }

  public static String localTime(Instant value, ZoneId zone) {
    return value == null ? null : value.atZone(zone).toLocalTime().format(TIME);
  }

  public static String slotLabel(Instant startAt, Instant endAt, ZoneId zone) {
    if (startAt == null) return null;
    String date = startAt.atZone(zone).toLocalDate().toString();
    String start = localTime(startAt, zone);
    String end = endAt == null ? "" : localTime(endAt, zone);
    return date + " " + start + (end.isEmpty() ? "" : "-" + end);
  }

  /**
   * A pending request that has passed its window is shown as expired to its owner. The stored
   * status is left for the operator's convergence job; the customer screen never claims otherwise.
   */
  public static String displayStatus(String status, Instant expiresAt, Instant now) {
    if ("PENDING_CONFIRMATION".equals(status) && expiresAt != null && !now.isBefore(expiresAt))
      return "EXPIRED";
    return status;
  }

  /** Only JPY-like zero-decimal currencies and two-decimal currencies are labelled in stage two. */
  public static String amountLabel(String currency, long minorUnits) {
    String code = currency == null || currency.isBlank() ? "JPY" : currency.strip().toUpperCase();
    if (code.equals("JPY") || code.equals("KRW"))
      return "JP\u00a5" + String.format("%,d", minorUnits).replace(',', ',');
    return code + " " + BigDecimal.valueOf(minorUnits).movePointLeft(2).toPlainString();
  }

  public static String digest(String... parts) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String part : parts) {
        digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0x1f);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  public static String fingerprint(String provider, String appScope, String subject) {
    return digest(provider, appScope, subject);
  }

  public static PublicBookingException invalid(String reason) {
    return new PublicBookingException(PublicBookingException.ErrorCode.REQUEST_INVALID, reason);
  }

  private static LocalDate date(String value, String reason) {
    if (value == null) throw invalid(reason + "_MISSING");
    try {
      return LocalDate.parse(value, DATE);
    } catch (DateTimeParseException malformed) {
      throw invalid(reason + "_INVALID");
    }
  }
}
