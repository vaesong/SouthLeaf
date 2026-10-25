package cn.zhinan.adminsecurity.port;

import cn.zhinan.adminsecurity.domain.AccountLifecycleResult;
import cn.zhinan.adminsecurity.domain.AdminAccount;
import cn.zhinan.adminsecurity.domain.AdminPermission;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Trusted CLI mutation port. Implementations own lock order, idempotency persistence and all
 * database transactions; runtime never receives this capability.
 */
public interface AccountLifecycleStore {
  Optional<AccountLifecycleResult> findResult(UUID commandId);

  AccountLifecycleResult bootstrap(Bootstrap command);

  AccountLifecycleResult changePassword(PasswordChange command);

  AccountLifecycleResult disableAccount(Disable command);

  AccountLifecycleResult replacePermissions(PermissionReplacement command);

  record Bootstrap(
      UUID commandId, String accountId, String credentialHash, Set<AdminPermission> permissions) {
    public Bootstrap {
      Objects.requireNonNull(commandId);
      AdminAccount.requireAccountId(accountId);
      if (credentialHash == null || credentialHash.isBlank() || credentialHash.length() > 512)
        throw new IllegalArgumentException("Invalid credential hash");
      permissions = Set.copyOf(Objects.requireNonNull(permissions));
    }
  }

  record PasswordChange(
      UUID commandId, String accountId, long expectedSecurityVersion, String credentialHash) {
    public PasswordChange {
      Objects.requireNonNull(commandId);
      AdminAccount.requireAccountId(accountId);
      if (expectedSecurityVersion < 0)
        throw new IllegalArgumentException("Invalid security version");
      if (credentialHash == null || credentialHash.isBlank() || credentialHash.length() > 512)
        throw new IllegalArgumentException("Invalid credential hash");
    }
  }

  record Disable(
      UUID commandId, String accountId, long expectedSecurityVersion, byte[] payloadSha256) {
    public Disable {
      Objects.requireNonNull(commandId);
      AdminAccount.requireAccountId(accountId);
      if (expectedSecurityVersion < 0)
        throw new IllegalArgumentException("Invalid security version");
      if (payloadSha256 == null || payloadSha256.length != 32)
        throw new IllegalArgumentException("Invalid command fingerprint");
      payloadSha256 = payloadSha256.clone();
    }

    @Override
    public byte[] payloadSha256() {
      return payloadSha256.clone();
    }
  }

  record PermissionReplacement(
      UUID commandId,
      String accountId,
      long expectedSecurityVersion,
      Set<AdminPermission> permissions,
      byte[] payloadSha256) {
    public PermissionReplacement {
      Objects.requireNonNull(commandId);
      AdminAccount.requireAccountId(accountId);
      if (expectedSecurityVersion < 0)
        throw new IllegalArgumentException("Invalid security version");
      permissions = Set.copyOf(Objects.requireNonNull(permissions));
      if (payloadSha256 == null || payloadSha256.length != 32)
        throw new IllegalArgumentException("Invalid command fingerprint");
      payloadSha256 = payloadSha256.clone();
    }

    @Override
    public byte[] payloadSha256() {
      return payloadSha256.clone();
    }
  }
}
