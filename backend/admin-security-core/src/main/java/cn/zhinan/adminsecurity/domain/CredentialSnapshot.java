package cn.zhinan.adminsecurity.domain;

/** A read-only fact, not proof that the caller knows the password. Never cache across attempts. */
public record CredentialSnapshot(
    String accountId, boolean enabled, long securityVersion, String credentialHash) {
  public CredentialSnapshot {
    AdminAccount.requireAccountId(accountId);
    if (securityVersion < 0) throw new IllegalArgumentException("Invalid security version");
    if (credentialHash == null || credentialHash.isEmpty() || credentialHash.length() > 256)
      throw new CredentialDependencyException(
          CredentialDependencyException.Reason.INVALID_STORED_HASH);
  }

  @Override
  public String toString() {
    return "CredentialSnapshot[REDACTED]";
  }
}
