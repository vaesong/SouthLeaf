package cn.zhinan.adminsecurity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Only trusted correlation and a known account ID; never attempted raw names, hashes or secrets.
 */
public record AuthenticationEvent(
    Kind kind, String knownAccountId, UUID requestId, Instant occurredAt) {
  public enum Kind {
    LOGIN_DENIED,
    CREDENTIAL_UPGRADE_REQUIRED
  }

  public AuthenticationEvent {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(occurredAt);
    if (knownAccountId != null) AdminAccount.requireAccountId(knownAccountId);
    if (kind == Kind.CREDENTIAL_UPGRADE_REQUIRED && knownAccountId == null)
      throw new IllegalArgumentException("Missing known account");
  }
}
