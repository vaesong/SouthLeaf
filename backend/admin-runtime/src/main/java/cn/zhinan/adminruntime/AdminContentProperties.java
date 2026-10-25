package cn.zhinan.adminruntime;

import cn.zhinan.admincontent.domain.ContentMode;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Optional S2-B content capability. Disabled unless explicitly enabled; a partial configuration is
 * refused rather than ignored. Not a record so credentials never reach a generated toString.
 */
final class AdminContentProperties {
  static final Set<String> KEYS =
      Set.of(
          "SOUTHLEAF_ADMIN_CONTENT_MODE",
          "SOUTHLEAF_ADMIN_CONTENT_BUSINESS_DB_USERNAME",
          "SOUTHLEAF_ADMIN_CONTENT_BUSINESS_DB_PASSWORD",
          "SOUTHLEAF_ADMIN_CONTENT_BUSINESS_DB_EXPECTED_USER",
          "SOUTHLEAF_ADMIN_CONTENT_PUBLICATION_DB_USERNAME",
          "SOUTHLEAF_ADMIN_CONTENT_PUBLICATION_DB_PASSWORD",
          "SOUTHLEAF_ADMIN_CONTENT_PUBLICATION_DB_EXPECTED_USER",
          "SOUTHLEAF_ADMIN_CONTENT_PUBLIC_SCHEMA",
          "SOUTHLEAF_ADMIN_CONTENT_MEDIA_ROOT");
  static final AdminContentProperties DISABLED =
      new AdminContentProperties(false, null, null, null, null, null, null, null, null, null);

  private final boolean enabled;
  private final ContentMode mode;
  private final String businessUser, businessPassword, businessExpected;
  private final String publicationUser, publicationPassword, publicationExpected;
  private final String publicSchema;
  private final Path mediaRoot;

  private AdminContentProperties(
      boolean enabled,
      ContentMode mode,
      String businessUser,
      String businessPassword,
      String businessExpected,
      String publicationUser,
      String publicationPassword,
      String publicationExpected,
      String publicSchema,
      Path mediaRoot) {
    this.enabled = enabled;
    this.mode = mode;
    this.businessUser = businessUser;
    this.businessPassword = businessPassword;
    this.businessExpected = businessExpected;
    this.publicationUser = publicationUser;
    this.publicationPassword = publicationPassword;
    this.publicationExpected = publicationExpected;
    this.publicSchema = publicSchema;
    this.mediaRoot = mediaRoot;
  }

  static AdminContentProperties load(
      Map<String, String> env,
      String adminMode,
      String adminSchema,
      String runtimeUser,
      boolean webEnabled) {
    String switchValue = env.get("SOUTHLEAF_ADMIN_CONTENT_MODE");
    boolean anyContentKey = KEYS.stream().anyMatch(env::containsKey);
    if (switchValue == null) {
      if (anyContentKey) refuse();
      return DISABLED;
    }
    if (!"enabled".equals(switchValue) || !webEnabled) refuse();
    for (String key : KEYS) {
      String value = env.get(key);
      if (value == null || value.isBlank() || value.length() > 4096) refuse();
    }
    final ContentMode mode;
    if ("demo".equals(adminMode)) mode = ContentMode.DEMO;
    else if ("production".equals(adminMode)) mode = ContentMode.PRODUCTION;
    else {
      refuse();
      return DISABLED;
    }
    String business = env.get("SOUTHLEAF_ADMIN_CONTENT_BUSINESS_DB_USERNAME");
    String businessExpected = env.get("SOUTHLEAF_ADMIN_CONTENT_BUSINESS_DB_EXPECTED_USER");
    String publication = env.get("SOUTHLEAF_ADMIN_CONTENT_PUBLICATION_DB_USERNAME");
    String publicationExpected = env.get("SOUTHLEAF_ADMIN_CONTENT_PUBLICATION_DB_EXPECTED_USER");
    String publicSchema = env.get("SOUTHLEAF_ADMIN_CONTENT_PUBLIC_SCHEMA");
    if (!user(business, businessExpected)
        || !user(publication, publicationExpected)
        || business.equalsIgnoreCase(publication)
        || business.equalsIgnoreCase(runtimeUser)
        || publication.equalsIgnoreCase(runtimeUser)
        || !publicSchema.matches("[a-z][a-z0-9_]{0,63}")
        || publicSchema.equals(adminSchema)
        || (mode == ContentMode.PRODUCTION && !publicSchema.equals("southleaf_public"))) refuse();
    final Path root;
    try {
      root = Path.of(env.get("SOUTHLEAF_ADMIN_CONTENT_MEDIA_ROOT"));
      if (!root.isAbsolute() || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) refuse();
    } catch (RuntimeException invalid) {
      if (invalid instanceof AdminStartupException startup) throw startup;
      refuse();
      return DISABLED;
    }
    return new AdminContentProperties(
        true,
        mode,
        business,
        env.get("SOUTHLEAF_ADMIN_CONTENT_BUSINESS_DB_PASSWORD"),
        businessExpected,
        publication,
        env.get("SOUTHLEAF_ADMIN_CONTENT_PUBLICATION_DB_PASSWORD"),
        publicationExpected,
        publicSchema,
        root);
  }

  private static boolean user(String username, String expected) {
    return username.matches("[A-Za-z][A-Za-z0-9_]{0,31}")
        && !username.equalsIgnoreCase("root")
        && expected.matches("[A-Za-z][A-Za-z0-9_]{0,31}@[A-Za-z0-9._%:/-]{1,255}")
        && expected.startsWith(username + "@");
  }

  private static void refuse() {
    throw new AdminStartupException(AdminStartupException.Reason.CONFIGURATION);
  }

  boolean enabled() {
    return enabled;
  }

  ContentMode mode() {
    return mode;
  }

  String businessUser() {
    return businessUser;
  }

  String businessPassword() {
    return businessPassword;
  }

  String businessExpected() {
    return businessExpected;
  }

  String publicationUser() {
    return publicationUser;
  }

  String publicationPassword() {
    return publicationPassword;
  }

  String publicationExpected() {
    return publicationExpected;
  }

  String publicSchema() {
    return publicSchema;
  }

  Path mediaRoot() {
    return mediaRoot;
  }

  @Override
  public String toString() {
    return "AdminContentProperties[REDACTED]";
  }
}
