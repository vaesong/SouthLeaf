package cn.zhinan.admincredentialcrypto;

import cn.zhinan.adminsecurity.domain.CredentialDependencyException;
import cn.zhinan.adminsecurity.domain.PasswordInput;
import cn.zhinan.adminsecurity.domain.PasswordMatch;
import cn.zhinan.adminsecurity.port.PasswordHasher;
import cn.zhinan.adminsecurity.port.PasswordVerifier;
import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/** Bounded maintained-library adapter, not a custom KDF or an account-policy implementation. */
public final class Argon2PasswordService implements PasswordVerifier, PasswordHasher {
  private static final String CURRENT = "m=65536,t=3,p=1";
  private static final String PREVIOUS = "m=19456,t=2,p=1";
  private static final Pattern PHC =
      Pattern.compile(
          Pattern.quote("$argon2id$v=19$")
              + "("
              + CURRENT
              + "|"
              + PREVIOUS
              + ")"
              + Pattern.quote("$")
              + "([A-Za-z0-9+/]{22})"
              + Pattern.quote("$")
              + "([A-Za-z0-9+/]{43})");
  // Public synthetic fixture; no account uses this hash, and its result never authorizes anyone.
  private static final String DUMMY =
      "$argon2id$v=19$m=65536,t=3,p=1$MDEyMzQ1Njc4OWFiY2RlZg$UK8wQh8PyocEgY7zRrNJQtvCpiKS/EbCJs3f3ZHk+Vg";
  private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 65536, 3);

  @Override
  public String hash(char[] password) {
    requireInput(password);
    // Spring creates internal String/char[] copies. GC-managed plaintext cannot be promised erased.
    return KdfWorkBudget.run(() -> encoder.encode(new String(password)));
  }

  @Override
  public PasswordMatch verify(char[] password, String credentialHash) {
    requireInput(password);
    boolean previous = previousProfile(credentialHash);
    return KdfWorkBudget.run(
        () -> {
          boolean matches = encoder.matches(new String(password), credentialHash);
          return new PasswordMatch(matches, matches && previous);
        });
  }

  @Override
  public void consumeDummyCost(char[] password) {
    verify(password, DUMMY);
  }

  private static void requireInput(char[] password) {
    if (!PasswordInput.isBounded(password))
      throw new IllegalArgumentException("Unsupported password input");
  }

  private static boolean previousProfile(String value) {
    if (value == null || value.length() > 128) throw invalidHash();
    var parts = PHC.matcher(value);
    if (!parts.matches()) throw invalidHash();
    // Check unused base64 bits too; Spring must never see a malformed PHC/log its decoder
    // exception.
    for (int group = 2; group <= 3; group++) {
      String encoded = parts.group(group);
      byte[] decoded = Base64.getDecoder().decode(encoded);
      if (!Base64.getEncoder().withoutPadding().encodeToString(decoded).equals(encoded))
        throw invalidHash();
    }
    return PREVIOUS.equals(parts.group(1));
  }

  private static CredentialDependencyException invalidHash() {
    return new CredentialDependencyException(
        CredentialDependencyException.Reason.INVALID_STORED_HASH);
  }
}
