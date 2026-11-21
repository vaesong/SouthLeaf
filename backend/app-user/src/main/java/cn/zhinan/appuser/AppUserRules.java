package cn.zhinan.appuser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

/** Shared identity and field rules. The fingerprint matches the booking subject digest. */
public final class AppUserRules {
  public static final String PROVIDER = "WECHAT";
  public static final int NAME_LIMIT = 32;
  public static final int NOTE_LIMIT = 500;
  public static final int AVATAR_LIMIT = 512 * 1024;
  public static final int EDGE = 512;
  static final int DEDUP_SECONDS = 30;

  private static final Set<String> PAGES =
      Set.of(
          "pages/index/index",
          "pages/works/works",
          "pages/works/workdetail",
          "pages/package/package",
          "pages/package/pkgdetail",
          "pages/about/about",
          "pages/booking/booking",
          "pages/booking/requestdetail",
          "pages/mine/mine");

  private AppUserRules() {}

  public static String fingerprint(String provider, String appScope, String subject) {
    return digest(provider, appScope, subject);
  }

  public static String digest(String... parts) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String part : parts) {
        digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0x1f);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  public static String shortId(String fingerprint) {
    return fingerprint.substring(0, 6).toUpperCase();
  }

  public static String displayLabel(String displayName, String fingerprint) {
    if (displayName != null && !displayName.isBlank()) return displayName;
    return "微信用户 " + shortId(fingerprint);
  }

  /** Empty input clears the nickname. Anything else must fit in 32 Unicode scalars. */
  public static String nickname(String raw) {
    if (raw == null) throw AppUserException.invalid("DISPLAY_NAME");
    String trimmed = raw.strip();
    if (trimmed.isEmpty()) return null;
    if (trimmed.codePointCount(0, trimmed.length()) > NAME_LIMIT) {
      throw AppUserException.invalid("DISPLAY_NAME");
    }
    trimmed
        .codePoints()
        .forEach(
            point -> {
              if (Character.isISOControl(point)) throw AppUserException.invalid("DISPLAY_NAME");
            });
    return trimmed;
  }

  /** Empty input clears the note. Line breaks stay; other controls do not. */
  public static String note(String raw) {
    if (raw == null) throw AppUserException.invalid("NOTE");
    String trimmed = raw.strip();
    if (trimmed.isEmpty()) return null;
    if (trimmed.codePointCount(0, trimmed.length()) > NOTE_LIMIT) {
      throw AppUserException.invalid("NOTE");
    }
    trimmed
        .codePoints()
        .forEach(
            point -> {
              if (point != '\n' && point != '\r' && Character.isISOControl(point)) {
                throw AppUserException.invalid("NOTE");
              }
            });
    return trimmed;
  }

  public static String page(String raw) {
    if (raw == null || !PAGES.contains(raw)) throw AppUserException.invalid("PAGE");
    return raw;
  }

  public static String optionalId(String raw, String reason) {
    if (raw == null || raw.isBlank()) return null;
    String trimmed = raw.strip();
    if (trimmed.length() > 64 || trimmed.indexOf('\u0000') >= 0) {
      throw AppUserException.invalid(reason);
    }
    return trimmed;
  }

  public static String clientEventId(String raw) {
    if (raw == null
        || !raw.matches(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
      throw AppUserException.invalid("CLIENT_EVENT_ID");
    }
    return raw.toLowerCase();
  }

  public static Integer scene(Integer raw) {
    if (raw == null) return null;
    if (raw < 0 || raw > 9999) throw AppUserException.invalid("SCENE");
    return raw;
  }

  public static String searchQuery(String raw) {
    if (raw == null || raw.isBlank()) return "";
    String trimmed = raw.strip();
    if (trimmed.codePointCount(0, trimmed.length()) > NAME_LIMIT) {
      throw AppUserException.invalid("QUERY");
    }
    return trimmed;
  }

  public static Instant dedupSince(Instant now) {
    return now.minusSeconds(DEDUP_SECONDS);
  }
}
