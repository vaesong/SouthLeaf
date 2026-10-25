package cn.zhinan.adminsecurity;

import cn.zhinan.adminsecurity.port.SessionTokenCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic TEST ONLY fixture; not a production source of session secrets. */
final class TestSessionTokenCodec implements SessionTokenCodec {
  private static final AtomicLong SEQUENCE = new AtomicLong();

  @Override
  public String generate() {
    return String.format(java.util.Locale.ROOT, "%043d", SEQUENCE.incrementAndGet());
  }

  @Override
  public String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }
}
