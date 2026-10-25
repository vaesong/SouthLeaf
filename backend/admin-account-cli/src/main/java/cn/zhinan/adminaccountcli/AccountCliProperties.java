package cn.zhinan.adminaccountcli;

import cn.zhinan.admindbpolicy.AdminRuntimePolicy;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Separate C3 namespace. DB-password injection is allowed; account passwords are never
 * configuration.
 */
final class AccountCliProperties {
  private static final Set<String> KEYS =
      Set.of(
          "SOUTHLEAF_ACCOUNT_CLI_MODE",
          "SOUTHLEAF_ACCOUNT_CLI_DB_URL",
          "SOUTHLEAF_ACCOUNT_CLI_DB_USERNAME",
          "SOUTHLEAF_ACCOUNT_CLI_DB_PASSWORD",
          "SOUTHLEAF_ACCOUNT_CLI_DB_SCHEMA",
          "SOUTHLEAF_ACCOUNT_CLI_DB_EXPECTED_USER");
  private final String url;
  private final String username;
  private final String password;
  private final String schema;
  private final String expectedUser;

  private AccountCliProperties(
      String url, String username, String password, String schema, String expectedUser) {
    this.url = url;
    this.username = username;
    this.password = password;
    this.schema = schema;
    this.expectedUser = expectedUser;
  }

  static AccountCliProperties load(Map<String, String> env) {
    for (String key : env.keySet()) {
      String upper = key.toUpperCase(Locale.ROOT);
      if ((upper.startsWith("SOUTHLEAF_ACCOUNT_CLI_") && !KEYS.contains(key))
          || upper.startsWith("SOUTHLEAF_ADMIN_")
          || upper.startsWith("SOUTHLEAF_ADMIN_MIGRATION_")
          || upper.startsWith("SPRING_")
          || upper.startsWith("SERVER_")
          || upper.startsWith("LOGGING_")
          || upper.equals("DEBUG")
          || upper.equals("TRACE")) fail();
    }
    for (String key : KEYS) {
      String value = env.get(key);
      if (value == null || value.isBlank() || value.length() > 4096) fail();
    }
    String username = env.get("SOUTHLEAF_ACCOUNT_CLI_DB_USERNAME");
    String expected = env.get("SOUTHLEAF_ACCOUNT_CLI_DB_EXPECTED_USER");
    if (!username.matches("[A-Za-z][A-Za-z0-9_]{0,31}")
        || username.equalsIgnoreCase("root")
        || !expected.matches("[A-Za-z][A-Za-z0-9_]{0,31}@[A-Za-z0-9._%:/-]{1,255}")
        || !expected.startsWith(username + "@")) fail();
    try {
      return new AccountCliProperties(
          AdminRuntimePolicy.canonicalUrl(
              env.get("SOUTHLEAF_ACCOUNT_CLI_MODE"),
              env.get("SOUTHLEAF_ACCOUNT_CLI_DB_SCHEMA"),
              env.get("SOUTHLEAF_ACCOUNT_CLI_DB_URL")),
          username,
          env.get("SOUTHLEAF_ACCOUNT_CLI_DB_PASSWORD"),
          env.get("SOUTHLEAF_ACCOUNT_CLI_DB_SCHEMA"),
          expected);
    } catch (IllegalArgumentException ex) {
      fail();
      throw new AssertionError(ex);
    }
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

  @Override
  public String toString() {
    return "AccountCliProperties[REDACTED]";
  }

  private static void fail() {
    throw new AccountCliException(AccountCliException.Reason.CONFIGURATION);
  }
}
