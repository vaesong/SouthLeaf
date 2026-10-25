package cn.zhinan.adminruntime;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Which browser origins may perform administrative writes.
 *
 * <p>The rule is exact membership: a header is canonicalised (lower-case host, explicit port, 443
 * when omitted) and compared against an allow-list. There is deliberately no suffix or wildcard
 * matching, so a deployment can admit a second name without widening what an attacker may otherwise
 * claim, and the request pipeline never learns how the names were spelled.
 */
final class AdminOriginPolicy {
  private final List<String> accepted;

  private AdminOriginPolicy(List<String> accepted) {
    this.accepted = List.copyOf(accepted);
  }

  static AdminOriginPolicy allowing(URI... origins) {
    var canonical = new LinkedHashSet<String>();
    for (URI origin : origins) {
      String value = canonicalize(origin == null ? null : origin.toString());
      if (value == null) throw new IllegalArgumentException("Non-canonical origin");
      canonical.add(value);
    }
    if (canonical.isEmpty()) throw new IllegalArgumentException("Empty origin policy");
    return new AdminOriginPolicy(new ArrayList<>(canonical));
  }

  /** A policy that matches nothing, used when the administrative listener is switched off. */
  static AdminOriginPolicy denyAll() {
    return new AdminOriginPolicy(List.of());
  }

  boolean accepts(String originHeader) {
    String candidate = canonicalize(originHeader);
    return candidate != null && accepted.contains(candidate);
  }

  /**
   * Reduces an origin to the one spelling that is compared, or null when the value cannot be an
   * administrative origin at all (another scheme, credentials, a path, the literal {@code null} a
   * sandboxed document sends).
   */
  static String canonicalize(String raw) {
    if (raw == null) return null;
    String value = raw.trim();
    if (value.isEmpty() || value.length() > 512) return null;
    final URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException malformed) {
      return null;
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null) return null;
    String path = uri.getRawPath();
    if (path != null && !path.isEmpty() && !"/".equals(path)) return null;
    String host = uri.getHost();
    if (host == null || host.isEmpty()) return null;
    if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
    host = host.toLowerCase(Locale.ROOT);
    if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
    if (host.isEmpty() || host.length() > 253) return null;
    int port = uri.getPort() == -1 ? 443 : uri.getPort();
    if (port < 1 || port > 65535) return null;
    return "https://" + host + ":" + port;
  }

  @Override
  public String toString() {
    return "AdminOriginPolicy" + accepted;
  }
}
