package cn.zhinan.admindbpolicy;

import java.util.Locale;
import java.util.regex.Pattern;

/** Pure migration target policy. No connections, credentials, or runtime/demo relaxation. */
public final class AdminMigrationPolicy {
  private AdminMigrationPolicy() {}

  public static void validateMigrationUrl(String url) {
    AdminJdbcUrl.parse(url);
    if (url == null
        || !url.startsWith("jdbc:mysql://")
        || url.contains("@")
        || url.contains("#")
        || Pattern.compile(
                "(?:[?&])(?:user|username|password|password[0-9]+)=", Pattern.CASE_INSENSITIVE)
            .matcher(url)
            .find()) {
      throw new IllegalArgumentException("Management URL must not contain credentials");
    }
    if (!Pattern.compile("(?:[?&])sslMode=VERIFY_IDENTITY(?:&|$)", Pattern.CASE_INSENSITIVE)
            .matcher(url)
            .find()
        || Pattern.compile("(?:[?&])sslMode=", Pattern.CASE_INSENSITIVE)
                .matcher(url)
                .results()
                .count()
            != 1) {
      throw new IllegalArgumentException("Management migration requires sslMode=VERIFY_IDENTITY");
    }
  }

  public static void validateMigrationTarget(
      String expected, String actual, String user, String cipher) {
    if (expected == null
        || !expected.matches("southleaf_admin(?:_[a-z0-9]+)*")
        || !expected.equals(actual)) {
      throw new IllegalStateException("Explicit isolated management schema required");
    }
    if (user == null || user.isBlank() || user.toLowerCase(Locale.ROOT).startsWith("root@")) {
      throw new IllegalStateException("Dedicated management migrator required");
    }
    if (cipher == null || cipher.isBlank())
      throw new IllegalStateException("Encrypted connection required");
  }
}
