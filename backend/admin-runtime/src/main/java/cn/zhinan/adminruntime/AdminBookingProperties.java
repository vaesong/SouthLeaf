package cn.zhinan.adminruntime;

import cn.zhinan.adminbooking.policy.AdminBookingGrantPolicy;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Demo-only reservation operations with a dedicated table-scoped booking-writer identity. */
final class AdminBookingProperties {
  static final Set<String> KEYS =
      Set.of(
          "SOUTHLEAF_ADMIN_BOOKING_MODE",
          "SOUTHLEAF_ADMIN_BOOKING_DB_USERNAME",
          "SOUTHLEAF_ADMIN_BOOKING_DB_PASSWORD",
          "SOUTHLEAF_ADMIN_BOOKING_DB_EXPECTED_USER",
          "SOUTHLEAF_ADMIN_BOOKING_DATA_MODE");
  static final AdminBookingProperties DISABLED =
      new AdminBookingProperties(false, null, null, null, null);

  private final boolean enabled;
  private final String databaseUser;
  private final String databasePassword;
  private final String expectedUser;
  private final String dataMode;

  private AdminBookingProperties(
      boolean enabled,
      String databaseUser,
      String databasePassword,
      String expectedUser,
      String dataMode) {
    this.enabled = enabled;
    this.databaseUser = databaseUser;
    this.databasePassword = databasePassword;
    this.expectedUser = expectedUser;
    this.dataMode = dataMode;
  }

  static AdminBookingProperties load(
      Map<String, String> env,
      String adminMode,
      String runtimeUser,
      String outboxUser,
      String contentBusinessUser,
      String contentPublicationUser,
      boolean webEnabled,
      boolean outboxEnabled) {
    String mode = env.get("SOUTHLEAF_ADMIN_BOOKING_MODE");
    boolean supplied = KEYS.stream().anyMatch(env::containsKey);
    if (mode == null) {
      if (supplied) refuse();
      return DISABLED;
    }
    if (!"enabled".equals(mode) || !"demo".equals(adminMode) || !webEnabled || !outboxEnabled)
      refuse();
    for (String key : KEYS) {
      String value = env.get(key);
      if (value == null || value.isBlank() || value.length() > 4096) refuse();
    }
    String username = env.get("SOUTHLEAF_ADMIN_BOOKING_DB_USERNAME");
    String expected = env.get("SOUTHLEAF_ADMIN_BOOKING_DB_EXPECTED_USER");
    if (!AdminBookingGrantPolicy.BOOKING_WRITER.equals(username)
        || !expected.matches("[A-Za-z][A-Za-z0-9_]{0,31}@[A-Za-z0-9._%:/-]{1,255}")
        || !expected.startsWith(username + "@")
        || same(username, runtimeUser)
        || same(username, outboxUser)
        || same(username, contentBusinessUser)
        || same(username, contentPublicationUser)) refuse();
    String dataMode = env.get("SOUTHLEAF_ADMIN_BOOKING_DATA_MODE").trim().toUpperCase(Locale.ROOT);
    if (!dataMode.equals("SYNTHETIC") && !dataMode.equals("PRODUCTION")) refuse();
    return new AdminBookingProperties(
        true, username, env.get("SOUTHLEAF_ADMIN_BOOKING_DB_PASSWORD"), expected, dataMode);
  }

  private static boolean same(String left, String right) {
    return right != null && left.equalsIgnoreCase(right);
  }

  private static void refuse() {
    throw new AdminStartupException(AdminStartupException.Reason.CONFIGURATION);
  }

  boolean enabled() {
    return enabled;
  }

  String databaseUser() {
    return databaseUser;
  }

  String databasePassword() {
    return databasePassword;
  }

  String expectedUser() {
    return expectedUser;
  }

  /** Package fact scope this runtime may read: {@code SYNTHETIC} or {@code PRODUCTION}. */
  String dataMode() {
    return dataMode;
  }

  @Override
  public String toString() {
    return "AdminBookingProperties[REDACTED]";
  }
}
