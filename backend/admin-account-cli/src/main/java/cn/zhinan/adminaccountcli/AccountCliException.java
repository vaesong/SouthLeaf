package cn.zhinan.adminaccountcli;

/**
 * Stable non-secret process result. Cause details, URLs and database messages never cross this
 * boundary.
 */
final class AccountCliException extends RuntimeException {
  enum Reason {
    ARGUMENT,
    CONFIGURATION,
    SECURE_INPUT,
    DATABASE_CHECK,
    COMMAND_REJECTED,
    DEPENDENCY
  }

  private final Reason reason;

  AccountCliException(Reason reason) {
    super("Account CLI rejected");
    this.reason = reason;
  }

  Reason reason() {
    return reason;
  }
}
