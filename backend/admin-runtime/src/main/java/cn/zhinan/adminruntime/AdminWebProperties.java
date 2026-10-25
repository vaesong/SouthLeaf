package cn.zhinan.adminruntime;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Explicit, local-only web listener inputs. No Spring property binding or fallback is allowed. */
final class AdminWebProperties {
  private static final Set<String> KEYS =
      Set.of(
          "SOUTHLEAF_ADMIN_WEB_MODE",
          "SOUTHLEAF_ADMIN_WEB_ORIGIN",
          "SOUTHLEAF_ADMIN_WEB_PORT",
          "SOUTHLEAF_ADMIN_WEB_KEYSTORE_PATH",
          "SOUTHLEAF_ADMIN_WEB_KEYSTORE_PASSWORD",
          "SOUTHLEAF_ADMIN_WEB_KEYSTORE_TYPE");
  private static final AdminWebProperties DISABLED =
      new AdminWebProperties(false, null, 0, null, null, null);

  private final boolean enabled;
  private final URI origin;
  private final int port;
  private final Path keyStorePath;
  private final String keyStorePassword;
  private final String keyStoreType;

  private AdminWebProperties(
      boolean enabled,
      URI origin,
      int port,
      Path keyStorePath,
      String keyStorePassword,
      String keyStoreType) {
    this.enabled = enabled;
    this.origin = origin;
    this.port = port;
    this.keyStorePath = keyStorePath;
    this.keyStorePassword = keyStorePassword;
    this.keyStoreType = keyStoreType;
  }

  static Set<String> keys() {
    return KEYS;
  }

  static AdminWebProperties load(Map<String, String> environment) {
    boolean supplied =
        environment.keySet().stream().anyMatch(key -> key.startsWith("SOUTHLEAF_ADMIN_WEB_"));
    if (!supplied) return DISABLED;
    for (String key : KEYS) requireValue(environment.get(key));
    if (!"demo".equals(environment.get("SOUTHLEAF_ADMIN_WEB_MODE"))) refuse();
    final int port;
    try {
      port = Integer.parseInt(environment.get("SOUTHLEAF_ADMIN_WEB_PORT"));
    } catch (NumberFormatException ex) {
      throw refused();
    }
    if (port < 1024 || port > 65535) refuse();
    URI origin = origin(environment.get("SOUTHLEAF_ADMIN_WEB_ORIGIN"), port);
    Path keyStore;
    try {
      keyStore = Path.of(environment.get("SOUTHLEAF_ADMIN_WEB_KEYSTORE_PATH"));
    } catch (RuntimeException ex) {
      throw refused();
    }
    if (!keyStore.isAbsolute()) refuse();
    String type = environment.get("SOUTHLEAF_ADMIN_WEB_KEYSTORE_TYPE");
    if (!"JKS".equals(type.toUpperCase(Locale.ROOT))) refuse();
    return new AdminWebProperties(
        true,
        origin,
        port,
        keyStore.normalize(),
        environment.get("SOUTHLEAF_ADMIN_WEB_KEYSTORE_PASSWORD"),
        type);
  }

  private static URI origin(String value, int port) {
    final URI parsed;
    try {
      parsed = URI.create(value);
    } catch (IllegalArgumentException ex) {
      throw refused();
    }
    if (!"https".equals(parsed.getScheme())
        || !"localhost".equals(parsed.getHost())
        || parsed.getPort() != port
        || parsed.getUserInfo() != null
        || parsed.getRawPath() == null
        || !parsed.getRawPath().isEmpty()
        || parsed.getRawQuery() != null
        || parsed.getRawFragment() != null) refuse();
    return parsed;
  }

  private static void requireValue(String value) {
    if (value == null || value.isBlank() || value.length() > 4096) refuse();
  }

  private static void refuse() {
    throw refused();
  }

  private static AdminStartupException refused() {
    return new AdminStartupException(AdminStartupException.Reason.CONFIGURATION);
  }

  boolean enabled() {
    return enabled;
  }

  URI origin() {
    return origin;
  }

  int port() {
    return port;
  }

  Path keyStorePath() {
    return keyStorePath;
  }

  String keyStorePassword() {
    return keyStorePassword;
  }

  String keyStoreType() {
    return keyStoreType;
  }

  @Override
  public String toString() {
    return "AdminWebProperties[REDACTED]";
  }
}
