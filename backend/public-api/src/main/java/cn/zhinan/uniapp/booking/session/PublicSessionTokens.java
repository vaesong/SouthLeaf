package cn.zhinan.uniapp.booking.session;

import cn.zhinan.uniapp.booking.PublicBookingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stateless signed customer session. The token carries only the internal customer id and a validity
 * window; there is no server session store, no cookie and no raw platform identifier.
 */
public final class PublicSessionTokens {
  private static final String VERSION = "v1";
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final byte[] secret;
  private final Duration lifetime;

  public PublicSessionTokens(String secret, Duration lifetime) {
    if (secret == null || secret.length() < 32)
      throw new IllegalArgumentException("Session secret must be at least 32 characters");
    if (lifetime == null
        || lifetime.isZero()
        || lifetime.isNegative()
        || lifetime.compareTo(Duration.ofHours(12)) > 0)
      throw new IllegalArgumentException("Session lifetime out of range");
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    this.lifetime = lifetime;
  }

  public Issued issue(String customerId, Instant now) {
    if (customerId == null || !customerId.matches("[A-Za-z0-9-]{8,64}"))
      throw new IllegalArgumentException("Customer reference invalid");
    Instant expiresAt = now.plus(lifetime);
    String payload =
        VERSION + "|" + customerId + "|" + now.getEpochSecond() + "|" + expiresAt.getEpochSecond();
    String encoded = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    return new Issued(encoded + "." + ENCODER.encodeToString(sign(encoded)), expiresAt);
  }

  /** Returns the customer id, or fails with {@code SESSION_REQUIRED} for any unusable token. */
  public String customerId(String token, Instant now) {
    if (token == null) throw required();
    String trimmed = token.strip();
    int separator = trimmed.lastIndexOf('.');
    if (separator <= 0 || separator == trimmed.length() - 1) throw required();
    String encoded = trimmed.substring(0, separator);
    byte[] signature;
    try {
      signature = DECODER.decode(trimmed.substring(separator + 1));
    } catch (IllegalArgumentException malformed) {
      throw required();
    }
    if (!MessageDigest.isEqual(sign(encoded), signature)) throw required();
    String payload;
    try {
      payload = new String(DECODER.decode(encoded), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException malformed) {
      throw required();
    }
    String[] parts = payload.split("\\|", -1);
    if (parts.length != 4 || !VERSION.equals(parts[0])) throw required();
    long issuedAt;
    long expiresAt;
    try {
      issuedAt = Long.parseLong(parts[2]);
      expiresAt = Long.parseLong(parts[3]);
    } catch (NumberFormatException malformed) {
      throw required();
    }
    if (!parts[1].matches("[A-Za-z0-9-]{8,64}")) throw required();
    long nowSeconds = now.getEpochSecond();
    if (expiresAt <= nowSeconds || issuedAt > nowSeconds + 300) throw required();
    return parts[1];
  }

  public record Issued(String token, Instant expiresAt) {}

  private byte[] sign(String encoded) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return mac.doFinal(encoded.getBytes(StandardCharsets.UTF_8));
    } catch (Exception impossible) {
      throw new IllegalStateException("HmacSHA256 unavailable", impossible);
    }
  }

  private static PublicBookingException required() {
    return new PublicBookingException(
        PublicBookingException.ErrorCode.SESSION_REQUIRED, "Session token rejected");
  }
}
