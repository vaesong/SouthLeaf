package cn.zhinan.adminruntime;

import static org.assertj.core.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdminWebPropertiesTest {
  private static Map<String, String> valid() {
    return new HashMap<>(
        Map.of(
            "SOUTHLEAF_ADMIN_WEB_MODE",
            "demo",
            "SOUTHLEAF_ADMIN_WEB_ORIGIN",
            "https://localhost:18443",
            "SOUTHLEAF_ADMIN_WEB_PORT",
            "18443",
            "SOUTHLEAF_ADMIN_WEB_KEYSTORE_PATH",
            "C:/owned/test.jks",
            "SOUTHLEAF_ADMIN_WEB_KEYSTORE_PASSWORD",
            "FAKE_SECRET_SENTINEL",
            "SOUTHLEAF_ADMIN_WEB_KEYSTORE_TYPE",
            "JKS"));
  }

  @Test
  void isDisabledOnlyWhenNoWebInputExists() {
    assertThat(AdminWebProperties.load(Map.of()).enabled()).isFalse();
    for (String missing : valid().keySet()) {
      var values = valid();
      values.remove(missing);
      assertThatThrownBy(() -> AdminWebProperties.load(values))
          .hasMessage("ADMIN_RUNTIME_REFUSED:CONFIGURATION")
          .hasNoCause();
    }
  }

  @Test
  void acceptsOnlyDedicatedLocalHttpsListenerConfiguration() {
    var properties = AdminWebProperties.load(valid());
    assertThat(properties.enabled()).isTrue();
    assertThat(properties.origin().toString()).isEqualTo("https://localhost:18443");
    assertThat(properties.port()).isEqualTo(18443);
    assertThat(properties.keyStorePath().toString()).contains("test.jks");
    assertThat(properties.toString()).isEqualTo("AdminWebProperties[REDACTED]");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://localhost:18443",
        "https://127.0.0.1:18443",
        "https://localhost:18444",
        "https://user@localhost:18443",
        "https://localhost:18443/",
        "https://localhost:18443/path",
        "https://localhost:18443?x=y",
        "https://localhost:18443#part"
      })
  void rejectsNonCanonicalOrNonLocalOrigins(String origin) {
    var values = valid();
    values.put("SOUTHLEAF_ADMIN_WEB_ORIGIN", origin);
    assertThatThrownBy(() -> AdminWebProperties.load(values))
        .hasMessage("ADMIN_RUNTIME_REFUSED:CONFIGURATION");
  }

  @Test
  void rejectsUnsafeModePortsAndKeystoreInputs() {
    for (String key :
        new String[] {
          "SOUTHLEAF_ADMIN_WEB_MODE",
          "SOUTHLEAF_ADMIN_WEB_PORT",
          "SOUTHLEAF_ADMIN_WEB_KEYSTORE_PATH",
          "SOUTHLEAF_ADMIN_WEB_KEYSTORE_TYPE"
        }) {
      var values = valid();
      values.put(key, "bad");
      assertThatThrownBy(() -> AdminWebProperties.load(values))
          .hasMessage("ADMIN_RUNTIME_REFUSED:CONFIGURATION");
    }
    var relative = valid();
    relative.put("SOUTHLEAF_ADMIN_WEB_KEYSTORE_PATH", "relative.jks");
    assertThatThrownBy(() -> AdminWebProperties.load(relative))
        .hasMessage("ADMIN_RUNTIME_REFUSED:CONFIGURATION");
  }
}
