package cn.zhinan.adminruntime;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Explicit web listener inputs.
 *
 * <p>Everything the listener needs to know about its deployment — which interface to bind, which
 * port, which public origin a browser may use, and which proxy is allowed to speak for it — is an
 * input here. Nothing about the deployment is compiled in, no value is read through Spring's
 * property binding, and a half-supplied section is a refused start rather than a silently ignored
 * typo.
 */
final class AdminWebProperties {
  static final String PREFIX = "SOUTHLEAF_ADMIN_WEB_";
  static final String PROFILE_KEY = PREFIX + "MODE";
  static final String ORIGIN_KEY = PREFIX + "ORIGIN";
  static final String PORT_KEY = PREFIX + "PORT";
  static final String BIND_KEY = PREFIX + "BIND_ADDRESS";
  static final String PROXIES_KEY = PREFIX + "TRUSTED_PROXIES";
  static final String KEYSTORE_PATH_KEY = PREFIX + "KEYSTORE_PATH";
  static final String KEYSTORE_PASSWORD_KEY = PREFIX + "KEYSTORE_PASSWORD";
  static final String KEYSTORE_TYPE_KEY = PREFIX + "KEYSTORE_TYPE";
  static final String KEYSTORE_ALIAS_KEY = PREFIX + "KEYSTORE_ALIAS";

  /** The whole vocabulary, so the runtime's unknown-key guard can be derived instead of copied. */
  static final Set<String> KEYS =
      Set.of(
          PROFILE_KEY,
          ORIGIN_KEY,
          PORT_KEY,
          BIND_KEY,
          PROXIES_KEY,
          KEYSTORE_PATH_KEY,
          KEYSTORE_PASSWORD_KEY,
          KEYSTORE_TYPE_KEY,
          KEYSTORE_ALIAS_KEY);

  private static final String DEFAULT_BIND = "127.0.0.1";
  private static final Set<String> KEYSTORE_TYPES = Set.of("JKS", "PKCS12");
  private static final AdminWebProperties DISABLED =
      new AdminWebProperties(false, null, null, 0, null, null, List.of());

  private final boolean enabled;
  private final DeploymentProfile profile;
  private final InetAddress bindAddress;
  private final int port;
  private final URI origin;
  private final AdminTlsMaterial tls;
  private final List<String> trustedProxies;

  private AdminWebProperties(
      boolean enabled,
      DeploymentProfile profile,
      InetAddress bindAddress,
      int port,
      URI origin,
      AdminTlsMaterial tls,
      List<String> trustedProxies) {
    this.enabled = enabled;
    this.profile = profile;
    this.bindAddress = bindAddress;
    this.port = port;
    this.origin = origin;
    this.tls = tls;
    this.trustedProxies = List.copyOf(trustedProxies);
  }

  static AdminWebProperties load(Map<String, String> environment) {
    boolean supplied = environment.keySet().stream().anyMatch(key -> key.startsWith(PREFIX));
    if (!supplied) return DISABLED;
    DeploymentProfile profile = profile(environment.get(PROFILE_KEY));
    int port = port(environment.get(PORT_KEY));
    InetAddress bindAddress = bindAddress(environment.get(BIND_KEY), profile);
    URI origin = origin(environment.get(ORIGIN_KEY), profile, port);
    AdminTlsMaterial tls = tls(environment, profile);
    List<String> trustedProxies =
        trustedProxies(environment.get(PROXIES_KEY), profile, tls != null);
    return new AdminWebProperties(true, profile, bindAddress, port, origin, tls, trustedProxies);
  }

  private static DeploymentProfile profile(String value) {
    requireValue(value);
    try {
      return DeploymentProfile.fromWireValue(value);
    } catch (IllegalArgumentException unknownProfile) {
      throw refused();
    }
  }

  private static int port(String value) {
    requireValue(value);
    final int port;
    try {
      port = Integer.parseInt(value);
    } catch (NumberFormatException ex) {
      throw refused();
    }
    if (port < 1024 || port > 65535) refuse();
    return port;
  }

  /**
   * The interface the listener binds. Literal addresses only: a name would make the bind target
   * depend on resolver state at boot, and an administrative socket must fail the same way twice.
   */
  private static InetAddress bindAddress(String value, DeploymentProfile profile) {
    String token = value == null ? DEFAULT_BIND : value.trim();
    if (!looksLikeLiteralAddress(token)) refuse();
    final InetAddress address;
    try {
      address = InetAddress.getByName(withoutBrackets(token));
    } catch (UnknownHostException ex) {
      throw refused();
    }
    // An isolated fixture lives on loopback; a public interface is not a local fixture.
    if (profile.loopbackListenOnly() && !address.isLoopbackAddress()) refuse();
    return address;
  }

  private static URI origin(String value, DeploymentProfile profile, int port) {
    requireValue(value);
    final URI parsed;
    try {
      parsed = URI.create(value);
    } catch (IllegalArgumentException ex) {
      throw refused();
    }
    String host = parsed.getHost();
    if (!"https".equals(parsed.getScheme())
        || host == null
        || host.isEmpty()
        || parsed.getRawUserInfo() != null
        || parsed.getRawPath() == null
        || !parsed.getRawPath().isEmpty()
        || parsed.getRawQuery() != null
        || parsed.getRawFragment() != null) refuse();
    if (profile.loopbackListenOnly()) {
      // Reached directly: what the browser is told must be the socket it is talking to.
      if (!isLoopbackHost(host) || parsed.getPort() != port) refuse();
    } else if (isLoopbackHost(host)) {
      // A deployed administrative surface never advertises a loopback origin.
      refuse();
    }
    return parsed;
  }

