package cn.zhinan.admindbpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class AdminOutboxGrantPolicyTest {
  private static final String SCHEMA = "southleaf_admin_it";
  private static final String USER = "southleaf_outbox@localhost";
  private static final List<String> VALID =
      List.of(
          "GRANT USAGE ON *.* TO `southleaf_outbox`@`localhost`",
          "GRANT SELECT, UPDATE ON `southleaf_admin_it`.`booking_notification_outbox` TO `southleaf_outbox`@`localhost`",
          "GRANT SELECT, INSERT ON `southleaf_admin_it`.`booking_command` TO `southleaf_outbox`@`localhost`",
          "GRANT INSERT ON `southleaf_admin_it`.`booking_audit` TO `southleaf_outbox`@`localhost`");

  @Test
  void theRequiredSetIsLimitedToDispatchAndAuditTables() {
    assertThat(AdminOutboxGrantPolicy.required(SCHEMA))
        .containsExactlyInAnyOrder(
            SCHEMA + ":booking_notification_outbox:SELECT",
            SCHEMA + ":booking_notification_outbox:UPDATE",
            SCHEMA + ":booking_command:SELECT",
            SCHEMA + ":booking_command:INSERT",
            SCHEMA + ":booking_audit:INSERT");
  }

  @Test
  void exactOutboxGrantsAreAccepted() {
    AdminOutboxGrantPolicy.validate(SCHEMA, USER, VALID);
  }

  @Test
  void missingOrExcessPrivilegeIsRejected() {
    assertThatThrownBy(() -> AdminOutboxGrantPolicy.validate(SCHEMA, USER, VALID.subList(0, 3)))
        .isInstanceOf(IllegalArgumentException.class);
    var excessive = new java.util.ArrayList<>(VALID);
    excessive.add(
        "GRANT DELETE ON `southleaf_admin_it`.`booking_audit` TO `southleaf_outbox`@`localhost`");
    assertThatThrownBy(() -> AdminOutboxGrantPolicy.validate(SCHEMA, USER, excessive))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void broadSchemaGrantAndWrongIdentityAreRejected() {
    var broad = new java.util.ArrayList<>(VALID);
    broad.set(
        1, "GRANT SELECT, UPDATE ON `southleaf_admin_it`.* TO `southleaf_outbox`@`localhost`");
    assertThatThrownBy(() -> AdminOutboxGrantPolicy.validate(SCHEMA, USER, broad))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> AdminOutboxGrantPolicy.validate(SCHEMA, "another_user@localhost", VALID))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
