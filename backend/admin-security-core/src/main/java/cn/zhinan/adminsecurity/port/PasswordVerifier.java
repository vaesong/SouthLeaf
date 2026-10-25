package cn.zhinan.adminsecurity.port;

import cn.zhinan.adminsecurity.domain.PasswordMatch;

/** Implementations must not mutate/retain the caller array; malformed stored hashes fail closed. */
public interface PasswordVerifier {
  PasswordMatch verify(char[] password, String credentialHash);

  /** Consume bounded current-profile cost; a dummy match must NEVER grant identity. */
  void consumeDummyCost(char[] password);
}
