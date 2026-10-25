package cn.zhinan.admindbpolicy;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class AdminAccountCliGrantPolicyTest {
  private static final String SCHEMA = "southleaf_admin_c3it";
  private static final String USER = "c3cli@localhost";

  @Test
  void acceptsOnlyTheCompleteDedicatedCliMatrix() {
    AdminAccountCliGrantPolicy.validate(
        SCHEMA,
        USER,
        List.of(
            "GRANT USAGE ON *.* TO `c3cli`@`localhost`",
            "GRANT SELECT, UPDATE ON `southleaf_admin_c3it`.`admin_bootstrap_guard` TO `c3cli`@`localhost`",
            "GRANT SELECT, INSERT, UPDATE ON `southleaf_admin_c3it`.`admin_account` TO `c3cli`@`localhost`",
            "GRANT SELECT, INSERT, DELETE ON `southleaf_admin_c3it`.`admin_account_permission` TO `c3cli`@`localhost`",
            "GRANT SELECT, INSERT ON `southleaf_admin_c3it`.`admin_account_command` TO `c3cli`@`localhost`"));
  }

  @Test
  void rejectsRuntimeSessionRightsRolesAndGrantOption() {
    for (String forbidden :
        List.of(
            "GRANT SELECT ON `southleaf_admin_c3it`.`admin_session` TO `c3cli`@`localhost`",
            "GRANT ALL PRIVILEGES ON `southleaf_admin_c3it`.* TO `c3cli`@`localhost`",
            "GRANT SELECT ON `southleaf_admin_c3it`.`admin_account` TO `role_c3`",
            "GRANT SELECT ON `southleaf_admin_c3it`.`admin_account` TO `c3cli`@`localhost` WITH GRANT OPTION")) {
      assertThatThrownBy(
              () ->
                  AdminAccountCliGrantPolicy.validate(
                      SCHEMA,
                      USER,
                      List.of("GRANT USAGE ON *.* TO `c3cli`@`localhost`", forbidden)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
