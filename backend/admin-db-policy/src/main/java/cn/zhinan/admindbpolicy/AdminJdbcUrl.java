package cn.zhinan.admindbpolicy;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** Shared shape only. Migration and runtime retain distinct value/TLS contracts. */
final class AdminJdbcUrl {
  private AdminJdbcUrl() {}

  static URI parse(String url) {
    // Small documented allowlist; encoded property aliases and credential-bearing options fail
    // closed.
    var allowed =
        Set.of(
            "sslmode",
            "allowpublickeyretrieval",
            "connectiontimezone",
            "forceconnectiontimezonetosession",
            "connecttimeout",
            "sockettimeout",
            "characterencoding");
    if (url == null || !url.startsWith("jdbc:mysql://"))
      throw new IllegalArgumentException("Single-host MySQL URL required");
    final URI parsed;
    try {
      parsed = URI.create(url.substring(5));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid management URL");
    }
    if (parsed.getHost() == null
        || parsed.getRawUserInfo() != null
        || parsed.getRawFragment() != null) {
      throw new IllegalArgumentException("Credential-free single-host MySQL URL required");
    }
    var seen = new java.util.HashSet<String>();
    if (parsed.getRawQuery() != null) {
      for (String property : parsed.getRawQuery().split("&", -1)) {
        String[] parts = property.split("=", 2);
        String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        if (parts.length != 2 || !allowed.contains(key) || !seen.add(key))
          throw new IllegalArgumentException("Unsupported management URL property");
      }
    }
    return parsed;
  }
}
