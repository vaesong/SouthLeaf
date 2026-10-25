package cn.zhinan.uniapp.booking;

/** Failure of a public booking call; the controller maps it onto the shared public envelope. */
public class PublicBookingException extends RuntimeException {
  public enum ErrorCode {
    REQUEST_INVALID,
    SESSION_REQUIRED,
    IDENTITY_REJECTED,
    IDENTITY_PROVIDER_UNCONFIGURED,
    PACKAGE_CATALOG_UNCONFIGURED,
    SLOT_UNAVAILABLE,
    REQUEST_ALREADY_OPEN,
    RESOURCE_UNAVAILABLE,
    SERVICE_UNAVAILABLE
  }

  private final ErrorCode errorCode;

  public PublicBookingException(ErrorCode errorCode) {
    this(errorCode, errorCode.name());
  }

  public PublicBookingException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public ErrorCode getErrorCode() {
    return errorCode;
  }
}
