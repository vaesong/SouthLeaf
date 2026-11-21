package cn.zhinan.appuser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Exact table privileges of the mini-program user account. It cannot write a booking row. */
public final class AppUserGrantPolicy {
  public static final String WRITER = "southleaf_public_user_writer";

  public static final Map<String, Set<String>> GRANTS;

  static {
    Map<String, Set<String>> grants = new LinkedHashMap<>();
    grants.put("app_user", Set.of("SELECT", "INSERT", "UPDATE"));
    grants.put("app_user_avatar", Set.of("SELECT", "INSERT", "UPDATE", "DELETE"));
    grants.put("share_card", Set.of("SELECT", "INSERT"));
    grants.put("visit_event", Set.of("SELECT", "INSERT"));
    grants.put("booking_external_identity", Set.of("SELECT"));
    grants.put("booking_reservation", Set.of("SELECT"));
    grants.put("booking_service_slot", Set.of("SELECT"));
    GRANTS = Map.copyOf(grants);
  }

  private AppUserGrantPolicy() {}
}
