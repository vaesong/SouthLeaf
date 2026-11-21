package cn.zhinan.appuser;

/** A user or visit failure. The message is a stable reason, never a stored value. */
public final class AppUserException extends RuntimeException {
  public enum Code {
    UNAVAILABLE,
    SESSION_REQUIRED,
    IDENTITY_REJECTED,
    INVALID,
    NOT_FOUND,
    CONFLICT,
    AVATAR_REJECTED
  }

  private final Code code;

  private AppUserException(Code code, String reason) {
    super(reason);
    this.code = code;
  }

  public Code code() {
    return code;
  }

  public static AppUserException unavailable() {
    return new AppUserException(Code.UNAVAILABLE, "UNAVAILABLE");
  }

  public static AppUserException sessionRequired() {
    return new AppUserException(Code.SESSION_REQUIRED, "SESSION_REQUIRED");
  }

  public static AppUserException identityRejected() {
    return new AppUserException(Code.IDENTITY_REJECTED, "IDENTITY_REJECTED");
  }

  public static AppUserException invalid(String reason) {
    return new AppUserException(Code.INVALID, reason);
  }

  public static AppUserException notFound() {
    return new AppUserException(Code.NOT_FOUND, "NOT_FOUND");
  }

  public static AppUserException conflict() {
    return new AppUserException(Code.CONFLICT, "VERSION_CONFLICT");
  }

  public static AppUserException avatarRejected() {
    return new AppUserException(Code.AVATAR_REJECTED, "AVATAR_REJECTED");
  }
}
