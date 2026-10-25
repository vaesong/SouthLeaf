package cn.zhinan.uniapp.booking.config;

/** Startup refusal: the public booking entry never runs on a guessed or partial configuration. */
public final class PublicBookingStartupException extends RuntimeException {
  public enum Reason {
    CONFIGURATION,
    IDENTITY,
    TLS,
    GRANTS,
    DATABASE
  }

  private final Reason reason;

  public PublicBookingStartupException(Reason reason) {
    super("Public booking refused: " + reason.name());
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
