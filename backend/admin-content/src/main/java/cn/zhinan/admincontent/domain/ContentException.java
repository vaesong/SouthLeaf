package cn.zhinan.admincontent.domain;

/** Carries only a category and a fixed reason code; never SQL, paths, keys or user content. */
public final class ContentException extends RuntimeException {
  private final ContentFailure failure;
  private final String reason;

  public ContentException(ContentFailure failure, String reason) {
    super(failure.name() + ":" + reason, null, false, false);
    this.failure = failure;
    this.reason = reason;
  }

  public ContentException(ContentFailure failure) {
    this(failure, failure.name());
  }

  public ContentFailure failure() {
    return failure;
  }

  public String reason() {
    return reason;
  }
}
