package cn.zhinan.adminruntime;

import static org.assertj.core.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdminRuntimePropertiesTest {
  static Map<String, String> valid() {
    return new HashMap<>(
        Map.of(
            "SOUTHLEAF_ADMIN_MODE",
            "demo",
            "SOUTHLEAF_ADMIN_DB_URL",
            "jdbc:mysql://localhost:1/southleaf_admin_it?sslMode=REQUIRED",
            "SOUTHLEAF_ADMIN_DB_USERNAME",
            "fixture_writer",
            "SOUTHLEAF_ADMIN_DB_PASSWORD",
            "FAKE_SECRET_SENTINEL",
            "SOUTHLEAF_ADMIN_DB_SCHEMA",
            "southleaf_admin_it",
            "SOUTHLEAF_ADMIN_DB_EXPECTED_USER",
            "fixture_writer@localhost"));
  }

  static AdminRuntimeProperties properties() {
    return load(valid());
  }

  static AdminRuntimeProperties load(Map<String, String> env) {
    return AdminRuntimeProperties.load(env, new Properties());
  }

  @Test
  void valuesStayPrivateAndToStringIsRedacted() {
    var p = properties();
    assertThat(p.password()).isEqualTo("FAKE_SECRET_SENTINEL");
    assertThat(p.toString()).isEqualTo("AdminRuntimeProperties[REDACTED]");
  }

  @Test
  void everyManagementValueIsRequiredAndNeverFallsBack() {
    for (String key : valid().keySet())
      for (String replacement : new String[] {null, "", " "}) {
        var env = valid();
        if (replacement == null) env.remove(key);
        else env.put(key, replacement);
        env.put("SOUTHLEAF_DB_URL", valid().get("SOUTHLEAF_ADMIN_DB_URL"));
        env.put("SOUTHLEAF_DB_USERNAME", "reader");
        env.put("SOUTHLEAF_DB_PASSWORD", "public-secret");
        assertThatThrownBy(() -> load(env))
            .isInstanceOf(AdminStartupException.class)
            .hasMessage("ADMIN_RUNTIME_REFUSED:CONFIGURATION")
            .hasNoCause();
      }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SPRING_CONFIG_LOCATION",
        "SPRING_CONFIG_IMPORT",
        "SPRING_CONFIG_NAME",
        "SPRING_PROFILES_ACTIVE",
        "SPRING_APPLICATION_JSON",
        "SPRING_MAIN_WEB_APPLICATION_TYPE",
        "LOGGING_CONFIG",
        "LOGBACK_CONFIGURATION_FILE",
        "SERVER_PORT",
        "DEBUG",
        "TRACE",
        "SOUTHLEAF_ADMIN_BYPASS",
        "SOUTHLEAF_ADMIN_MIGRATION_URL"
      })
  void rejectsExternalOverrideSources(String key) {
    var env = valid();
    env.put(key, "not-even-read");
    assertThatThrownBy(() -> load(env)).hasMessage("ADMIN_RUNTIME_REFUSED:CONFIG_SOURCE");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "spring.config.location",
        "spring.profiles.active",
        "spring.main.web-application-type",
        "logging.config",
        "logback.configurationFile",
        "org.springframework.boot.logging.LoggingSystem",
        "southleaf.admin.runtime.config-id",
        "debug",
        "server.port"
      })
  void rejectsJvmOverrideSources(String key) {
    var sys = new Properties();
    sys.setProperty(key, "not-even-read");
    assertThatThrownBy(() -> AdminRuntimeProperties.load(valid(), sys))
        .hasMessage("ADMIN_RUNTIME_REFUSED:CONFIG_SOURCE");
  }

  @Test
  void rejectsRootAndAmbiguousAccounts() {
    for (String name : new String[] {"root", "ROOT", "user@host", " user", "user'"}) {
      var env = valid();
      env.put("SOUTHLEAF_ADMIN_DB_USERNAME", name);
      env.put("SOUTHLEAF_ADMIN_DB_EXPECTED_USER", name + "@localhost");
      assertThatThrownBy(() -> load(env)).isInstanceOf(AdminStartupException.class);
    }
    for (String expected :
        new String[] {
          "someone@localhost",
          "fixture_writer",
          "fixture_writer@",
          "fixture_writer@a@b",
          "fixture_writer@localhost "
        }) {
      var env = valid();
      env.put("SOUTHLEAF_ADMIN_DB_EXPECTED_USER", expected);
      assertThatThrownBy(() -> load(env)).isInstanceOf(AdminStartupException.class);
    }
  }

  @Test
  void errorsNeverContainInputOrCause() {
    var env = valid();
    env.put(
        "SOUTHLEAF_ADMIN_DB_URL", "jdbc:mysql://hidden:secret@somewhere/private?sslMode=REQUIRED");
    assertThatThrownBy(() -> load(env))
        .hasMessage("ADMIN_RUNTIME_REFUSED:CONFIGURATION")
        .hasNoCause();
  }

  @Test
  void argumentAndMissingConfigPathsFailBeforeBoot() {
    assertThat(
            AdminRuntimeApplication.run(
                new String[] {"--check", "--check"}, valid(), new Properties()))
        .isEqualTo(2);
    assertThat(AdminRuntimeApplication.run(new String[] {"--check"}, Map.of(), new Properties()))
        .isEqualTo(2);
  }

  @Test
  void unexpectedStartupFailuresExposeOnlySafeTypeNames() {
    var failure =
        new IllegalStateException(
            "jdbc:mysql://example.invalid/admin?password=secret",
            new IllegalArgumentException("private diagnostic"));
    assertThat(AdminRuntimeApplication.reason(failure))
        .isEqualTo(
            "INTERNAL_ILLEGALSTATEEXCEPTION_CAUSE_ILLEGALARGUMENTEXCEPTION_AT_ADMINRUNTIMEPROPERTIESTEST")
        .doesNotContain("secret", "private", "jdbc", "example");
  }

  @Test
  void explicitStartupReasonsRemainStable() {
    assertThat(
            AdminRuntimeApplication.reason(
                new AdminStartupException(AdminStartupException.Reason.TLS)))
        .isEqualTo("TLS");
  }
}
