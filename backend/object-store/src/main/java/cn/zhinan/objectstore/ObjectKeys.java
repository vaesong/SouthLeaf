package cn.zhinan.objectstore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Content-addressed derivative names. Originals are never named here. */
public final class ObjectKeys {
  public static final int MAX_BYTES = 4 * 1024 * 1024;
  private static final Pattern KEY = Pattern.compile("[0-9a-f]{64}\\.(jpg|png)");

  private ObjectKeys() {}

  public static String require(String key) {
    if (key == null || !KEY.matcher(key).matches())
      throw new ObjectStoreException("OBJECT_KEY_INVALID");
    return key;
  }

  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  public static boolean matchesContent(String key, byte[] bytes) {
    return require(key).startsWith(sha256(bytes) + ".");
  }

  public static String contentType(String key) {
    String required = require(key);
    return required.endsWith(".png") ? "image/png" : "image/jpeg";
  }

  static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
