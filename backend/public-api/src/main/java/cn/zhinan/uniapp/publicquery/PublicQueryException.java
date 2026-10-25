package cn.zhinan.uniapp.publicquery;

public class PublicQueryException extends RuntimeException {
  private final ErrorCode errorCode;

  public PublicQueryException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public ErrorCode getErrorCode() {
    return errorCode;
  }

  public enum ErrorCode {
    PUBLIC_QUERY_INVALID,
    PUBLIC_CURSOR_EXPIRED,
    PUBLIC_RESOURCE_UNAVAILABLE,
    PUBLIC_SERVICE_UNAVAILABLE,
    PUBLIC_MEDIA_UNAVAILABLE,
    PUBLIC_RESPONSE_INVALID
  }
}
