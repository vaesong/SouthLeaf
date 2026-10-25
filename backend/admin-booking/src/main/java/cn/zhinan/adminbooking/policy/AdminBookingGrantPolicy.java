package cn.zhinan.adminbooking.policy;

import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.BookingFailure;
import java.util.List;
import java.util.Locale;

/**
 * The exact set of tables the booking slice may touch, and the exact set of grantees that may touch
 * them. The policy is a closed list: a table that is not named here is not reachable, and a grantee
 * that is not named for a table receives nothing. There is no substring, prefix or probe matching,
 * so a table such as {@code booking_order_snapshot} is never covered by a grant meant for {@code
 * booking_order}.
 */
public final class AdminBookingGrantPolicy {
  private AdminBookingGrantPolicy() {}

  /** Tables the booking slice owns outright. */
  public static final List<String> OWNED_TABLES =
      List.of(
          "booking_customer",
          "booking_external_identity",
          "booking_contact_point",
          "booking_customer_relationship",
          "booking_customer_link_request",
          "booking_service_slot",
          "booking_reservation",
          "booking_slot_occupancy",
          "booking_slot_assignment_history",
          "booking_order",
          "booking_order_snapshot",
          "booking_order_revision",
          "booking_command",
          "booking_notification_outbox",
          "booking_audit");

  /** Tables the booking slice may read but must never write. */
  public static final List<String> READ_ONLY_TABLES =
      List.of(
          "admin_work",
          "admin_work_version",
          "admin_media_asset",
          "admin_publication",
          "admin_package_fact");

  /**
   * Read-only grants with an exact privilege set, enforced by the startup verifier. A table listed
   * here must be granted exactly these privileges and nothing more: a released package fact is
   * readable so an order snapshot can quote it, but never writable by the booking runtime.
   */
  public static final java.util.Map<String, List<String>> READ_ONLY_GRANTS =
      java.util.Map.of("admin_package_fact", List.of("SELECT"));

  /** The grantee that owns the booking schema. */
  public static final String BOOKING_WRITER = "southleaf_booking_writer";

  /** The grantee that may read the booking schema for reporting only. */
  public static final String BOOKING_READER = "southleaf_booking_reader";

  /** The grantee used by the public read path; it sees no booking table at all. */
  public static final String PUBLIC_READER = "southleaf_public_reader";

  public static boolean isOwnedTable(String table) {
    return OWNED_TABLES.contains(table);
  }

  public static boolean isReadableByWriter(String table) {
    return OWNED_TABLES.contains(table) || READ_ONLY_TABLES.contains(table);
  }

  /**
   * Validates a requested grant against the closed list. A grantee that is not the declared owner
   * of a table receives a stable failure rather than a partially applied grant.
   */
  public static void requireGrant(String table, String grantee, boolean writable) {
    String normalizedTable = normalize(table);
    String normalizedGrantee = normalize(grantee);
    if (!isOwnedTable(normalizedTable)) {
      throw new BookingException(BookingFailure.FORBIDDEN, "TABLE_OUT_OF_SCOPE");
    }
    if (writable) {
      if (!BOOKING_WRITER.equals(normalizedGrantee)) {
        throw new BookingException(BookingFailure.FORBIDDEN, "GRANTEE_NOT_OWNER");
      }
      return;
    }
    if (!BOOKING_WRITER.equals(normalizedGrantee) && !BOOKING_READER.equals(normalizedGrantee)) {
      throw new BookingException(BookingFailure.FORBIDDEN, "GRANTEE_NOT_ALLOWED");
    }
  }

  /** The complete set of grants the writer must hold. A missing entry is a failure. */
  public static List<String> requiredWriterGrants() {
    return OWNED_TABLES.stream().map(table -> table + ":ALL").toList();
  }

  /** The complete set of grants the reader must hold. */
  public static List<String> requiredReaderGrants() {
    return OWNED_TABLES.stream().map(table -> table + ":SELECT").toList();
  }

  private static String normalize(String value) {
    if (value == null) return "";
    return value.trim().toLowerCase(Locale.ROOT);
  }
}
