package cn.zhinan.adminsecurity.domain;

/** Intentionally does not reveal account existence, expiry, disablement or revocation. */
public final class AdminAuthenticationException extends RuntimeException {
  public AdminAuthenticationException() {
    super("Administrator authentication required");
  }
}
