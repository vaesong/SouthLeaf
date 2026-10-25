package cn.zhinan.adminaccountcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.admincredentialcrypto.Argon2PasswordService;
import cn.zhinan.adminsecurity.application.AdminAccountLifecycleService;
import cn.zhinan.adminsecurity.domain.AccountLifecycleException;
import cn.zhinan.adminsecurity.domain.AccountLifecycleResult;
import cn.zhinan.adminsecurity.domain.AccountPasswordPolicy;
import cn.zhinan.adminsecurity.domain.AdminPermission;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Runs only in the explicit c3-isolated-it Maven profile against its disposable owned fixture. */
class AccountLifecycleRealIT {
  private static final String ACCOUNT = "c3.bootstrap";

  @Test
  void serializesBootstrapAndPreservesVersionedIdempotentLifecycleFacts() throws Exception {
    UUID firstCommand = UUID.randomUUID();
    UUID secondCommand = UUID.randomUUID();
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<Outcome> first = executor.submit(bootstrap(start, firstCommand));
      Future<Outcome> second = executor.submit(bootstrap(start, secondCommand));
      start.countDown();
      Outcome one = first.get();
      Outcome two = second.get();
      assertThat(java.util.stream.Stream.of(one, two).filter(Outcome::success).count())
          .isEqualTo(1);
      assertThat(
              java.util.stream.Stream.of(one, two)
                  .filter(Outcome::success)
                  .findFirst()
                  .orElseThrow()
                  .result())
          .extracting(AccountLifecycleResult::securityVersion)
          .isEqualTo(0L);
      assertThat(
              java.util.stream.Stream.of(one, two)
                  .filter(outcome -> !outcome.success())
                  .map(Outcome::reason))
          .containsExactly(AccountLifecycleException.Reason.BOOTSTRAP_ALREADY_COMPLETED);
    }

    UUID firstPasswordCommand = UUID.randomUUID();
    UUID permissionCommand = UUID.randomUUID();
    UUID secondPasswordCommand = UUID.randomUUID();
    try (var context = context()) {
      assertThat(changePassword(context.lifecycle(), firstPasswordCommand, 0).securityVersion())
          .isEqualTo(1L);
      var replacement =
          context
              .lifecycle()
              .replacePermissions(
                  permissionCommand,
                  ACCOUNT,
                  1,
                  Set.of(AdminPermission.STAFF_ACCESS_MANAGE, AdminPermission.AUDIT_READ));
      assertThat(replacement.securityVersion()).isEqualTo(2L);
      assertThat(
              context
                  .lifecycle()
                  .replacePermissions(
                      permissionCommand,
                      ACCOUNT,
                      1,
                      Set.of(AdminPermission.AUDIT_READ, AdminPermission.STAFF_ACCESS_MANAGE)))
          .isEqualTo(replacement);
      assertThatThrownBy(
              () ->
                  context
                      .lifecycle()
                      .replacePermissions(
                          UUID.randomUUID(),
                          ACCOUNT,
                          1,
                          Set.of(AdminPermission.STAFF_ACCESS_MANAGE)))
          .isInstanceOf(AccountLifecycleException.class)
          .extracting("reason")
          .isEqualTo(AccountLifecycleException.Reason.VERSION_CONFLICT);
      assertThat(changePassword(context.lifecycle(), secondPasswordCommand, 2).securityVersion())
          .isEqualTo(3L);
      assertThatThrownBy(() -> changePassword(context.lifecycle(), secondPasswordCommand, 3))
          .isInstanceOf(AccountLifecycleException.class)
          .extracting("reason")
          .isEqualTo(AccountLifecycleException.Reason.SECRET_RETRY_REQUIRES_RESULT_QUERY);
      assertThatThrownBy(() -> context.lifecycle().disableAccount(UUID.randomUUID(), ACCOUNT, 3))
          .isInstanceOf(AccountLifecycleException.class)
          .extracting("reason")
          .isEqualTo(AccountLifecycleException.Reason.LAST_MAINTAINER);
      assertThat(context.lifecycle().commandResult(permissionCommand)).isEqualTo(replacement);
    }
  }

  private static Callable<Outcome> bootstrap(CountDownLatch start, UUID commandId) {
    return () -> {
      start.await();
      try (var context = context()) {
        return Outcome.success(
            bootstrap(
                context.lifecycle(),
                commandId,
                Set.of(AdminPermission.STAFF_ACCESS_MANAGE, AdminPermission.AUDIT_READ)));
      } catch (AccountLifecycleException ex) {
        return Outcome.rejected(ex.reason());
      }
    };
  }

  private static AccountLifecycleResult bootstrap(
      AdminAccountLifecycleService lifecycle, UUID commandId, Set<AdminPermission> permissions) {
    char[] password = password();
    try {
      return lifecycle.bootstrap(commandId, ACCOUNT, password, permissions);
    } finally {
      Arrays.fill(password, '\0');
    }
  }

  private static AccountLifecycleResult changePassword(
      AdminAccountLifecycleService lifecycle, UUID commandId, long expectedVersion) {
    char[] password = password();
    try {
      return lifecycle.changePassword(commandId, ACCOUNT, expectedVersion, password);
    } finally {
      Arrays.fill(password, '\0');
    }
  }

  private static char[] password() {
    return ("C3-isolated-" + UUID.randomUUID()).toCharArray();
  }

  private static Context context() {
    var database = AccountCliDatabaseVerifier.open(properties());
    return new Context(
        database,
        new AdminAccountLifecycleService(
            new JdbcAccountLifecycleStore(database.dataSource(), database.executionIdentity()),
            new Argon2PasswordService(),
            new AccountPasswordPolicy()));
  }

  private static AccountCliProperties properties() {
    return AccountCliProperties.load(
        Map.of(
            "SOUTHLEAF_ACCOUNT_CLI_MODE",
            required("SOUTHLEAF_C3_IT_MODE"),
            "SOUTHLEAF_ACCOUNT_CLI_DB_URL",
            required("SOUTHLEAF_C3_IT_DB_URL"),
            "SOUTHLEAF_ACCOUNT_CLI_DB_USERNAME",
            required("SOUTHLEAF_C3_IT_DB_USERNAME"),
            "SOUTHLEAF_ACCOUNT_CLI_DB_PASSWORD",
            required("SOUTHLEAF_C3_IT_DB_PASSWORD"),
            "SOUTHLEAF_ACCOUNT_CLI_DB_SCHEMA",
            required("SOUTHLEAF_C3_IT_DB_SCHEMA"),
            "SOUTHLEAF_ACCOUNT_CLI_DB_EXPECTED_USER",
            required("SOUTHLEAF_C3_IT_DB_EXPECTED_USER")));
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Missing explicit C3 fixture input");
    return value;
  }

  private record Context(
      AccountCliDatabaseVerifier.VerifiedDatabase database, AdminAccountLifecycleService lifecycle)
      implements AutoCloseable {
    @Override
    public void close() {
      database.close();
    }
  }

  private record Outcome(AccountLifecycleResult result, AccountLifecycleException.Reason reason) {
    static Outcome success(AccountLifecycleResult result) {
      return new Outcome(result, null);
    }

    static Outcome rejected(AccountLifecycleException.Reason reason) {
      return new Outcome(null, reason);
    }

    boolean success() {
      return result != null;
    }
  }
}
