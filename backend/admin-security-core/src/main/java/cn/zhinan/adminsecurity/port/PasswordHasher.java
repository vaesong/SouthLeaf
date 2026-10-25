package cn.zhinan.adminsecurity.port;

/** Low-level hashing only. Caller must apply the separate creation/reset policy before use. */
public interface PasswordHasher {
  String hash(char[] password);
}
