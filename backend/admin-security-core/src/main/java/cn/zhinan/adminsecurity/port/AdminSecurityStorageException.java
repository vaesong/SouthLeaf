package cn.zhinan.adminsecurity.port;

/** Dependency/data failure, never an authentication success or missing-account signal. */
public final class AdminSecurityStorageException extends RuntimeException {
  public AdminSecurityStorageException(Throwable cause) {
    super("Administrator security storage unavailable", cause);
  }
}
