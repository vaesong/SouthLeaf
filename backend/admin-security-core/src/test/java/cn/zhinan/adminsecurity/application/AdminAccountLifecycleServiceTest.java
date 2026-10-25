package cn.zhinan.adminsecurity.application;

import static org.assertj.core.api.Assertions.*;

import cn.zhinan.adminsecurity.domain.*;
import cn.zhinan.adminsecurity.port.AccountLifecycleStore;
import cn.zhinan.adminsecurity.port.PasswordHasher;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class AdminAccountLifecycleServiceTest {
  private final FakeStore store = new FakeStore();
  private final CapturingHasher hasher = new CapturingHasher();
  private final AdminAccountLifecycleService service =
      new AdminAccountLifecycleService(store, hasher, new AccountPasswordPolicy());

  @Test
  void bootstrapRequiresNewIdAndExplicitMaintenancePermissionBeforeHashing() {
    var id = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                service.bootstrap(
                    id,
                    "ab",
                    "a very long secret".toCharArray(),
                    Set.of(AdminPermission.STAFF_ACCESS_MANAGE)))
        .isInstanceOfSatisfying(
            AccountLifecycleException.class,
            ex ->
                assertThat(ex.reason())
                    .isEqualTo(AccountLifecycleException.Reason.INVALID_ARGUMENT));
    assertThatThrownBy(
            () ->
                service.bootstrap(
                    UUID.randomUUID(),
                    "owner",
                    "a very long secret".toCharArray(),
                    Set.of(AdminPermission.CONTENT_READ)))
        .isInstanceOf(AccountLifecycleException.class);
    assertThat(hasher.calls).isZero();
  }

  @Test
  void hashesOwnedCopyAndClearsItWithoutChangingCallerPassword() {
    char[] caller = "a very long secret".toCharArray();
    char[] snapshot = caller.clone();
    var result =
        service.bootstrap(
            UUID.randomUUID(), "owner", caller, Set.of(AdminPermission.STAFF_ACCESS_MANAGE));
    assertThat(result.securityVersion()).isEqualTo(1);
    assertThat(caller).isEqualTo(snapshot);
    assertThat(hasher.seen).containsOnly('\0');
    assertThat(store.bootstrap.credentialHash()).isEqualTo("hash");
  }

  @Test
  void duplicateSecretCommandRequiresResultQueryWithoutHashingAgain() {
    UUID id = UUID.randomUUID();
    store.results.put(id, result(id, AccountLifecycleOperation.CHANGE_PASSWORD, "owner", 4));
    assertThatThrownBy(
            () -> service.changePassword(id, "owner", 4, "a very long secret".toCharArray()))
        .isInstanceOfSatisfying(
            AccountLifecycleException.class,
            ex ->
                assertThat(ex.reason())
                    .isEqualTo(
                        AccountLifecycleException.Reason.SECRET_RETRY_REQUIRES_RESULT_QUERY));
    assertThat(hasher.calls).isZero();
    assertThat(service.commandResult(id).securityVersion()).isEqualTo(4);
  }

  @Test
  void replacementFingerprintIsStableAcrossPermissionSetIterationOrder() {
    service.replacePermissions(
        UUID.randomUUID(),
        "owner",
        8,
        Set.of(AdminPermission.CONTENT_READ, AdminPermission.AUDIT_READ));
    byte[] first = store.replacement.payloadSha256();
    service.replacePermissions(
        UUID.randomUUID(),
        "owner",
        8,
        Set.of(AdminPermission.AUDIT_READ, AdminPermission.CONTENT_READ));
    assertThat(store.replacement.payloadSha256()).isEqualTo(first);
  }

  @Test
  void passwordPolicyRejectsShortAndKnownCommonValues() {
    assertThatThrownBy(
            () -> service.changePassword(UUID.randomUUID(), "owner", 1, "short".toCharArray()))
        .isInstanceOf(AccountLifecycleException.class);
    assertThatThrownBy(
            () ->
                service.changePassword(
                    UUID.randomUUID(), "owner", 1, "PasswordPassword".toCharArray()))
        .isInstanceOfSatisfying(
            AccountLifecycleException.class,
            ex ->
                assertThat(ex.reason())
                    .isEqualTo(AccountLifecycleException.Reason.PASSWORD_POLICY));
  }

  private static AccountLifecycleResult result(
      UUID id, AccountLifecycleOperation op, String account, long version) {
    return new AccountLifecycleResult(
        id, op, account, version, Instant.parse("2026-10-24T00:00:00Z"));
  }

  private static final class CapturingHasher implements PasswordHasher {
    int calls;
    char[] seen;

    @Override
    public String hash(char[] password) {
      calls++;
      seen = password;
      return "hash";
    }
  }

  private static final class FakeStore implements AccountLifecycleStore {
    final Map<UUID, AccountLifecycleResult> results = new HashMap<>();
    Bootstrap bootstrap;
    PermissionReplacement replacement;

    @Override
    public Optional<AccountLifecycleResult> findResult(UUID commandId) {
      return Optional.ofNullable(results.get(commandId));
    }

    @Override
    public AccountLifecycleResult bootstrap(Bootstrap command) {
      bootstrap = command;
      var result =
          result(command.commandId(), AccountLifecycleOperation.BOOTSTRAP, command.accountId(), 1);
      results.put(command.commandId(), result);
      return result;
    }

    @Override
    public AccountLifecycleResult changePassword(PasswordChange command) {
      var result =
          result(
              command.commandId(),
              AccountLifecycleOperation.CHANGE_PASSWORD,
              command.accountId(),
              command.expectedSecurityVersion() + 1);
      results.put(command.commandId(), result);
      return result;
    }

    @Override
    public AccountLifecycleResult disableAccount(Disable command) {
      var result =
          result(
              command.commandId(),
              AccountLifecycleOperation.DISABLE_ACCOUNT,
              command.accountId(),
              command.expectedSecurityVersion() + 1);
      results.put(command.commandId(), result);
      return result;
    }

    @Override
    public AccountLifecycleResult replacePermissions(PermissionReplacement command) {
      replacement = command;
      var result =
          result(
              command.commandId(),
              AccountLifecycleOperation.REPLACE_PERMISSIONS,
              command.accountId(),
              command.expectedSecurityVersion() + 1);
      results.put(command.commandId(), result);
      return result;
    }
  }
}
