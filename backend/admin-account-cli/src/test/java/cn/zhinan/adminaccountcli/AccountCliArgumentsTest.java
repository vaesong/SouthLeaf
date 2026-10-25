package cn.zhinan.adminaccountcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.adminsecurity.domain.AdminPermission;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountCliArgumentsTest {
  private static final String COMMAND = "00000000-0000-0000-0000-000000000001";

  @Test
  void parsesBootstrapWithRepeatedExplicitPermissions() {
    var parsed =
        AccountCliArguments.parse(
            new String[] {
              "bootstrap",
              "--command-id",
              COMMAND,
              "--account",
              "ops.bootstrap",
              "--permission",
              "STAFF_ACCESS_MANAGE",
              "--permission",
              "AUDIT_READ"
            });

    assertThat(parsed.action()).isEqualTo(AccountCliArguments.Action.BOOTSTRAP);
    assertThat(parsed.commandId()).isEqualTo(UUID.fromString(COMMAND));
    assertThat(parsed.permissions())
        .containsExactlyInAnyOrder(AdminPermission.STAFF_ACCESS_MANAGE, AdminPermission.AUDIT_READ);
  }

  @Test
  void parsesNonSecretCommandsWithoutASecretFlag() {
    var parsed =
        AccountCliArguments.parse(
            new String[] {
              "disable-account",
              "--command-id",
              COMMAND,
              "--account",
              "ops.one",
              "--expected-version",
              "7"
            });

    assertThat(parsed.expectedVersion()).isEqualTo(7L);
    assertThat(parsed.permissions()).isEmpty();
  }

  @Test
  void rejectsPasswordSecretUnknownAndDuplicateFlags() {
    assertRejected("bootstrap", "--command-id", COMMAND, "--password", "not-allowed");
    assertRejected("command-result", "--command-id", COMMAND, "--account", "ops.one");
    assertRejected(
        "disable-account",
        "--command-id",
        COMMAND,
        "--command-id",
        "00000000-0000-0000-0000-000000000002",
        "--account",
        "ops.one",
        "--expected-version",
        "0");
  }

  private static void assertRejected(String... args) {
    assertThatThrownBy(() -> AccountCliArguments.parse(args))
        .isInstanceOf(AccountCliException.class)
        .extracting("reason")
        .isEqualTo(AccountCliException.Reason.ARGUMENT);
  }
}
