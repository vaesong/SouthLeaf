package cn.zhinan.uniapp.user;

import cn.zhinan.appuser.AppUserGrantPolicy;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/** Environment of the mini-program user entry. It stays off unless every value is present. */
public final class PublicUserProperties {
  static final Set<String> KEYS =
      Set.of(
          "SOUTHLEAF_PUBLIC_USER_MODE",
          "SOUTHLEAF_PUBLIC_USER_DB_URL",
          "SOUTHLEAF_PUBLIC_USER_DB_USERNAME",
          "SOUTHLEAF_PUBLIC_USER_DB_PASSWORD",
          "SOUTHLEAF_PUBLIC_USER_DB_EXPECTED_USER",
          "SOUTHLEAF_PUBLIC_USER_APP_SCOPE",
          "SOUTHLEAF_PUBLIC_USER_WECHAT_APP_ID",
          "SOUTHLEAF_PUBLIC_USER_WECHAT_APP_SECRET",
          "SOUTHLEAF_PUBLIC_USER_SESSION_SECRET",
          "SOUTHLEAF_PUBLIC_USER_SESSION_MINUTES",
          "SOUTHLEAF_PUBLIC_USER_IDENTITY_SINK",
          "SOUTHLEAF_PUBLIC_USER_DATA_MODE");

  private final boolean enabled;
  private final String databaseUrl;
  private final String databaseUser;
  private final String databasePassword;
  private final String expectedUser;
  private final String appScope;
  private final String wechatAppId;
  private final String wechatAppSecret;
  private final String sessionSecret;
  private final Duration sessionLifetime;
  private final String identitySink;
  private final String dataMode;

  private PublicUserProperties(
      boolean enabled,
      String databaseUrl,
      String databaseUser,
      String databasePassword,
      String expectedUser,
      String appScope,
      String wechatAppId,
      String wechatAppSecret,
      String sessionSecret,
      Duration sessionLifetime,
      String identitySink,
      String dataMode) {
    this.enabled = enabled;
    this.databaseUrl = databaseUrl;
    this.databaseUser = databaseUser;
    this.databasePassword = databasePassword;
    this.expectedUser = expectedUser;
    this.appScope = appScope;
    this.wechatAppId = wechatAppId;
    this.wechatAppSecret = wechatAppSecret;
    this.sessionSecret = sessionSecret;
    this.sessionLifetime = sessionLifetime;
    this.identitySink = identitySink;
    this.dataMode = dataMode;
  }

  public static PublicUserProperties load(
      java.util.Map<String, String> env, String publicReaderUser) {
    String mode = env.get("SOUTHLEAF_PUBLIC_USER_MODE");
    boolean supplied = KEYS.stream().anyMatch(env::containsKey);
    if (mode == null) {
      if (supplied) refuse();
      return disabled();
    }
    if (!"enabled".equals(mode)) refuse();
    for (String key : KEYS) {
      if (key.equals("SOUTHLEAF_PUBLIC_USER_WECHAT_APP_ID")
          || key.equals("SOUTHLEAF_PUBLIC_USER_WECHAT_APP_SECRET")) continue;
      require(env, key);
    }
    String user = env.get("SOUTHLEAF_PUBLIC_USER_DB_USERNAME");
    String expected = env.get("SOUTHLEAF_PUBLIC_USER_DB_EXPECTED_USER");
    if (!AppUserGrantPolicy.WRITER.equals(user)
        || expected == null
        || !expected.matches("[A-Za-z][A-Za-z0-9_]{0,31}@[A-Za-z0-9._%:/-]{1,255}")
        || !expected.startsWith(user + "@")
        || (publicReaderUser != null
            && !publicReaderUser.isBlank()
            && user.equalsIgnoreCase(publicReaderUser))) refuse();
    String sink = require(env, "SOUTHLEAF_PUBLIC_USER_IDENTITY_SINK").toUpperCase(Locale.ROOT);
    String dataMode = require(env, "SOUTHLEAF_PUBLIC_USER_DATA_MODE").toUpperCase(Locale.ROOT);
    if ((!sink.equals("WECHAT") && !sink.equals("TEST"))
        || (!dataMode.equals("PRODUCTION") && !dataMode.equals("SYNTHETIC"))
        || (sink.equals("TEST") && !dataMode.equals("SYNTHETIC"))) refuse();
    String appId = env.get("SOUTHLEAF_PUBLIC_USER_WECHAT_APP_ID");
    String appSecret = env.get("SOUTHLEAF_PUBLIC_USER_WECHAT_APP_SECRET");
    if (sink.equals("WECHAT")) {
      if (appId == null
          || appSecret == null
          || appId.isBlank()
          || appSecret.isBlank()
          || appId.length() > 64
          || appSecret.length() > 128) refuse();
    } else if (appId != null || appSecret != null) {
      refuse();
    }
    String secret = require(env, "SOUTHLEAF_PUBLIC_USER_SESSION_SECRET");
    if (secret.length() < 32 || secret.length() > 256) refuse();
    String scope = require(env, "SOUTHLEAF_PUBLIC_USER_APP_SCOPE");
    if (!scope.matches("[A-Za-z0-9._:-]{1,32}")) refuse();
    int minutes = number(env.get("SOUTHLEAF_PUBLIC_USER_SESSION_MINUTES"));
    String url = require(env, "SOUTHLEAF_PUBLIC_USER_DB_URL");
    if (url.length() > 2048 || url.indexOf(' ') >= 0) refuse();
    return new PublicUserProperties(
        true,
        url,
        user,
        env.get("SOUTHLEAF_PUBLIC_USER_DB_PASSWORD"),
        expected,
        scope,
        appId,
        appSecret,
        secret,
        Duration.ofMinutes(minutes),
        sink,
        dataMode);
  }

  private static PublicUserProperties disabled() {
    return new PublicUserProperties(
        false, null, null, null, null, null, null, null, null, null, null, null);
  }

  private static void refuse() {
    throw new PublicUserStartupException(PublicUserStartupException.Reason.CONFIGURATION);
  }

  private static String require(java.util.Map<String, String> env, String key) {
    String value = env.get(key);
    if (value == null || value.isBlank()) refuse();
    return value;
  }

  private static int number(String raw) {
    try {
      int value = Integer.parseInt(raw);
      if (value < 5 || value > 720) refuse();
      return value;
    } catch (RuntimeException rejected) {
      refuse();
      return 0;
    }
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

  public String appScope() {
    return appScope;
  }

  public String wechatAppId() {
    return wechatAppId;
  }

  public String wechatAppSecret() {
    return wechatAppSecret;
  }

  public String sessionSecret() {
    return sessionSecret;
  }

  public Duration sessionLifetime() {
    return sessionLifetime;
  }

  public String identitySink() {
    return identitySink;
  }

  public String publicDataMode() {
    return "PRODUCTION".equals(dataMode) ? "production" : "demo";
  }

  @Override
  public String toString() {
    return "PublicUserProperties[" + (enabled ? "enabled" : "disabled") + "]";
  }
}
