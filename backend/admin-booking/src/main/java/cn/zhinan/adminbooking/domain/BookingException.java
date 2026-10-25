package cn.zhinan.adminbooking.domain;

/**
 * Carries only a category and a fixed reason code; never SQL, contact values, external subjects,
 * object keys or user content. Stack traces and messages are suppressed so that a leaked frame
 * cannot carry adapter detail.
 */
public final class BookingException extends RuntimeException {
  private final BookingFailure failure;
  private final String reason;

  public BookingException(BookingFailure failure, String reason) {
    super(failure.name() + ":" + reason, null, false, false);
    this.failure = failure;
    this.reason = reason;
  }

  public BookingException(BookingFailure failure) {
    this(failure, failure.name());
  }

  public BookingFailure failure() {
    return failure;
  }

  public String reason() {
    return reason;
  }
}
