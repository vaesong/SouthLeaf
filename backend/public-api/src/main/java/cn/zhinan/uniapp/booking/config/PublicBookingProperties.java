package cn.zhinan.uniapp.booking.config;

import java.time.Duration;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Environment contract of the customer booking entry. Every value is explicit: a partially
 * configured or self-contradictory environment refuses to start instead of falling back to a
 * default identity provider, a default business zone or a default package list.
 */
public final class PublicBookingProperties {
  static final java.util.Set<String> KEYS =
      java.util.Set.of(
          "SOUTHLEAF_PUBLIC_BOOKING_MODE",
          "SOUTHLEAF_PUBLIC_BOOKING_DB_URL",
          "SOUTHLEAF_PUBLIC_BOOKING_DB_USERNAME",
          "SOUTHLEAF_PUBLIC_BOOKING_DB_PASSWORD",
          "SOUTHLEAF_PUBLIC_BOOKING_DB_EXPECTED_USER",
          "SOUTHLEAF_PUBLIC_BOOKING_DATA_MODE",
          "SOUTHLEAF_PUBLIC_BOOKING_IDENTITY_SINK",
          "SOUTHLEAF_PUBLIC_BOOKING_WECHAT_APP_ID",
          "SOUTHLEAF_PUBLIC_BOOKING_WECHAT_APP_SECRET",
          "SOUTHLEAF_PUBLIC_BOOKING_APP_SCOPE",
          "SOUTHLEAF_PUBLIC_BOOKING_SESSION_SECRET",
          "SOUTHLEAF_PUBLIC_BOOKING_SESSION_MINUTES",
          "SOUTHLEAF_PUBLIC_BOOKING_REQUEST_HOURS",
          "SOUTHLEAF_PUBLIC_BOOKING_BUSINESS_ZONE",
          "SOUTHLEAF_PUBLIC_BOOKING_PACKAGE_REFS");

  static final PublicBookingProperties DISABLED =
      new PublicBookingProperties(
          false, null, null, null, null, null, null, null, null, null, null, null, null, null,
          Map.of());

  private final boolean enabled;
  private final String databaseUrl;
  private final String databaseUser;
  private final String databasePassword;
  private final String expectedUser;
  private final String dataMode;
  private final String identitySink;
  private final String wechatAppId;
  private final String wechatAppSecret;
  private final String appScope;
  private final String sessionSecret;
  private final Duration sessionLifetime;
  private final Duration requestLifetime;
  private final ZoneId businessZone;
  private final Map<String, String> packages;

  private PublicBookingProperties(
      boolean enabled,
      String databaseUrl,
      String databaseUser,
      String databasePassword,
      String expectedUser,
      String dataMode,
      String identitySink,
      String wechatAppId,
      String wechatAppSecret,
      String appScope,
      String sessionSecret,
      Duration sessionLifetime,
      Duration requestLifetime,
      ZoneId businessZone,
      Map<String, String> packages) {
    this.enabled = enabled;
    this.databaseUrl = databaseUrl;
    this.databaseUser = databaseUser;
    this.databasePassword = databasePassword;
    this.expectedUser = expectedUser;
    this.dataMode = dataMode;
    this.identitySink = identitySink;
    this.wechatAppId = wechatAppId;
    this.wechatAppSecret = wechatAppSecret;
    this.appScope = appScope;
    this.sessionSecret = sessionSecret;
    this.sessionLifetime = sessionLifetime;
    this.requestLifetime = requestLifetime;
    this.businessZone = businessZone;
    this.packages = packages;
  }

