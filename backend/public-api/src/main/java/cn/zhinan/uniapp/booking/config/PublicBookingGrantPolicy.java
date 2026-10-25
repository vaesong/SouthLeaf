package cn.zhinan.uniapp.booking.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The customer entry is an intake identity, not an operations identity. It may open a request and
 * read its own projection, and it may never occupy a slot, confirm, cancel or write an order.
 */
public final class PublicBookingGrantPolicy {
  public static final String WRITER = "southleaf_public_booking_writer";

  public static final Map<String, List<String>> GRANTS;

  static {
    Map<String, List<String>> grants = new LinkedHashMap<>();
    grants.put("booking_customer", List.of("INSERT", "SELECT"));
    grants.put("booking_external_identity", List.of("INSERT", "SELECT"));
    grants.put("booking_reservation", List.of("INSERT", "SELECT"));
    grants.put("booking_request_preference", List.of("INSERT", "SELECT"));
    grants.put("booking_command", List.of("INSERT", "SELECT"));
    grants.put("booking_audit", List.of("INSERT"));
    grants.put("booking_notification_outbox", List.of("INSERT"));
    grants.put("booking_service_slot", List.of("SELECT"));
    grants.put("booking_slot_occupancy", List.of("SELECT"));
    grants.put("booking_order", List.of("SELECT"));
    grants.put("booking_order_snapshot", List.of("SELECT"));
    GRANTS = Map.copyOf(grants);
  }

  private PublicBookingGrantPolicy() {}
}
