package cn.zhinan.adminaccountcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AccountCliDatabaseVerifierTest {
  @Test
  void acceptsOnlyVerifiedSchemaIdentityRolesAndExactDirectGrants() throws Exception {
    var queries = new ArrayList<String>();

    String identity =
        AccountCliDatabaseVerifier.verify(
            connection("southleaf_admin_it", "account_cli@%", "NONE", "", exactGrants(), queries),
            properties());

    assertThat(identity).isEqualTo("account_cli@%");
    assertThat(queries)
        .containsExactly(
            "SELECT DATABASE()",
            "SELECT CURRENT_USER()",
            "SELECT CURRENT_ROLE()",
            "SELECT @@GLOBAL.mandatory_roles",
            "SHOW GRANTS FOR CURRENT_USER()");
  }

  @Test
  void rejectsUnexpectedSchemaIdentityRoleMandatoryRoleAndExtraRightsWithoutARealDatabase() {
    assertRejected(
        connection("other_schema", "account_cli@%", "NONE", "", exactGrants(), new ArrayList<>()));
    assertRejected(
        connection(
            "southleaf_admin_it", "other_cli@%", "NONE", "", exactGrants(), new ArrayList<>()));
    assertRejected(
        connection(
            "southleaf_admin_it",
            "account_cli@%",
            "ops_role",
            "",
            exactGrants(),
            new ArrayList<>()));
    assertRejected(
        connection(
            "southleaf_admin_it",
            "account_cli@%",
            "NONE",
            "ops_role",
            exactGrants(),
            new ArrayList<>()));
    assertRejected(
        connection(
            "southleaf_admin_it",
            "account_cli@%",
            "NONE",
            "",
            append(
                exactGrants(),
                "GRANT SELECT ON `southleaf_admin_it`.`admin_session` TO `account_cli`@`%`"),
            new ArrayList<>()));
  }

  private static void assertRejected(Connection connection) {
    assertThatThrownBy(() -> AccountCliDatabaseVerifier.verify(connection, properties()))
        .isInstanceOf(AccountCliException.class)
        .extracting("reason")
        .isEqualTo(AccountCliException.Reason.DATABASE_CHECK);
  }

  private static AccountCliProperties properties() {
    return AccountCliProperties.load(
        Map.of(
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
            "account_cli@%"));
  }

  private static List<String> exactGrants() {
    return List.of(
        "GRANT USAGE ON *.* TO `account_cli`@`%`",
        "GRANT SELECT, UPDATE ON `southleaf_admin_it`.`admin_bootstrap_guard` TO `account_cli`@`%`",
        "GRANT SELECT, INSERT, UPDATE ON `southleaf_admin_it`.`admin_account` TO `account_cli`@`%`",
        "GRANT SELECT, INSERT, DELETE ON `southleaf_admin_it`.`admin_account_permission` TO `account_cli`@`%`",
        "GRANT SELECT, INSERT ON `southleaf_admin_it`.`admin_account_command` TO `account_cli`@`%`");
  }

  private static List<String> append(List<String> original, String extra) {
    var values = new ArrayList<>(original);
    values.add(extra);
    return List.copyOf(values);
  }

  private static Connection connection(
      String schema,
      String identity,
      String currentRole,
      String mandatoryRoles,
      List<String> grants,
      List<String> queries) {
    return proxy(
        Connection.class,
        (_proxy, method, arguments) -> {
          if (method.getName().equals("createStatement")) {
            return statement(schema, identity, currentRole, mandatoryRoles, grants, queries);
          }
          return defaultValue(method.getReturnType());
        });
  }

  private static Statement statement(
      String schema,
      String identity,
      String currentRole,
      String mandatoryRoles,
      List<String> grants,
      List<String> queries) {
    return proxy(
        Statement.class,
        (_proxy, method, arguments) -> {
          if (method.getName().equals("executeQuery")) {
            String query = (String) arguments[0];
            queries.add(query);
            return switch (query) {
              case "SELECT DATABASE()" -> rows(List.of(schema));
              case "SELECT CURRENT_USER()" -> rows(List.of(identity));
              case "SELECT CURRENT_ROLE()" -> rows(List.of(currentRole));
              case "SELECT @@GLOBAL.mandatory_roles" -> rows(List.of(mandatoryRoles));
              case "SHOW GRANTS FOR CURRENT_USER()" -> rows(grants);
              default -> throw new AssertionError("Unexpected query: " + query);
            };
          }
          return defaultValue(method.getReturnType());
        });
  }

  private static ResultSet rows(List<String> values) {
    int[] index = {-1};
    return proxy(
        ResultSet.class,
        (_proxy, method, arguments) -> {
          if (method.getName().equals("next")) return ++index[0] < values.size();
          if (method.getName().equals("getString")) return values.get(index[0]);
          return defaultValue(method.getReturnType());
        });
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private static Object defaultValue(Class<?> type) {
    if (type == void.class || !type.isPrimitive()) return null;
    if (type == boolean.class) return false;
    if (type == byte.class) return (byte) 0;
    if (type == short.class) return (short) 0;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    if (type == float.class) return 0F;
    if (type == double.class) return 0D;
    if (type == char.class) return '\0';
    throw new AssertionError("Unexpected primitive " + type);
  }
}
