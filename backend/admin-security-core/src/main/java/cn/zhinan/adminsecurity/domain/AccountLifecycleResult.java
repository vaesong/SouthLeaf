package cn.zhinan.adminsecurity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Non-secret durable result that the CLI may return or query by command ID. */
public record AccountLifecycleResult(
    UUID commandId,
    AccountLifecycleOperation operation,
    String accountId,
    long securityVersion,
    Instant occurredAt) {
  public AccountLifecycleResult {
    Objects.requireNonNull(commandId);
    Objects.requireNonNull(operation);
    AdminAccount.requireAccountId(accountId);
    if (securityVersion < 0) throw new IllegalArgumentException("Invalid security version");
    Objects.requireNonNull(occurredAt);
  }
}
