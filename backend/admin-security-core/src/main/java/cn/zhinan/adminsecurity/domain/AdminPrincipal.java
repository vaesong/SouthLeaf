package cn.zhinan.adminsecurity.domain;

import java.util.Objects;
import java.util.Set;

/** Snapshot of current effective permissions for a single application operation. */
public record AdminPrincipal(String accountId, Set<AdminPermission> permissions) {
  public AdminPrincipal {
    AdminAccount.requireAccountId(accountId);
    permissions = Set.copyOf(Objects.requireNonNull(permissions));
  }
}
