package cn.zhinan.appuser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stateless mini-program session. Version {@code u1} is distinct from a booking token, so one
 * cannot be presented as the other even when the secrets differ.
 */
public final class AppUserTokens {
  static final String VERSION = "u1";
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final byte[] secret;
  private final Duration lifetime;

  public AppUserTokens(String secret, Duration lifetime) {
    if (secret == null || secret.length() < 32 || secret.length() > 256) {
      throw new IllegalArgumentException("Session secret must be at least 32 characters");
    }
    if (lifetime == null
        || lifetime.compareTo(Duration.ofMinutes(5)) < 0
        || lifetime.compareTo(Duration.ofHours(12)) > 0) {
      throw new IllegalArgumentException("Session lifetime out of range");
    }
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    this.lifetime = lifetime;
  }

  public Issued issue(String userId, Instant now) {
    if (userId == null || !userId.matches("[A-Za-z0-9-]{8,64}")) {
      throw new IllegalArgumentException("User reference invalid");
    }
    Instant expiresAt = now.plus(lifetime);
    String payload =
        VERSION + "|" + userId + "|" + now.getEpochSecond() + "|" + expiresAt.getEpochSecond();
    String encoded = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    return new Issued(encoded + "." + ENCODER.encodeToString(sign(encoded)), expiresAt);
  }

  public String userId(String token, Instant now) {
    if (token == null) throw AppUserException.sessionRequired();
    String trimmed = token.strip();
    int separator = trimmed.lastIndexOf('.');
    if (separator <= 0 || separator == trimmed.length() - 1)
      throw AppUserException.sessionRequired();
    String encoded = trimmed.substring(0, separator);
    byte[] signature;
    try {
      signature = DECODER.decode(trimmed.substring(separator + 1));
    } catch (IllegalArgumentException malformed) {
      throw AppUserException.sessionRequired();
    }
    if (!MessageDigest.isEqual(sign(encoded), signature)) throw AppUserException.sessionRequired();
    String payload;
    try {
      payload = new String(DECODER.decode(encoded), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException malformed) {
      throw AppUserException.sessionRequired();
    }
    String[] parts = payload.split("\\|", -1);
    if (parts.length != 4 || !VERSION.equals(parts[0])) throw AppUserException.sessionRequired();
    long issuedAt;
    long expiresAt;
    try {
      issuedAt = Long.parseLong(parts[2]);
      expiresAt = Long.parseLong(parts[3]);
    } catch (NumberFormatException malformed) {
      throw AppUserException.sessionRequired();
    }
    if (!parts[1].matches("[A-Za-z0-9-]{8,64}")) throw AppUserException.sessionRequired();
    long nowSeconds = now.getEpochSecond();
    if (expiresAt <= nowSeconds || issuedAt > nowSeconds + 300)
      throw AppUserException.sessionRequired();
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
}
