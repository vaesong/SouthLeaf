package cn.zhinan.adminsecurity.domain;

/** Stable non-secret lifecycle rejection classification for the dedicated account CLI. */
public final class AccountLifecycleException extends RuntimeException {
  public enum Reason {
    INVALID_ARGUMENT,
    PASSWORD_POLICY,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_ALREADY_EXISTS,
    ACCOUNT_ALREADY_DISABLED,
    BOOTSTRAP_ALREADY_COMPLETED,
    BOOTSTRAP_REQUIRED,
    VERSION_CONFLICT,
    LAST_MAINTAINER,
    COMMAND_ID_REUSED,
    SECRET_RETRY_REQUIRES_RESULT_QUERY,
    COMMAND_NOT_FOUND,
    STORE_INVARIANT
  }

  private final Reason reason;

  public AccountLifecycleException(Reason reason) {
    super("Account lifecycle command rejected");
    this.reason = java.util.Objects.requireNonNull(reason);
  }

  public Reason reason() {
    return reason;
  }
}
