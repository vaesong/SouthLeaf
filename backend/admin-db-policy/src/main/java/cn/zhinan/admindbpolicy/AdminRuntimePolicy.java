package cn.zhinan.admindbpolicy;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Explicit runtime target, independent of migration's always-VERIFY_IDENTITY policy. */
public final class AdminRuntimePolicy {
  private AdminRuntimePolicy() {}

  public static String canonicalUrl(String mode, String schema, String url) {
    boolean production = "production".equals(mode);
    if ((!production && !"demo".equals(mode))
        || schema == null
        || schema.length() > 64
        || !(production
            ? schema.equals("southleaf_admin")
            : schema.matches("southleaf_admin_[a-z0-9]+(?:_[a-z0-9]+)*"))) fail();
    var uri = AdminJdbcUrl.parse(url);
    if (url.length() > 4096
        || url.contains("%")
        || url.contains("@")
        || uri.getPort() == 0
        || uri.getPort() > 65535
        || uri.getPort() < -1
        || !('/' + schema).equals(uri.getRawPath())) fail();
    Map<String, String> options = new LinkedHashMap<>();
    if (uri.getRawQuery() != null) {
      for (String item : uri.getRawQuery().split("&", -1)) {
        var pair = item.split("=", 2);
        options.put(pair[0].toLowerCase(Locale.ROOT), pair[1]);
      }
    }
    String tls = options.get("sslmode");
    if (tls == null
        || !(production
            ? "VERIFY_IDENTITY".equals(tls)
            : Set.of("REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY").contains(tls))) fail();
    requireValue(options, "allowpublickeyretrieval", "false");
    requireValue(options, "connectiontimezone", "UTC");
    requireValue(options, "forceconnectiontimezonetosession", "true");
    requireValue(options, "characterencoding", "UTF-8");
    int connect = timeout(options.getOrDefault("connecttimeout", "5000"));
    int socket = timeout(options.getOrDefault("sockettimeout", "5000"));
    return "jdbc:mysql://"
        + uri.getRawAuthority()
        + "/"
        + schema
        + "?sslMode="
        + tls
        + "&allowPublicKeyRetrieval=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
        + "&characterEncoding=UTF-8&connectTimeout="
        + connect
        + "&socketTimeout="
        + socket;
  }

  private static void requireValue(Map<String, String> options, String key, String value) {
    if (!value.equals(options.getOrDefault(key, value))) fail();
  }

  private static int timeout(String value) {
    if (!value.matches("[1-9][0-9]{3,4}")) fail();
    int n = Integer.parseInt(value);
    if (n < 1000 || n > 15000) fail();
    return n;
  }

  private static void fail() {
    throw new IllegalArgumentException("Invalid management runtime target");
  }
}
