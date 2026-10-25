package cn.zhinan.adminruntime;

/** Deliberately contains no external value, cause, SQL or credential. */
final class AdminStartupException extends RuntimeException {
  enum Reason {
    CONFIGURATION,
    CONFIG_SOURCE,
    ARGUMENTS,
    DATABASE_CHECK,
    IDENTITY,
    TLS,
    ROLES,
    GRANTS,
    INTERNAL
  }

  private final Reason reason;

  AdminStartupException(Reason reason) {
    super("ADMIN_RUNTIME_REFUSED:" + reason.name());
    this.reason = reason;
  }

  Reason reason() {
    return reason;
  }
}