  private static AdminTlsMaterial tls(Map<String, String> environment, DeploymentProfile profile) {
    String path = trimmed(environment.get(KEYSTORE_PATH_KEY));
    String password = environment.get(KEYSTORE_PASSWORD_KEY);
    String type = trimmed(environment.get(KEYSTORE_TYPE_KEY));
    String alias = trimmed(environment.get(KEYSTORE_ALIAS_KEY));
    boolean anyMaterial = path != null || password != null || type != null;
    boolean allMaterial = path != null && password != null && type != null;
    if (anyMaterial && !allMaterial) refuse();
    if (!allMaterial) {
      // A deployed listener may leave TLS to the proxy in front of it; a local fixture may not,
      // and an alias without a key store is a typo rather than a default.
      if (profile.tlsInProcessRequired() || alias != null) refuse();
      return null;
    }
    final Path keyStore;
    try {
      keyStore = Path.of(path);
    } catch (RuntimeException ex) {
      throw refused();
    }
    if (!keyStore.isAbsolute()) refuse();
    String normalizedType = type.toUpperCase(Locale.ROOT);
    if (!KEYSTORE_TYPES.contains(normalizedType)) refuse();
    try {
      return new AdminTlsMaterial(keyStore.normalize(), password, normalizedType, alias);
    } catch (IllegalArgumentException ex) {
      throw refused();
    }
  }

  /**
   * Proxy addresses that may supply the forwarded protocol/scheme headers.
   *
   * <p>Ignoring these headers makes every administrative write look like plain HTTP behind a
   * terminating proxy, while trusting them everywhere lets any client claim HTTPS. So the trust is
   * named explicitly, never inferred, and is bounded to the addresses given.
   */
  private static List<String> trustedProxies(
      String value, DeploymentProfile profile, boolean tlsInProcess) {
    String token = trimmed(value);
    if (profile.loopbackListenOnly()) {
      // The fixture terminates TLS itself; honouring a forwarded header there would let any local
      // client manufacture HTTPS.
      if (token != null) refuse();
      return List.of();
    }
    if (token == null) {
      if (tlsInProcess) return List.of();
      // Nothing terminates TLS: refuse to start rather than boot a listener that answers every
      // write with ORIGIN_REJECTED.
      throw refused();
    }
    var proxies = new ArrayList<String>();
    for (String candidate : token.split(",", -1)) {
      String address = candidate.trim();
      if (!looksLikeLiteralAddress(address) || "localhost".equalsIgnoreCase(address)) refuse();
      try {
        // Store the address in the same spelling the socket layer reports, so the match holds.
        proxies.add(InetAddress.getByName(withoutBrackets(address)).getHostAddress());
      } catch (UnknownHostException ex) {
        throw refused();
      }
    }
    if (proxies.isEmpty()) refuse();
    return List.copyOf(proxies);
  }

  private static boolean looksLikeLiteralAddress(String token) {
    return "localhost".equalsIgnoreCase(token)
        || token.matches("\\d{1,3}(\\.\\d{1,3}){3}")
        || token.indexOf(':') >= 0;
  }

  private static String withoutBrackets(String token) {
    return token.startsWith("[") && token.endsWith("]")
        ? token.substring(1, token.length() - 1)
        : token;
  }

  private static boolean isLoopbackHost(String host) {
    if (!looksLikeLiteralAddress(host)) return false;
    try {
      return InetAddress.getByName(withoutBrackets(host)).isLoopbackAddress();
    } catch (UnknownHostException ex) {
      return false;
    }
  }

  private static String trimmed(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static void requireValue(String value) {
    if (value == null || value.isBlank() || value.length() > 4096) refuse();
  }

  private static void refuse() {
    throw refused();
  }

  private static AdminStartupException refused() {
    return new AdminStartupException(AdminStartupException.Reason.CONFIGURATION);
  }

  boolean enabled() {
    return enabled;
  }

  DeploymentProfile profile() {
    return profile;
  }

  InetAddress bindAddress() {
    return bindAddress;
  }

  int port() {
    return port;
  }

  URI origin() {
    return origin;
  }

  Optional<AdminTlsMaterial> tls() {
    return Optional.ofNullable(tls);
  }

  List<String> trustedProxies() {
    return trustedProxies;
  }

  /**
   * The proxies as a regex over the peer address, which is the form the servlet container takes.
   */
  String trustedProxiesPattern() {
    return trustedProxies.stream().map(Pattern::quote).collect(Collectors.joining("|"));
  }

  AdminOriginPolicy originPolicy() {
    return origin == null ? AdminOriginPolicy.denyAll() : AdminOriginPolicy.allowing(origin);
  }

  @Override
  public String toString() {
    return "AdminWebProperties[REDACTED]";
  }
}
