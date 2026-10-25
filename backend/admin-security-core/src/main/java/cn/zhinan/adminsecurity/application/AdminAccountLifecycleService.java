package cn.zhinan.adminsecurity.application;

import cn.zhinan.adminsecurity.domain.AccountLifecycleException;
import cn.zhinan.adminsecurity.domain.AccountLifecycleOperation;
import cn.zhinan.adminsecurity.domain.AccountLifecycleResult;
import cn.zhinan.adminsecurity.domain.AccountPasswordPolicy;
import cn.zhinan.adminsecurity.domain.AdminAccount;
import cn.zhinan.adminsecurity.domain.AdminPermission;
import cn.zhinan.adminsecurity.port.AccountLifecycleStore;
import cn.zhinan.adminsecurity.port.PasswordHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Pure C3 orchestration. It never accepts account lifecycle secrets from an HTTP caller. */
public final class AdminAccountLifecycleService {
  private final AccountLifecycleStore store;
  private final PasswordHasher hasher;
  private final AccountPasswordPolicy passwordPolicy;

  public AdminAccountLifecycleService(
      AccountLifecycleStore store, PasswordHasher hasher, AccountPasswordPolicy passwordPolicy) {
    this.store = Objects.requireNonNull(store);
    this.hasher = Objects.requireNonNull(hasher);
    this.passwordPolicy = Objects.requireNonNull(passwordPolicy);
  }

  public AccountLifecycleResult bootstrap(
      UUID commandId, String accountId, char[] password, Set<AdminPermission> permissions) {
    requireNewAccountId(accountId);
    requirePermissions(permissions);
    if (!permissions.contains(AdminPermission.STAFF_ACCESS_MANAGE)) invalid();
    requireUnusedSecretCommand(commandId);
    return store.bootstrap(
        new AccountLifecycleStore.Bootstrap(
            commandId, accountId, hashNewPassword(password), permissions));
  }

  public AccountLifecycleResult changePassword(
      UUID commandId, String accountId, long expectedSecurityVersion, char[] password) {
    requireExistingAccountId(accountId);
    requireVersion(expectedSecurityVersion);
    requireUnusedSecretCommand(commandId);
    return store.changePassword(
        new AccountLifecycleStore.PasswordChange(
            commandId, accountId, expectedSecurityVersion, hashNewPassword(password)));
  }

  public AccountLifecycleResult disableAccount(
      UUID commandId, String accountId, long expectedSecurityVersion) {
    requireExistingAccountId(accountId);
    requireVersion(expectedSecurityVersion);
    return store.disableAccount(
        new AccountLifecycleStore.Disable(
            commandId,
            accountId,
            expectedSecurityVersion,
            fingerprint(
                AccountLifecycleOperation.DISABLE_ACCOUNT,
                accountId,
                expectedSecurityVersion,
                Set.of())));
  }

  public AccountLifecycleResult replacePermissions(
      UUID commandId,
      String accountId,
      long expectedSecurityVersion,
      Set<AdminPermission> permissions) {
    requireExistingAccountId(accountId);
    requireVersion(expectedSecurityVersion);
    requirePermissions(permissions);
    return store.replacePermissions(
        new AccountLifecycleStore.PermissionReplacement(
            commandId,
            accountId,
            expectedSecurityVersion,
            permissions,
            fingerprint(
                AccountLifecycleOperation.REPLACE_PERMISSIONS,
                accountId,
                expectedSecurityVersion,
                permissions)));
  }

  public AccountLifecycleResult commandResult(UUID commandId) {
    if (commandId == null) invalid();
    return store
        .findResult(commandId)
        .orElseThrow(
            () ->
                new AccountLifecycleException(AccountLifecycleException.Reason.COMMAND_NOT_FOUND));
  }

  private void requireUnusedSecretCommand(UUID commandId) {
    if (commandId == null) invalid();
    if (store.findResult(commandId).isPresent()) {
      throw new AccountLifecycleException(
          AccountLifecycleException.Reason.SECRET_RETRY_REQUIRES_RESULT_QUERY);
    }
  }

  private String hashNewPassword(char[] supplied) {
    if (supplied == null) invalid();
    char[] owned = supplied.clone();
    try {
      passwordPolicy.requireAcceptable(owned);
      return hasher.hash(owned);
    } finally {
      Arrays.fill(owned, '\0');
    }
  }

  private static byte[] fingerprint(
      AccountLifecycleOperation operation,
      String accountId,
      long expectedSecurityVersion,
      Set<AdminPermission> permissions) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      String canonical =
          operation.name()
              + "\n"
              + accountId
              + "\n"
              + expectedSecurityVersion
              + "\n"
              + permissions.stream()
                  .map(Enum::name)
                  .sorted(Comparator.naturalOrder())
                  .reduce((left, right) -> left + "," + right)
                  .orElse("");
      return digest.digest(canonical.getBytes(StandardCharsets.US_ASCII));
    } catch (NoSuchAlgorithmException ex) {
      throw new AssertionError(ex);
    }
  }

  private static void requireNewAccountId(String accountId) {
    if (accountId == null || !accountId.matches("[A-Za-z0-9._-]{3,64}")) invalid();
  }

  private static void requireExistingAccountId(String accountId) {
    try {
      AdminAccount.requireAccountId(accountId);
    } catch (IllegalArgumentException ex) {
      invalid();
    }
  }

  private static void requireVersion(long version) {
    if (version < 0) invalid();
  }

  private static void requirePermissions(Set<AdminPermission> permissions) {
    if (permissions == null
        || permissions.isEmpty()
        || permissions.stream().anyMatch(java.util.Objects::isNull)) invalid();
  }

  private static void invalid() {
    throw new AccountLifecycleException(AccountLifecycleException.Reason.INVALID_ARGUMENT);
  }
}
