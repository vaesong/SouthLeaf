package cn.zhinan.adminsecurity.domain;

/** Fixed, non-secret classifications. Never translate these into a bad-password result. */
public final class CredentialDependencyException extends RuntimeException {
  public enum Reason {
    INVALID_STORED_HASH,
    CAPACITY_EXHAUSTED,
    INCONSISTENT_LOOKUP
  }

  private final Reason reason;

  public CredentialDependencyException(Reason reason) {
    super("Credential dependency unavailable");
    this.reason = java.util.Objects.requireNonNull(reason);
  }

  public Reason reason() {
    return reason;
  }
}
