package cn.zhinan.adminruntime;

import cn.zhinan.admincontent.adapter.cos.CosMediaObjectWriter;
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
          "SOUTHLEAF_ADMIN_CONTENT_MEDIA_STORE",
          "SOUTHLEAF_ADMIN_CONTENT_MEDIA_ROOT",
          "SOUTHLEAF_ADMIN_CONTENT_COS_SECRET_ID",
          "SOUTHLEAF_ADMIN_CONTENT_COS_SECRET_KEY",
          "SOUTHLEAF_ADMIN_CONTENT_COS_BUCKET",
          "SOUTHLEAF_ADMIN_CONTENT_COS_REGION");
  private static final Set<String> COS_KEYS =
      Set.of(
          "SOUTHLEAF_ADMIN_CONTENT_COS_SECRET_ID",
          "SOUTHLEAF_ADMIN_CONTENT_COS_SECRET_KEY",
          "SOUTHLEAF_ADMIN_CONTENT_COS_BUCKET",
          "SOUTHLEAF_ADMIN_CONTENT_COS_REGION");
  static final AdminContentProperties DISABLED =
      new AdminContentProperties(
          false, null, null, null, null, null, null, null, null, null, null, null, null, null,
          null);

  private final boolean enabled;
  private final ContentMode mode;
  private final String businessUser, businessPassword, businessExpected;
  private final String publicationUser, publicationPassword, publicationExpected;
  private final String publicSchema;
  private final String mediaStore;
  private final Path mediaRoot;
  private final String cosSecretId;
  private final String cosSecretKey;
  private final String cosBucket;
  private final String cosRegion;

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
      String mediaStore,
      Path mediaRoot,
      String cosSecretId,
      String cosSecretKey,
      String cosBucket,
      String cosRegion) {
    this.enabled = enabled;
    this.mode = mode;
    this.businessUser = businessUser;
    this.businessPassword = businessPassword;
    this.businessExpected = businessExpected;
    this.publicationUser = publicationUser;
    this.publicationPassword = publicationPassword;
    this.publicationExpected = publicationExpected;
    this.publicSchema = publicSchema;
    this.mediaStore = mediaStore;
    this.mediaRoot = mediaRoot;
    this.cosSecretId = cosSecretId;
    this.cosSecretKey = cosSecretKey;
    this.cosBucket = cosBucket;
    this.cosRegion = cosRegion;
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
      if (key.equals("SOUTHLEAF_ADMIN_CONTENT_MEDIA_ROOT") || COS_KEYS.contains(key)) continue;
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
        || (mode == ContentMode.PRODUCTION && !publicSchema.equals("southleaf_public"))
        || (mode == ContentMode.DEMO && publicSchema.equals("southleaf_public"))) refuse();
    String store = env.get("SOUTHLEAF_ADMIN_CONTENT_MEDIA_STORE");
    if (!"file".equals(store) && !"cos".equals(store)) refuse();
    boolean cosConfigured =
        COS_KEYS.stream().anyMatch(key -> env.get(key) != null && !env.get(key).isBlank());
    String rootValue = env.get("SOUTHLEAF_ADMIN_CONTENT_MEDIA_ROOT");
    boolean rootConfigured = rootValue != null && !rootValue.isBlank();
    Path root = null;
    String secretId = null;
    String secretKey = null;
    String bucket = null;
    String region = null;
    if ("file".equals(store)) {
      if (cosConfigured || !rootConfigured) refuse();
      try {
        root = Path.of(rootValue);
        if (!root.isAbsolute() || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) refuse();
      } catch (RuntimeException invalid) {
        if (invalid instanceof AdminStartupException startup) throw invalid;
        refuse();
      }
    } else {
      if (rootConfigured) refuse();
      for (String key : COS_KEYS) {
        String value = env.get(key);
        if (value == null || value.isBlank() || value.length() > 256) refuse();
      }
      secretId = env.get("SOUTHLEAF_ADMIN_CONTENT_COS_SECRET_ID");
      secretKey = env.get("SOUTHLEAF_ADMIN_CONTENT_COS_SECRET_KEY");
      bucket = env.get("SOUTHLEAF_ADMIN_CONTENT_COS_BUCKET");
      region = env.get("SOUTHLEAF_ADMIN_CONTENT_COS_REGION");
      try {
        CosMediaObjectWriter.requireLocation(secretId, secretKey, bucket, region);
      } catch (RuntimeException invalid) {
        refuse();
      }
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
        store,
        root,
        secretId,
        secretKey,
        bucket,
        region);
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

  String mediaStore() {
    return mediaStore;
  }

  Path mediaRoot() {
    return mediaRoot;
  }

  String cosSecretId() {
    return cosSecretId;
  }

  String cosSecretKey() {
    return cosSecretKey;
  }

  String cosBucket() {
    return cosBucket;
  }

  String cosRegion() {
    return cosRegion;
  }

  @Override
  public String toString() {
    return "AdminContentProperties[REDACTED]";
  }
}