  public static PublicBookingProperties load(Map<String, String> env, String publicReaderUser) {
    String mode = env.get("SOUTHLEAF_PUBLIC_BOOKING_MODE");
    boolean supplied = KEYS.stream().anyMatch(env::containsKey);
    if (mode == null) {
      if (supplied) refuse();
      return DISABLED;
    }
    if (!"enabled".equals(mode)) refuse();
    for (String key : KEYS) {
      if (key.equals("SOUTHLEAF_PUBLIC_BOOKING_WECHAT_APP_ID")
          || key.equals("SOUTHLEAF_PUBLIC_BOOKING_WECHAT_APP_SECRET")) continue;
      requireValue(env, key);
    }
    String user = env.get("SOUTHLEAF_PUBLIC_BOOKING_DB_USERNAME");
    String expected = env.get("SOUTHLEAF_PUBLIC_BOOKING_DB_EXPECTED_USER");
    if (!PublicBookingGrantPolicy.WRITER.equals(user)
        || !expected.matches("[A-Za-z][A-Za-z0-9_]{0,31}@[A-Za-z0-9._%:/-]{1,255}")
        || !expected.startsWith(user + "@")
        || (publicReaderUser != null
            && !publicReaderUser.isBlank()
            && user.equalsIgnoreCase(publicReaderUser))) refuse();
    String dataMode =
        requireValue(env, "SOUTHLEAF_PUBLIC_BOOKING_DATA_MODE").toUpperCase(Locale.ROOT);
    if (!dataMode.equals("SYNTHETIC") && !dataMode.equals("PRODUCTION")) refuse();
    String sink =
        requireValue(env, "SOUTHLEAF_PUBLIC_BOOKING_IDENTITY_SINK").toUpperCase(Locale.ROOT);
    if (!sink.equals("WECHAT") && !sink.equals("TEST")) refuse();
    if (sink.equals("TEST") && !dataMode.equals("SYNTHETIC")) refuse();
    String appId = env.get("SOUTHLEAF_PUBLIC_BOOKING_WECHAT_APP_ID");
    String appSecret = env.get("SOUTHLEAF_PUBLIC_BOOKING_WECHAT_APP_SECRET");
    if (sink.equals("WECHAT")) {
      if (appId == null
          || appId.isBlank()
          || appSecret == null
          || appSecret.isBlank()
          || appId.length() > 64
          || appSecret.length() > 128) refuse();
    } else if (appId != null || appSecret != null) {
      // A test sink must not carry real platform credentials.
      refuse();
    }
    String secret = requireValue(env, "SOUTHLEAF_PUBLIC_BOOKING_SESSION_SECRET");
    if (secret.length() < 32 || secret.length() > 256) refuse();
    Duration session =
        Duration.ofMinutes(number(env, "SOUTHLEAF_PUBLIC_BOOKING_SESSION_MINUTES", 5, 720));
    Duration request =
        Duration.ofHours(number(env, "SOUTHLEAF_PUBLIC_BOOKING_REQUEST_HOURS", 1, 720));
    ZoneId zone;
    try {
      zone = ZoneId.of(requireValue(env, "SOUTHLEAF_PUBLIC_BOOKING_BUSINESS_ZONE"));
    } catch (RuntimeException unsupported) {
      throw new PublicBookingStartupException(PublicBookingStartupException.Reason.CONFIGURATION);
    }
    String scope = requireValue(env, "SOUTHLEAF_PUBLIC_BOOKING_APP_SCOPE");
    if (!scope.matches("[A-Za-z0-9._:-]{1,32}")) refuse();
    Map<String, String> packages = new LinkedHashMap<>();
    for (String raw : requireValue(env, "SOUTHLEAF_PUBLIC_BOOKING_PACKAGE_REFS").split(",")) {
      String entry = raw.strip();
      int separator = entry.indexOf('=');
      String ref = separator < 0 ? entry : entry.substring(0, separator).strip();
      String label = separator < 0 ? entry : entry.substring(separator + 1).strip();
      if (!ref.matches("[A-Za-z0-9._:-]{1,64}")
          || label.isBlank()
          || label.length() > 120
          || packages.size() >= 32
          || packages.putIfAbsent(ref, label) != null) refuse();
    }
    if (packages.isEmpty()) refuse();
    return new PublicBookingProperties(
        true,
        requireValue(env, "SOUTHLEAF_PUBLIC_BOOKING_DB_URL"),
        user,
        env.get("SOUTHLEAF_PUBLIC_BOOKING_DB_PASSWORD"),
        expected,
        dataMode,
        sink,
        appId,
        appSecret,
        scope,
        secret,
        session,
        request,
        zone,
        Map.copyOf(packages));
  }

  private static String requireValue(Map<String, String> env, String key) {
    String value = env.get(key);
    if (value == null || value.isBlank() || value.length() > 4096) refuse();
    return value;
  }

  private static int number(Map<String, String> env, String key, int min, int max) {
    String value = requireValue(env, key);
    try {
      int parsed = Integer.parseInt(value.strip());
      if (parsed < min || parsed > max) refuse();
      return parsed;
    } catch (NumberFormatException malformed) {
      throw new PublicBookingStartupException(PublicBookingStartupException.Reason.CONFIGURATION);
    }
  }

  private static void refuse() {
    throw new PublicBookingStartupException(PublicBookingStartupException.Reason.CONFIGURATION);
  }

  public boolean enabled() {
    return enabled;
  }

  public String databaseUrl() {
    return databaseUrl;
  }

  public String databaseUser() {
    return databaseUser;
  }

  public String databasePassword() {
    return databasePassword;
  }

  public String expectedUser() {
    return expectedUser;
  }

  /** Package fact scope this entry may search: {@code SYNTHETIC} or {@code PRODUCTION}. */
  public String dataMode() {
    return dataMode;
  }

  /** Mirrors the public catalog vocabulary: {@code demo} or {@code production}. */
  public String publicDataMode() {
    return "PRODUCTION".equals(dataMode) ? "production" : "demo";
  }

  public String identitySink() {
    return identitySink;
  }

  public String wechatAppId() {
    return wechatAppId;
  }

  public String wechatAppSecret() {
    return wechatAppSecret;
  }

  public String appScope() {
    return appScope;
  }

  public String sessionSecret() {
    return sessionSecret;
  }

  public Duration sessionLifetime() {
    return sessionLifetime;
  }

  public Duration requestLifetime() {
    return requestLifetime;
  }

  public ZoneId businessZone() {
    return businessZone;
  }

  public boolean allowsPackage(String ref) {
    return ref != null && packages.containsKey(ref);
  }

  /** Published bookable package versions, in publication order: ref to display label. */
  public List<Map.Entry<String, String>> packages() {
    return List.copyOf(packages.entrySet());
  }

  public String packageLabel(String ref) {
    return packages.get(ref);
  }

  @Override
  public String toString() {
    return "PublicBookingProperties[REDACTED]";
  }
}
