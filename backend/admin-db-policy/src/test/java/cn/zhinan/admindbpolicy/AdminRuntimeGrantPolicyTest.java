package cn.zhinan.admindbpolicy;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdminRuntimeGrantPolicyTest {
  private static final String TO = " TO `fixture_writer`@`localhost`";

  private static List<String> grants() {
    return new ArrayList<>(
        List.of(
            "GRANT USAGE ON *.*" + TO,
            "GRANT SELECT ON `southleaf_admin_it`.`admin_account`" + TO,
            "GRANT SELECT ON `southleaf_admin_it`.`admin_account_permission`" + TO,
            "GRANT SELECT, INSERT, UPDATE ON `southleaf_admin_it`.`admin_session`" + TO,
            "GRANT INSERT ON `southleaf_admin_it`.`admin_security_audit`" + TO,
            "GRANT INSERT ON `southleaf_admin_it`.`admin_auth_event`" + TO));
  }

  private static void validate(List<String> rows) {
    AdminRuntimeGrantPolicy.validate("southleaf_admin_it", "fixture_writer@localhost", rows);
  }

  @Test
  void exactMatrixAndOrderingPass() {
    var g = grants();
    validate(g);
    Collections.reverse(g);
    validate(g);
  }

  @Test
  void splitNonoverlappingPrivilegesPass() {
    var g = grants();
    g.remove(3);
    for (String p : List.of("SELECT", "INSERT", "UPDATE"))
      g.add("GRANT " + p + " ON `southleaf_admin_it`.`admin_session`" + TO);
    validate(g);
  }

  @Test
  void everyRequiredGrantIsRequired() {
    for (int i = 0; i < grants().size(); i++) {
      var g = grants();
      g.remove(i);
      assertThatThrownBy(() -> validate(g)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "GRANT SELECT ON *.*",
        "GRANT SELECT ON `southleaf_admin_it`.*",
        "GRANT ALL PRIVILEGES ON `southleaf_admin_it`.`admin_session`",
        "GRANT DELETE ON `southleaf_admin_it`.`admin_session`",
        "GRANT SELECT ON `southleaf_admin_it`.`admin_auth_event`",
        "GRANT UPDATE ON `southleaf_admin_it`.`admin_auth_event`",
        "GRANT DELETE ON `southleaf_admin_it`.`admin_auth_event`",
        "GRANT CREATE ON `southleaf_admin_it`.`admin_session`",
        "GRANT UPDATE ON `southleaf_admin_it`.`admin_account`",
        "GRANT SELECT ON `southleaf_admin_it`.`flyway_admin_schema_history`",
        "GRANT SELECT ON `southleaf_public`.`admin_account`",
        "GRANT SELECT (`enabled`) ON `southleaf_admin_it`.`admin_account`",
        "GRANT `role_reader`@`%`",
        "GRANT SYSTEM_USER ON *.*",
        "REVOKE SELECT ON `southleaf_admin_it`.`admin_account`",
        "GRANT USAGE ON *.*",
        "GRANT SELECT ON `southleaf_admin_it`.`admin_account`"
      })
  void rejectsAnyAdditionalGrant(String row) {
    var g = grants();
    g.add(row + TO);
    assertThatThrownBy(() -> validate(g)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {" WITH GRANT OPTION", ";", " REQUIRE SSL", " ", "\nGRANT SELECT ON *.*"})
  void rejectsUnrecognizedSuffixes(String suffix) {
    var g = grants();
    g.set(1, g.get(1) + suffix);
    assertThatThrownBy(() -> validate(g)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsPartialPrivilegeSetAndRecipientMismatch() {
    var g = grants();
    g.set(3, g.get(3).replace(", UPDATE", ""));
    assertThatThrownBy(() -> validate(g)).isInstanceOf(IllegalArgumentException.class);
    var wrong = grants();
    wrong.set(1, wrong.get(1).replace("`localhost`", "`%`"));
    assertThatThrownBy(() -> validate(wrong)).isInstanceOf(IllegalArgumentException.class);
    var duplicate = grants();
    duplicate.set(3, duplicate.get(3).replace("SELECT, INSERT", "SELECT, SELECT, INSERT"));
    assertThatThrownBy(() -> validate(duplicate)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsEmptyNullAndOversizedOutput() {
    assertThatThrownBy(() -> validate(List.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> validate(null)).isInstanceOf(IllegalArgumentException.class);
    var g = grants();
    g.add(null);
    assertThatThrownBy(() -> validate(g)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> validate(Collections.nCopies(65, grants().getFirst())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> validate(List.of("x".repeat(4097))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
