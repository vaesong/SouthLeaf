package cn.zhinan.adminaccountcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AccountCliPropertiesTest {
  @Test
  void acceptsOnlyASeparateCanonicalDemoTarget() {
    AccountCliProperties properties = AccountCliProperties.load(valid());

    assertThat(properties.url())
        .isEqualTo(
            "jdbc:mysql://db.example.test:3306/southleaf_admin_it?sslMode=VERIFY_IDENTITY&allowPublicKeyRetrieval=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8&connectTimeout=5000&socketTimeout=5000");
    assertThat(properties.toString()).doesNotContain("database-password");
  }

  @Test
  void rejectsRuntimeAndUnknownAccountCliConfiguration() {
    assertRejected(with("SOUTHLEAF_ADMIN_DB_URL", "jdbc:mysql://forbidden"));
    assertRejected(with("SOUTHLEAF_ACCOUNT_CLI_EXTRA", "forbidden"));
    assertRejected(with("SOUTHLEAF_ACCOUNT_CLI_DB_EXPECTED_USER", "root@%"));
  }

  private static Map<String, String> valid() {
    return Map.of(
        "SOUTHLEAF_ACCOUNT_CLI_MODE",
        "demo",
        "SOUTHLEAF_ACCOUNT_CLI_DB_URL",
        "jdbc:mysql://db.example.test:3306/southleaf_admin_it?sslMode=VERIFY_IDENTITY&allowPublicKeyRetrieval=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8",
        "SOUTHLEAF_ACCOUNT_CLI_DB_USERNAME",
        "account_cli",
        "SOUTHLEAF_ACCOUNT_CLI_DB_PASSWORD",
        "database-password",
        "SOUTHLEAF_ACCOUNT_CLI_DB_SCHEMA",
        "southleaf_admin_it",
        "SOUTHLEAF_ACCOUNT_CLI_DB_EXPECTED_USER",
        "account_cli@%");
  }

  private static Map<String, String> with(String key, String value) {
    var copy = new java.util.HashMap<>(valid());
    copy.put(key, value);
    return Map.copyOf(copy);
  }

  private static void assertRejected(Map<String, String> environment) {
    assertThatThrownBy(() -> AccountCliProperties.load(environment))
        .isInstanceOf(AccountCliException.class)
        .extracting("reason")
        .isEqualTo(AccountCliException.Reason.CONFIGURATION);
  }
}
