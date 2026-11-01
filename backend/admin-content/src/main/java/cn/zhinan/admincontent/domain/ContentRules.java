package cn.zhinan.admincontent.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Pure S2 limits and canonical digests (D-302 §13, D-507 §12). */
public final class ContentRules {
  /** Selected JPEG/PNG before derivatives are generated. The original is not stored. */
  public static final int MAX_UPLOAD_BYTES = 30 * 1024 * 1024;

  /** Thumbnail and preview objects, and any file read back from the private root. */
  public static final int MAX_STORED_OBJECT_BYTES = 4 * 1024 * 1024;

  public static final int MAX_SOURCE_FILE_NAME = 120;
  public static final int MAX_FOLDER_NAME = 40;
  public static final int MAX_FOLDER_DEPTH = 3;
  public static final int MAX_EDGE = 8192;
  public static final long MAX_PIXELS = 24_000_000L;
  public static final int THUMBNAIL_EDGE = 480;
  public static final int PREVIEW_EDGE = 1600;
  public static final int MAX_TITLE = 80;
  public static final int MAX_SUMMARY = 400;
  public static final int MAX_ALT = 120;
  public static final int MAX_MEDIA = 9;
  public static final int MAX_BUSINESSES = 3;
  public static final String PURPOSE = "PUBLIC_WORK_DISPLAY";
  public static final String CHANNEL = "WECHAT_MINI_PROGRAM";

  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{16,64}");
  private static final Pattern SCOPE_REF = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
  private static final Pattern BUSINESS_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
  private static final Pattern UUID_TEXT =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  private ContentRules() {}

  public static String idempotencyKey(String value) {
    if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches())
      throw new ContentException(ContentFailure.INVALID_REQUEST, "IDEMPOTENCY_KEY_INVALID");
    return value;
  }

  public static String uuid(String value) {
    if (value == null || !UUID_TEXT.matcher(value).matches())
      throw new ContentException(ContentFailure.NOT_FOUND, "ID_INVALID");
    return value;
  }

  public static String newId() {
    return UUID.randomUUID().toString();
  }

  public static boolean scopeRef(String value) {
    return value != null && SCOPE_REF.matcher(value).matches();
  }

  public static boolean businessId(String value) {
    return value != null && BUSINESS_ID.matcher(value).matches();
  }

  /** Order-independent digest of the exact media set covered by an authorization decision. */
  public static String mediaScopeDigest(Collection<String> mediaIds) {
    List<String> sorted = mediaIds.stream().sorted().toList();
    return sha256(String.join("\n", sorted).getBytes(StandardCharsets.UTF_8));
  }

  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  public static String sha256(String text) {
    return sha256(text.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Display name for admin search. Takes the last path segment, drops separators and control
   * characters, and keeps at most {@link #MAX_SOURCE_FILE_NAME} characters. Not an object key.
   * Empty results are {@code null}.
   */
  public static String sourceFileName(String submitted) {
    if (submitted == null) return null;
    int separator = Math.max(submitted.lastIndexOf('/'), submitted.lastIndexOf('\\'));
    String base = separator >= 0 ? submitted.substring(separator + 1) : submitted;
    var cleaned = new StringBuilder(base.length());
    base.codePoints()
        .filter(cp -> cp != '/' && cp != '\\' && !Character.isISOControl(cp))
        .forEach(cleaned::appendCodePoint);
    int start = 0;
    int end = cleaned.length();
    while (start < end && Character.isWhitespace(cleaned.charAt(start))) start++;
    while (end > start && Character.isWhitespace(cleaned.charAt(end - 1))) end--;
    if (start >= end) return null;
    int keep = Math.min(MAX_SOURCE_FILE_NAME, cleaned.codePointCount(start, end));
    return cleaned.substring(start, cleaned.offsetByCodePoints(start, keep));
  }

  /**
   * Bounded contains-pattern for a display-name search. Backslash, percent and underscore are
   * escaped so they match literally. Null when the query is blank or longer than a stored name.
   */
  public static String likeContains(String query) {
    if (query == null) return null;
    String trimmed = query.trim();
    if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > MAX_SOURCE_FILE_NAME)
      return null;
    return trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  /** Display name of a media folder. Slashes and control characters are rejected. */
  public static String folderName(String value) {
    if (value == null) throw folderNameInvalid();
    String trimmed = value.trim();
    int length = trimmed.codePointCount(0, trimmed.length());
    if (length < 1
        || length > MAX_FOLDER_NAME
        || trimmed.indexOf('/') >= 0
        || trimmed.indexOf('\\') >= 0
        || trimmed.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) throw folderNameInvalid();
    return trimmed;
  }

  private static ContentException folderNameInvalid() {
    return new ContentException(ContentFailure.INVALID_REQUEST, "FOLDER_NAME_INVALID");
  }
}
