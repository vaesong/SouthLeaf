package cn.zhinan.uniapp.user;

/** Startup refusal for the mini-program user entry. */
public final class PublicUserStartupException extends RuntimeException {
  public enum Reason {
    CONFIGURATION,
    IDENTITY,
    TLS,
    GRANTS,
    DATABASE
  }

  public PublicUserStartupException(Reason reason) {
    super("Public user entry refused: " + reason.name());
  }
}
