package cn.zhinan.adminruntime;

import cn.zhinan.admincredentialcrypto.Argon2PasswordService;
import java.util.Arrays;

/** Test-only hash fixture: caller captures stdout privately and never records it in logs/files. */
public final class AdminCredentialFixtureHash {
  private AdminCredentialFixtureHash() {}

  public static void main(String[] args) {
    if (args.length != 0) throw new IllegalArgumentException("No command-line password accepted");
    String value = System.getenv("SOUTHLEAF_TEST_LOGIN_PASSWORD");
    if (value == null || value.isBlank())
      throw new IllegalStateException("Synthetic test password required");
    char[] password = value.toCharArray();
    try {
      System.out.print(new Argon2PasswordService().hash(password));
    } finally {
      Arrays.fill(password, '\0');
    }
  }
}
