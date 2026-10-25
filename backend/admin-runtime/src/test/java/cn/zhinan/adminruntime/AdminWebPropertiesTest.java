package cn.zhinan.adminruntime;

import static org.assertj.core.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdminWebPropertiesTest {
  /** A disposable local fixture: loopback socket, in-process TLS, origin equal to the socket. */
  private static Map<String, String> isolated() {
    return new HashMap<>(
        Map.of(
            AdminWebProperties.PROFILE_KEY,
            "demo",
            AdminWebProperties.ORIGIN_KEY,
            "https://localhost:18443",
            AdminWebProperties.PORT_KEY,
            "18443",
            AdminWebProperties.KEYSTORE_PATH_KEY,
            "C:/owned/test.jks",
            AdminWebProperties.KEYSTORE_PASSWORD_KEY,
            "FAKE_SECRET_SENTINEL",
            AdminWebProperties.KEYSTORE_TYPE_KEY,
            "JKS"));
  }

  /** A deployment behind a terminating proxy: named origin, no key store, named proxy. */
  private static Map<String, String> deployed() {
    return new HashMap<>(
        Map.of(
            AdminWebProperties.PROFILE_KEY,
            "release",
            AdminWebProperties.ORIGIN_KEY,
            "https://admin.seekway.xyz",
            AdminWebProperties.PORT_KEY,
            "18443",
            AdminWebProperties.PROXIES_KEY,
            "127.0.0.1"));
  }

  @Test
  void isDisabledOnlyWhenNoWebInputExists() {
    assertThat(AdminWebProperties.load(Map.of()).enabled()).isFalse();
    for (String missing : isolated().keySet()) {
      var values = isolated();
      values.remove(missing);
      assertThatThrownBy(() -> AdminWebProperties.load(values))
          .hasMessage("ADMIN_RUNTIME_REFUSED:CONFIGURATION")
          .hasNoCause();
    }
  }

  @Test
  void acceptsOnlyDedicatedLocalHttpsListenerConfiguration() {
    var properties = AdminWebProperties.load(isolated());
    assertThat(properties.enabled()).isTrue();
    assertThat(properties.profile()).isEqualTo(DeploymentProfile.ISOLATED);
    assertThat(properties.bindAddress().isLoopbackAddress()).isTrue();
    assertThat(properties.origin().toString()).isEqualTo("https://localhost:18443");
    assertThat(properties.port()).isEqualTo(18443);
    assertThat(properties.tls()).isPresent();
    assertThat(properties.tls().orElseThrow().keyStore().toString()).contains("test.jks");
    assertThat(properties.trustedProxies()).isEmpty();
    assertThat(properties.toString()).isEqualTo("AdminWebProperties[REDACTED]");
    assertThat(properties.tls().orElseThrow().toString()).isEqualTo("AdminTlsMaterial[REDACTED]");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://localhost:18443",
        "https://localhost:18444",
        "https://user@localhost:18443",
        "https://localhost:18443/",
        "https://localhost:18443/path",
        "https://localhost:18443?x=y",
        "https://localhost:18443#part"
      })
  void rejectsNonCanonicalOrNonLocalOrigins(String origin) {
    var values = isolated();
    values.put(AdminWebProperties.ORIGIN_KEY, origin);
    assertThatThrownBy(() -> AdminWebProperties.load(values))
        .hasMessage("ADMIN_RUNTIME_REFUSED:CONFIGURATION");
  }

  @Test
  void acceptsAnyLoopbackOriginTheFixtureServesDirectly() {
    var literal = isolated();
    literal.put(AdminWebProperties.ORIGIN_KEY, "https://127.0.0.1:18443");
    assertThat(AdminWebProperties.load(literal).origin().getHost()).isEqualTo("127.0.0.1");

    var wrongPort = isolated();
    wrongPort.put(AdminWebProperties.ORIGIN_KEY, "https://[::1]:18444");
    assertThatThrownBy(() -> AdminWebProperties.load(wrongPort))
        .hasMessage("ADMIN_RUNTIME_REFUSED:CONFIGURATION");
  }

  @Test
  void rejectsUnsafeModePortsAndKeystoreInputs() {
    for (String key :
        new String[] {
          AdminWebProperties.PROFILE_KEY,
          AdminWebProperties.PORT_KEY,
          AdminWebProperties.KEYSTORE_PATH_KEY,
          AdminWebProperties.KEYSTORE_TYPE_KEY
        }) {
      var values = isolated();
      values.put(key, "bad");
      assertThatThrownBy(() -> AdminWebProperties.load(values))
          .hasMessage("ADMIN_RUNTIME_REFUSED:CONFIGURATION");
    }
    var relative = isolated();
    relative.put(AdminWebProperties.KEYSTORE_PATH_KEY, "relative.jks");
    assertThatThrownBy(() -> AdminWebProperties.load(relative))
        .hasMessage("ADMIN_RUNTIME_REFUSED:CONFIGURATION");
  }

  @Test
  void acceptsAConfiguredPublicOriginWithoutCompiledInNames() {
    var properties = AdminWebProperties.load(deployed());
    assertThat(properties.enabled()).isTrue();
    assertThat(properties.profile()).isEqualTo(DeploymentProfile.DEPLOYED);
    assertThat(properties.origin().getHost()).isEqualTo("admin.seekway.xyz");
    assertThat(properties.tls()).isEmpty();
    assertThat(properties.trustedProxies()).containsExactly("127.0.0.1");
    assertThat(properties.trustedProxiesPattern()).isEqualTo("\\Q127.0.0.1\\E");
    assertThat(properties.originPolicy().accepts("https://admin.seekway.xyz")).isTrue();
  }

  @Test
  void acceptsAnInProcessKeyStoreInsteadOfANamedProxy() {
    var values = deployed();
    values.remove(AdminWebProperties.PROXIES_KEY);
    assertThatThrownBy(() -> AdminWebProperties.load(values))
        .hasMessage("ADMIN_RUNTIME_REFUSED:CONFIGURATION");

    values.putAll(
        Map.of(
            AdminWebProperties.KEYSTORE_PATH_KEY, "C:/owned/test.jks",
            AdminWebProperties.KEYSTORE_PASSWORD_KEY, "FAKE_SECRET_SENTINEL",
            AdminWebProperties.KEYSTORE_TYPE_KEY, "PKCS12"));
    var properties = AdminWebProperties.load(values);
    assertThat(properties.tls()).isPresent();
    assertThat(properties.tls().orElseThrow().type()).isEqualTo("PKCS12");
    assertThat(properties.trustedProxies()).isEmpty();
  }

  @Test
  void refusesPartialOrMisplacedSecretsAndProxyTrust() {
    var partial = isolated();
    partial.remove(AdminWebProperties.KEYSTORE_PASSWORD_KEY);
    assertThatThrownBy(() -> AdminWebProperties.load(partial))
        .hasMessage("ADMIN_RUNTIME_REFUSED:CONFIGURATION");

    var aliasWithoutStore = deployed();
    aliasWithoutStore.put(AdminWebProperties.KEYSTORE_ALIAS_KEY, "admin");
    assertThatThrownBy(() -> AdminWebProperties.load(aliasWithoutStore))
        .hasMessage("ADMIN_RUNTIME_REFUSED:CONFIGURATION");

    var proxyInFixture = isolated();
    proxyInFixture.put(AdminWebProperties.PROXIES_KEY, "127.0.0.1");
    assertThatThrownBy(() -> AdminWebProperties.load(proxyInFixture))
        .hasMessage("ADMIN_RUNTIME_REFUSED:CONFIGURATION");

    var wildcardProxy = deployed();
    wildcardProxy.put(AdminWebProperties.PROXIES_KEY, "0.0.0.0/0");
    assertThatThrownBy(() -> AdminWebProperties.load(wildcardProxy))
        .hasMessage("ADMIN_RUNTIME_REFUSED:CONFIGURATION");
  }

  @Test
  void bindsOnlyWhereTheProfileAllows() {
    var publicFixture = isolated();
    publicFixture.put(AdminWebProperties.BIND_KEY, "0.0.0.0");
    assertThatThrownBy(() -> AdminWebProperties.load(publicFixture))
        .hasMessage("ADMIN_RUNTIME_REFUSED:CONFIGURATION");

    var namedOriginInFixture = isolated();
    namedOriginInFixture.put(AdminWebProperties.ORIGIN_KEY, "https://admin.seekway.xyz:18443");
    assertThatThrownBy(() -> AdminWebProperties.load(namedOriginInFixture))
        .hasMessage("ADMIN_RUNTIME_REFUSED:CONFIGURATION");

    var loopbackOriginWhenDeployed = deployed();
    loopbackOriginWhenDeployed.put(AdminWebProperties.ORIGIN_KEY, "https://localhost:18443");
    assertThatThrownBy(() -> AdminWebProperties.load(loopbackOriginWhenDeployed))
        .hasMessage("ADMIN_RUNTIME_REFUSED:CONFIGURATION");

    var wildcardBind = deployed();
    wildcardBind.put(AdminWebProperties.BIND_KEY, "0.0.0.0");
    assertThat(AdminWebProperties.load(wildcardBind).bindAddress().isAnyLocalAddress()).isTrue();

    var namedBind = deployed();
    namedBind.put(AdminWebProperties.BIND_KEY, "intranet.example");
    assertThatThrownBy(() -> AdminWebProperties.load(namedBind))
        .hasMessage("ADMIN_RUNTIME_REFUSED:CONFIGURATION");
  }
}
