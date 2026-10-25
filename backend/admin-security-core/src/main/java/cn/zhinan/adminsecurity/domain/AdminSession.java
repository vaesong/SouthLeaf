package cn.zhinan.adminsecurity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Persist only a digest of a random bearer secret. No password or raw token. */
public record AdminSession(
    String tokenDigest,
    String accountId,
    long securityVersion,
    Instant issuedAt,
    Instant expiresAt) {
  public static final Duration MAX_LIFETIME = Duration.ofHours(8);

  public AdminSession {
    if (tokenDigest == null || !tokenDigest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Invalid session digest");
    }
    AdminAccount.requireAccountId(accountId);
    if (securityVersion < 0) throw new IllegalArgumentException("Invalid security version");
    Objects.requireNonNull(issuedAt);
    Objects.requireNonNull(expiresAt);
    Duration lifetime = Duration.between(issuedAt, expiresAt);
    if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(MAX_LIFETIME) > 0) {
      throw new IllegalArgumentException("Invalid session lifetime");
    }
  }

  public boolean activeFor(AdminAccount account, Instant now) {
    Objects.requireNonNull(now);
    return account != null
        && account.enabled()
        && accountId.equals(account.accountId())
        && securityVersion == account.securityVersion()
        && !now.isBefore(issuedAt)
        && now.isBefore(expiresAt);
  }
}
