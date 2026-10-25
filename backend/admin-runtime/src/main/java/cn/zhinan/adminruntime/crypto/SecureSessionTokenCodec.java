package cn.zhinan.adminruntime.crypto;

import cn.zhinan.adminsecurity.port.SessionTokenCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/** Standard JDK primitives. SHA-256 is appropriate for random tokens, never for passwords. */
public final class SecureSessionTokenCodec implements SessionTokenCodec {
  private final SecureRandom random = new SecureRandom();

  @Override
  public String generate() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  @Override
  public String digest(String token) {
    Objects.requireNonNull(token);
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(token.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
