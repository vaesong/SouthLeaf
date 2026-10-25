package cn.zhinan.adminsecurity.domain;

import java.util.Locale;
import java.util.Set;

/**
 * C3 creation/reset policy. This preserves input bytes for hashing; comparison is only a
 * weak-secret rejection check and never rewrites the supplied password.
 */
public final class AccountPasswordPolicy {
  public static final String REJECT_LIST_VERSION = "c3-local-common-v1";
  private static final Set<String> REJECTED =
      Set.of(
          "passwordpassword",
          "123456789012345",
          "qwertyqwerty123",
          "letmeinletmein1",
          "adminadminadmin1");

  public void requireAcceptable(char[] password) {
    if (!PasswordInput.isBounded(password)
        || Character.codePointCount(password, 0, password.length) < 15) {
      reject();
    }
    // String exists briefly because Java's Unicode case operation has no char[] API. The caller
    // owns
    // and clears its char[]; the existing Argon2 adapter has the same managed-memory limitation.
    String candidate = new String(password);
    if (REJECTED.contains(candidate.toLowerCase(Locale.ROOT))) reject();
  }

  private static void reject() {
    throw new AccountLifecycleException(AccountLifecycleException.Reason.PASSWORD_POLICY);
  }
}
