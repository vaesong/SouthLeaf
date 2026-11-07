package cn.zhinan.objectstore;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Tencent COS V5 request signature (HMAC-SHA1). Query signatures are for short-lived CDN reads.
 * Header signatures are for create-only writes. The secret is never part of {@link #toString()}.
 */
public final class CosSigner {
  private final String secretId;
  private final String secretKey;

  public CosSigner(String secretId, String secretKey) {
    if (!credential(secretId) || !credential(secretKey))
      throw new ObjectStoreException("COS_CREDENTIAL_INVALID");
    this.secretId = secretId;
    this.secretKey = secretKey;
  }

  /**
   * Authorization header value. {@code headers} are the headers COS must check, including host. The
   * caller sends exactly those headers.
   */
  public String authorization(
      String method, String path, Map<String, String> headers, long startEpoch, long endEpoch) {
    String keyTime = keyTime(startEpoch, endEpoch);
    String httpHeaders = canonical(headers);
    String headerList = names(headers);
    String signature = signature(method, path, "", httpHeaders, keyTime);
    return "q-sign-algorithm=sha1&q-ak="
        + secretId
        + "&q-sign-time="
        + keyTime
        + "&q-key-time="
        + keyTime
        + "&q-header-list="
        + headerList
        + "&q-url-param-list=&q-signature="
        + signature;
  }

  /**
   * Query string, without a leading {@code ?}, for a GET whose host header is {@code host}. The
   * signature covers every {@code q-*} parameter except {@code q-signature}.
   */
  public String signedQuery(
      String method, String path, String host, long startEpoch, long endEpoch) {
    if (host == null || host.isBlank()) throw new ObjectStoreException("COS_HOST_INVALID");
    String keyTime = keyTime(startEpoch, endEpoch);
    Map<String, String> params = new TreeMap<>();
    params.put("q-ak", secretId);
    params.put("q-header-list", "host");
    params.put("q-key-time", keyTime);
    params.put("q-sign-algorithm", "sha1");
    params.put("q-sign-time", keyTime);
    params.put("q-url-param-list", String.join(";", namesOf(params, "q-url-param-list")));
    String httpParameters = canonical(params);
    String signature =
        signature(method, path, httpParameters, canonical(Map.of("host", host)), keyTime);
    return httpParameters + "&q-signature=" + signature;
  }

  private String signature(
      String method, String path, String httpParameters, String httpHeaders, String keyTime) {
    String verb = method == null ? "" : method.toLowerCase(Locale.ROOT);
    if (!verb.equals("get") && !verb.equals("put"))
      throw new ObjectStoreException("COS_METHOD_INVALID");
    if (path == null || !path.startsWith("/")) throw new ObjectStoreException("COS_PATH_INVALID");
    String httpString = verb + "\n" + path + "\n" + httpParameters + "\n" + httpHeaders + "\n";
    String stringToSign = "sha1\n" + keyTime + "\n" + sha1(httpString) + "\n";
    String signKey = hmac(secretKey, keyTime);
    return hmac(signKey, stringToSign);
  }

  private static String keyTime(long startEpoch, long endEpoch) {
    if (startEpoch < 0 || endEpoch <= startEpoch)
      throw new ObjectStoreException("COS_TIME_INVALID");
    return startEpoch + ";" + endEpoch;
  }

  private static boolean credential(String value) {
    return value != null
        && value.length() >= 8
        && value.length() <= 128
        && value.chars().allMatch(ch -> ch > 0x20 && ch < 0x7f);
  }

  private static String canonical(Map<String, String> fields) {
    StringBuilder out = new StringBuilder();
    for (var entry : new TreeMap<>(fields).entrySet()) {
      if (out.length() > 0) out.append('&');
      out.append(encode(entry.getKey().toLowerCase(Locale.ROOT)))
          .append('=')
          .append(encode(entry.getValue() == null ? "" : entry.getValue()));
    }
    return out.toString();
  }

  private static String names(Map<String, String> fields) {
    return String.join(
        ";", new TreeMap<>(fields).keySet().stream().map(k -> k.toLowerCase(Locale.ROOT)).toList());
  }

  private static java.util.List<String> namesOf(Map<String, String> fields, String extra) {
    var names = new java.util.ArrayList<String>();
    names.addAll(fields.keySet());
    names.add(extra);
    names.sort(String::compareTo);
    return names;
  }

  static String encode(String value) {
    StringBuilder out = new StringBuilder(value.length());
    for (byte unit : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
      int code = unit & 0xff;
      if ((code >= 'A' && code <= 'Z')
          || (code >= 'a' && code <= 'z')
          || (code >= '0' && code <= '9')
          || code == '-'
          || code == '_'
          || code == '.'
          || code == '~') out.append((char) code);
      else out.append('%').append(HexFormat.of().withUpperCase().toHexDigits((byte) code));
    }
    return out.toString();
  }

  private static String sha1(String value) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-1").digest(ObjectKeys.utf8(value)));
    } catch (java.security.NoSuchAlgorithmException missing) {
      throw new IllegalStateException("SHA-1 unavailable");
    }
  }

  private static String hmac(String key, String message) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(ObjectKeys.utf8(key), "HmacSHA1"));
      return HexFormat.of().formatHex(mac.doFinal(ObjectKeys.utf8(message)));
    } catch (java.security.GeneralSecurityException failure) {
      throw new IllegalStateException("HMAC-SHA1 unavailable");
    }
  }

  @Override
  public String toString() {
    return "CosSigner[REDACTED]";
  }
}
