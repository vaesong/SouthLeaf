package cn.zhinan.uniapp.config;

import cn.zhinan.objectstore.CosSigner;
import cn.zhinan.objectstore.ObjectStoreException;
import cn.zhinan.uniapp.media.adapter.cdn.SignedCdnMediaDelivery;
import cn.zhinan.uniapp.media.port.MediaDelivery;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * Explicit file or CDN placement. A missing mode, a partial CDN configuration, or credentials from
 * the other mode refuse startup. There is no implicit disk placement.
 */
public final class MediaPlacement {
  public static final String DELIVERY = "SOUTHLEAF_MEDIA_DELIVERY";
  public static final String ROOT = "SOUTHLEAF_MEDIA_ROOT";
  public static final String ORIGIN = "SOUTHLEAF_MEDIA_CDN_ORIGIN";
  public static final String SECRET_ID = "SOUTHLEAF_MEDIA_COS_SECRET_ID";
  public static final String SECRET_KEY = "SOUTHLEAF_MEDIA_COS_SECRET_KEY";
  public static final String TTL = "SOUTHLEAF_MEDIA_URL_TTL_SECONDS";
  private static final Set<String> CDN_KEYS = Set.of(ORIGIN, SECRET_ID, SECRET_KEY, TTL);

  private final String mode;
  private final Path root;
  private final String origin;
  private final String secretId;
  private final String secretKey;
  private final int ttlSeconds;

  private MediaPlacement(
      String mode, Path root, String origin, String secretId, String secretKey, int ttlSeconds) {
    this.mode = mode;
    this.root = root;
    this.origin = origin;
    this.secretId = secretId;
    this.secretKey = secretKey;
    this.ttlSeconds = ttlSeconds;
  }

  public static MediaPlacement open(Environment environment) {
    return open(
        Map.of(
            DELIVERY, text(environment, DELIVERY),
            ROOT, text(environment, ROOT),
            ORIGIN, text(environment, ORIGIN),
            SECRET_ID, text(environment, SECRET_ID),
            SECRET_KEY, text(environment, SECRET_KEY),
            TTL, text(environment, TTL)));
  }

  public static MediaPlacement open(Map<String, String> env) {
    String mode = value(env, DELIVERY);
    if (!"file".equals(mode) && !"cdn".equals(mode)) refuse("MEDIA_DELIVERY_REQUIRED");
    boolean cdnConfigured = CDN_KEYS.stream().anyMatch(key -> !value(env, key).isBlank());
    String rootValue = value(env, ROOT);
    if ("file".equals(mode)) {
      if (cdnConfigured || rootValue.isBlank()) refuse("MEDIA_FILE_CONFIGURATION");
      Path root = Path.of(rootValue);
      if (!root.isAbsolute() || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
        refuse("MEDIA_FILE_CONFIGURATION");
      return new MediaPlacement("file", root, "", "", "", 0);
    }
    if (!rootValue.isBlank() || CDN_KEYS.stream().anyMatch(key -> value(env, key).isBlank()))
      refuse("MEDIA_CDN_CONFIGURATION");
    String origin = origin(value(env, ORIGIN));
    int ttl;
    try {
      ttl = Integer.parseInt(value(env, TTL));
    } catch (NumberFormatException malformed) {
      throw refused("MEDIA_CDN_CONFIGURATION");
    }
    if (ttl < 60 || ttl > 900) refuse("MEDIA_CDN_CONFIGURATION");
    try {
      new CosSigner(value(env, SECRET_ID), value(env, SECRET_KEY));
    } catch (ObjectStoreException invalid) {
      refuse("MEDIA_CDN_CONFIGURATION");
    }
    return new MediaPlacement(
        "cdn", null, origin, value(env, SECRET_ID), value(env, SECRET_KEY), ttl);
  }

  public boolean servesBytes() {
    return "file".equals(mode);
  }

  public Path root() {
    return root;
  }

  public MediaDelivery delivery(Clock clock) {
    if (servesBytes()) return MediaDelivery.apiPaths();
    return new SignedCdnMediaDelivery(
        origin, new CosSigner(secretId, secretKey), Duration.ofSeconds(ttlSeconds), clock);
  }

  private static String origin(String value) {
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException invalid) {
      throw refused("MEDIA_CDN_CONFIGURATION");
    }
    if (!"https".equals(uri.getScheme())
        || uri.getUserInfo() != null
        || uri.getHost() == null
        || !uri.getHost().contains(".")
        || uri.getPort() != -1
        || (uri.getRawPath() != null
            && !uri.getRawPath().isEmpty()
            && !"/".equals(uri.getRawPath()))
        || uri.getRawQuery() != null
        || !value.equals("https://" + uri.getHost())) refuse("MEDIA_CDN_CONFIGURATION");
    return "https://" + uri.getHost();
  }

  private static String text(Environment environment, String key) {
    String value = environment.getProperty(key);
    return value == null ? "" : value;
  }

  private static String value(Map<String, String> env, String key) {
    String value = env.get(key);
    return value == null ? "" : value;
  }

  private static void refuse(String code) {
    throw refused(code);
  }

  private static IllegalStateException refused(String code) {
    return new IllegalStateException(code);
  }

  @Override
  public String toString() {
    return "MediaPlacement[REDACTED]";
  }
}
