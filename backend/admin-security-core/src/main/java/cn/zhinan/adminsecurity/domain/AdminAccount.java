package cn.zhinan.adminsecurity.domain;

import java.util.Objects;
import java.util.Set;

/** Security version changes on credential reset, disablement or access changes. */
public record AdminAccount(
    String accountId, boolean enabled, long securityVersion, Set<AdminPermission> permissions) {
  public AdminAccount {
    requireAccountId(accountId);
    if (securityVersion < 0) throw new IllegalArgumentException("Invalid security version");
    permissions = Set.copyOf(Objects.requireNonNull(permissions));
  }

  /** Login compatibility includes existing one-character IDs; CLI creation requires 3–64. */
  public static boolean isValidAccountId(String accountId) {
    return accountId != null && accountId.matches("[A-Za-z0-9._-]{1,64}");
  }

  public static void requireAccountId(String accountId) {
    if (!isValidAccountId(accountId)) {
      throw new IllegalArgumentException("Invalid account identifier");
    }
  }
}
