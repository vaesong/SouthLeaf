package cn.zhinan.adminaccountcli;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * Test-classpath-only S2-B fixture bridge. Accepts only the disposable S2-B fixture naming and only
 * bootstrap / replace-permissions; production main still requires a real Console.
 */
public final class S2BAccountCliFixtureMain {
  private static final Set<String> ACTIONS = Set.of("bootstrap", "replace-permissions");

  private S2BAccountCliFixtureMain() {}

  public static void main(String[] args) {
    try {
      require("S2B_DISPOSABLE_L5".equals(System.getenv("SOUTHLEAF_S2B_FIXTURE_KIND")));
      require(args != null && args.length >= 2 && ACTIONS.contains(args[0]));
      String mode = required("SOUTHLEAF_S2B_CLI_MODE");
      String schema = required("SOUTHLEAF_S2B_CLI_DB_SCHEMA");
      String username = required("SOUTHLEAF_S2B_CLI_DB_USERNAME");
      String expectedUser = required("SOUTHLEAF_S2B_CLI_DB_EXPECTED_USER");
      String url = required("SOUTHLEAF_S2B_CLI_DB_URL");
      String databasePassword = required("SOUTHLEAF_S2B_CLI_DB_PASSWORD");
      require("demo".equals(mode));
      require(schema.matches("southleaf_admin_s2b[a-z0-9]{8}"));
      require("s2b_cli".equals(username));
      require("s2b_cli@localhost".equals(expectedUser));
      require(
          url.matches(
              "jdbc:mysql://localhost:[0-9]{5}/"
                  + schema
                  + "\\?sslMode=VERIFY_IDENTITY"
                  + "&allowPublicKeyRetrieval=false&connectionTimeZone=UTC"
                  + "&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8"
                  + "&connectTimeout=[1-9][0-9]{3,4}&socketTimeout=[1-9][0-9]{3,4}"));
      require(option(args, "--account").matches("s2b\\.[a-z0-9]{8}"));
      // replace-permissions never needs a password; bootstrap reads it from the fixture env.
      String secret =
          "bootstrap".equals(args[0]) ? required("SOUTHLEAF_S2B_ACCOUNT_PASSWORD") : "unused";
      char[] retainedSecret = secret.toCharArray();
      int exit;
      try {
        Map<String, String> cliEnvironment =
            Map.of(
                "SOUTHLEAF_ACCOUNT_CLI_MODE", mode,
                "SOUTHLEAF_ACCOUNT_CLI_DB_URL", url,
                "SOUTHLEAF_ACCOUNT_CLI_DB_USERNAME", username,
                "SOUTHLEAF_ACCOUNT_CLI_DB_PASSWORD", databasePassword,
                "SOUTHLEAF_ACCOUNT_CLI_DB_SCHEMA", schema,
                "SOUTHLEAF_ACCOUNT_CLI_DB_EXPECTED_USER", expectedUser);
        exit =
            AccountCliMain.run(
                args,
                cliEnvironment,
                () -> retainedSecret.clone(),
                new PrintStream(System.out, true));
      } finally {
        Arrays.fill(retainedSecret, '\0');
      }
      System.exit(exit);
    } catch (RuntimeException | AssertionError rejected) {
      System.err.println("S2B_CLI_FIXTURE_REFUSED");
      System.exit(2);
    }
  }

  private static String option(String[] args, String name) {
    String found = null;
    for (int index = 1; index + 1 < args.length; index++) {
      if (name.equals(args[index])) {
        require(found == null);
        found = args[index + 1];
      }
    }
    require(found != null);
    return found;
  }

  private static String required(String key) {
    String value = System.getenv(key);
    require(value != null && !value.isBlank() && value.length() <= 4096);
    return value;
  }

  private static void require(boolean condition) {
    if (!condition) throw new IllegalArgumentException();
  }
}
