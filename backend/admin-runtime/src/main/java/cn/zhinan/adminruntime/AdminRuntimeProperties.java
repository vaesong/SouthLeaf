package cn.zhinan.adminruntime;

import cn.zhinan.admindbpolicy.AdminRuntimePolicy;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Not a record: generated toString must never expose credentials. No Spring binding/fallback. */
final class AdminRuntimeProperties {
  private static final Set<String> REQUIRED_KEYS =
      Set.of(
          "SOUTHLEAF_ADMIN_MODE",
          "SOUTHLEAF_ADMIN_DB_URL",
          "SOUTHLEAF_ADMIN_DB_USERNAME",
          "SOUTHLEAF_ADMIN_DB_PASSWORD",
          "SOUTHLEAF_ADMIN_DB_SCHEMA",
          "SOUTHLEAF_ADMIN_DB_EXPECTED_USER");
  private static final Set<String> KEYS =
      Set.of(
          "SOUTHLEAF_ADMIN_MODE",
          "SOUTHLEAF_ADMIN_DB_URL",
          "SOUTHLEAF_ADMIN_DB_USERNAME",
          "SOUTHLEAF_ADMIN_DB_PASSWORD",
          "SOUTHLEAF_ADMIN_DB_SCHEMA",
          "SOUTHLEAF_ADMIN_DB_EXPECTED_USER",
          "SOUTHLEAF_ADMIN_WEB_MODE",
          "SOUTHLEAF_ADMIN_WEB_ORIGIN",
          "SOUTHLEAF_ADMIN_WEB_PORT",
          "SOUTHLEAF_ADMIN_WEB_KEYSTORE_PATH",
          "SOUTHLEAF_ADMIN_WEB_KEYSTORE_PASSWORD",
          "SOUTHLEAF_ADMIN_WEB_KEYSTORE_TYPE");
  private final String url, username, password, schema, expectedUser;
  private final AdminWebProperties web;

  private AdminRuntimeProperties(
      String url,
      String username,
      String password,
      String schema,
      String expectedUser,
      AdminWebProperties web) {
    this.url = url;
    this.username = username;
    this.password = password;
    this.schema = schema;
    this.expectedUser = expectedUser;
    this.web = web;
  }

  static AdminRuntimeProperties load(Map<String, String> env, Properties system) {
    for (String key : env.keySet()) {
      String k = key.toUpperCase(Locale.ROOT);
      if (k.startsWith("SPRING_")
          || k.startsWith("LOGGING_")
          || k.startsWith("LOGBACK_")
          || k.startsWith("LOG_")
          || k.startsWith("SERVER_")
          || k.equals("DEBUG")
          || k.equals("TRACE")
          || (k.startsWith("SOUTHLEAF_ADMIN_") && !KEYS.contains(key)))
        refuse(AdminStartupException.Reason.CONFIG_SOURCE);
    }
    for (String key : system.stringPropertyNames()) {
      String k = key.toLowerCase(Locale.ROOT);
      if (k.startsWith("spring.")
          || k.startsWith("org.springframework.")
          || k.startsWith("logging.")
          || k.startsWith("logback.")
          || k.startsWith("server.")
          || k.startsWith("southleaf.admin.")
          || k.equals("debug")
          || k.equals("trace")
          || k.startsWith("log_")) refuse(AdminStartupException.Reason.CONFIG_SOURCE);
    }
    for (String key : REQUIRED_KEYS) {
      String value = env.get(key);
      if (value == null || value.isBlank() || value.length() > 4096)
        refuse(AdminStartupException.Reason.CONFIGURATION);
    }
    // Validate optional web inputs here so a partial/unsafe web configuration cannot be ignored.
    AdminWebProperties web = AdminWebProperties.load(env);
    String username = env.get("SOUTHLEAF_ADMIN_DB_USERNAME");
    String expected = env.get("SOUTHLEAF_ADMIN_DB_EXPECTED_USER");
    if (!username.matches("[A-Za-z][A-Za-z0-9_]{0,31}")
        || username.equalsIgnoreCase("root")
        || !expected.matches("[A-Za-z][A-Za-z0-9_]{0,31}@[A-Za-z0-9._%:/-]{1,255}")
        || !expected.startsWith(username + "@")) refuse(AdminStartupException.Reason.CONFIGURATION);
    String schema = env.get("SOUTHLEAF_ADMIN_DB_SCHEMA");
    final String url;
    try {
      url =
          AdminRuntimePolicy.canonicalUrl(
              env.get("SOUTHLEAF_ADMIN_MODE"), schema, env.get("SOUTHLEAF_ADMIN_DB_URL"));
    } catch (IllegalArgumentException ex) {
      throw new AdminStartupException(AdminStartupException.Reason.CONFIGURATION);
    }
    return new AdminRuntimeProperties(
        url, username, env.get("SOUTHLEAF_ADMIN_DB_PASSWORD"), schema, expected, web);
  }

  private static void refuse(AdminStartupException.Reason reason) {
    throw new AdminStartupException(reason);
  }

  String url() {
    return url;
  }

  String username() {
    return username;
  }

  String password() {
    return password;
  }

  String schema() {
    return schema;
  }

  String expectedUser() {
    return expectedUser;
  }

  AdminWebProperties web() {
    return web;
  }

  @Override
  public String toString() {
    return "AdminRuntimeProperties[REDACTED]";
  }
}
