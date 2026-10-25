package cn.zhinan.adminruntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class AdminBookingPropertiesTest {
  @Test
  void enablesWhenTheWebSurfaceIsUpAndTheDataModeMatchesTheRuntime() {
    var properties = AdminRuntimeProperties.load(configured(), new Properties());

    assertThat(properties.booking().enabled()).isTrue();
    assertThat(properties.booking().databaseUser()).isEqualTo("southleaf_booking_writer");
    assertThat(properties.booking().expectedUser()).isEqualTo("southleaf_booking_writer@localhost");
    assertThat(properties.booking().toString()).doesNotContain("BOOKING_SENTINEL");
    assertThat(properties.toString()).doesNotContain("BOOKING_SENTINEL");
  }

  @Test
  void absentBookingSettingsRemainDisabledAndPartialSettingsFailClosed() {
    assertThat(
            AdminRuntimeProperties.load(AdminRuntimePropertiesTest.valid(), new Properties())
                .booking()
                .enabled())
        .isFalse();

    var partial = AdminRuntimePropertiesTest.valid();
    partial.put("SOUTHLEAF_ADMIN_BOOKING_MODE", "enabled");
    assertThatThrownBy(() -> AdminRuntimeProperties.load(partial, new Properties()))
        .isInstanceOf(AdminStartupException.class);
  }

  @Test
  void rejectsBookingWithoutTheWebSurface() {
    var noWeb = configured();
    noWeb.remove("SOUTHLEAF_ADMIN_WEB_MODE");
    noWeb.remove("SOUTHLEAF_ADMIN_WEB_ORIGIN");
    noWeb.remove("SOUTHLEAF_ADMIN_WEB_PORT");
    noWeb.remove("SOUTHLEAF_ADMIN_WEB_KEYSTORE_PATH");
    noWeb.remove("SOUTHLEAF_ADMIN_WEB_KEYSTORE_PASSWORD");
    noWeb.remove("SOUTHLEAF_ADMIN_WEB_KEYSTORE_TYPE");
    assertThatThrownBy(() -> AdminRuntimeProperties.load(noWeb, new Properties()))
        .isInstanceOf(AdminStartupException.class);
  }

  @Test
  void confirmationStaysAvailableWithoutTheNotificationDispatcher() {
    // Confirming writes outbox rows inside the transaction; draining them is a separate process,
    // so a runtime without the dispatcher can still take a booking.
    var noDispatcher = configured();
    noDispatcher.keySet().removeIf(key -> key.startsWith("SOUTHLEAF_ADMIN_OUTBOX_"));
    var properties = AdminRuntimeProperties.load(noDispatcher, new Properties());
    assertThat(properties.booking().enabled()).isTrue();
    assertThat(properties.outbox().enabled()).isFalse();
  }

  @Test
  void confirmationInProductionRequiresProductionPrices() {
    var demoPrices = production();
    demoPrices.put("SOUTHLEAF_ADMIN_BOOKING_DATA_MODE", "SYNTHETIC");
    assertThatThrownBy(() -> AdminRuntimeProperties.load(demoPrices, new Properties()))
        .isInstanceOf(AdminStartupException.class);

    var production = production();
    assertThat(AdminRuntimeProperties.load(production, new Properties()).booking().enabled())
        .isTrue();
  }

  @Test
  void aDemoRuntimeCannotQuoteProductionPrices() {
    var values = configured();
    values.put("SOUTHLEAF_ADMIN_BOOKING_DATA_MODE", "PRODUCTION");
    assertThatThrownBy(() -> AdminRuntimeProperties.load(values, new Properties()))
        .isInstanceOf(AdminStartupException.class);
  }

  @Test
  void bookingWriterMustBeTheDedicatedIdentityAndDistinctFromEveryOtherPool() {
    for (String username : new String[] {"fixture_writer", "southleaf_outbox", "southleaf_admin"}) {
      var env = configured();
      env.put("SOUTHLEAF_ADMIN_BOOKING_DB_USERNAME", username);
      env.put("SOUTHLEAF_ADMIN_BOOKING_DB_EXPECTED_USER", username + "@localhost");
      assertThatThrownBy(() -> AdminRuntimeProperties.load(env, new Properties()))
          .isInstanceOf(AdminStartupException.class);
    }

    for (int collision = 0; collision < 4; collision++) {
      int samePool = collision;
      assertThatThrownBy(
              () ->
                  AdminBookingProperties.load(
                      configured(),
                      "demo",
                      samePool == 0 ? "southleaf_booking_writer" : "fixture_writer",
                      samePool == 1 ? "southleaf_booking_writer" : "southleaf_outbox",
                      samePool == 2 ? "southleaf_booking_writer" : "content_business",
                      samePool == 3 ? "southleaf_booking_writer" : "content_publication",
                      true))
          .isInstanceOf(AdminStartupException.class);
    }
  }

  @Test
  void requiresAnExplicitDataModeAndRejectsAnythingButSyntheticOrProduction() {
    var missing = configured();
    missing.remove("SOUTHLEAF_ADMIN_BOOKING_DATA_MODE");
    assertThatThrownBy(() -> AdminRuntimeProperties.load(missing, new Properties()))
        .isInstanceOf(AdminStartupException.class);

    var nonsense = configured();
    nonsense.put("SOUTHLEAF_ADMIN_BOOKING_DATA_MODE", "DEMO");
    assertThatThrownBy(() -> AdminRuntimeProperties.load(nonsense, new Properties()))
        .isInstanceOf(AdminStartupException.class);

    var production = production();
    assertThat(AdminRuntimeProperties.load(production, new Properties()).booking().dataMode())
        .isEqualTo("PRODUCTION");
  }

  @Test
  void rejectsUnexpectedBookingConfigurationNamesAndSecretsInJvmProperties() {
    var env = configured();
    env.put("SOUTHLEAF_ADMIN_BOOKING_ALLOW_PROD", "true");
    assertThatThrownBy(() -> AdminRuntimeProperties.load(env, new Properties()))
        .hasMessage("ADMIN_RUNTIME_REFUSED:CONFIG_SOURCE");

    var system = new Properties();
    system.setProperty("southleaf.admin.booking.mode", "enabled");
    assertThatThrownBy(() -> AdminRuntimeProperties.load(configured(), system))
        .hasMessage("ADMIN_RUNTIME_REFUSED:CONFIG_SOURCE");
  }

  /** The same surface in production: named origin, no key store, production prices. */
  static Map<String, String> production() {
    Map<String, String> values = new HashMap<>(configured());
    values.put("SOUTHLEAF_ADMIN_MODE", "production");
    values.put("SOUTHLEAF_ADMIN_DB_SCHEMA", "southleaf_admin");
    values.put(
        "SOUTHLEAF_ADMIN_DB_URL",
        "jdbc:mysql://localhost:1/southleaf_admin?sslMode=VERIFY_IDENTITY");
    values.put("SOUTHLEAF_ADMIN_WEB_MODE", "release");
    values.put("SOUTHLEAF_ADMIN_WEB_ORIGIN", "https://admin.example.com");
    values.put("SOUTHLEAF_ADMIN_WEB_PORT", "18443");
    values.put("SOUTHLEAF_ADMIN_WEB_TRUSTED_PROXIES", "127.0.0.1");
    values.keySet().removeIf(key -> key.startsWith("SOUTHLEAF_ADMIN_WEB_KEYSTORE_"));
    values.keySet().removeIf(key -> key.startsWith("SOUTHLEAF_ADMIN_OUTBOX_"));
    values.put("SOUTHLEAF_ADMIN_BOOKING_DATA_MODE", "PRODUCTION");
    return values;
  }

  static Map<String, String> configured() {
    Map<String, String> values = new HashMap<>(AdminRuntimePropertiesTest.valid());
    values.put("SOUTHLEAF_ADMIN_WEB_MODE", "demo");
    values.put("SOUTHLEAF_ADMIN_WEB_ORIGIN", "https://localhost:18443");
    values.put("SOUTHLEAF_ADMIN_WEB_PORT", "18443");
    values.put("SOUTHLEAF_ADMIN_WEB_KEYSTORE_PATH", "C:/owned/test.jks");
    values.put("SOUTHLEAF_ADMIN_WEB_KEYSTORE_PASSWORD", "WEB_SENTINEL");
    values.put("SOUTHLEAF_ADMIN_WEB_KEYSTORE_TYPE", "JKS");
    values.put("SOUTHLEAF_ADMIN_OUTBOX_MODE", "enabled");
    values.put("SOUTHLEAF_ADMIN_OUTBOX_DB_USERNAME", "southleaf_outbox");
    values.put("SOUTHLEAF_ADMIN_OUTBOX_DB_PASSWORD", "OUTBOX_SENTINEL");
    values.put("SOUTHLEAF_ADMIN_OUTBOX_DB_EXPECTED_USER", "southleaf_outbox@localhost");
    values.put("SOUTHLEAF_ADMIN_OUTBOX_DELIVERY_MODE", "TEST_SINK");
    values.put("SOUTHLEAF_ADMIN_OUTBOX_MAX_ATTEMPTS", "5");
    values.put("SOUTHLEAF_ADMIN_OUTBOX_LEASE_SECONDS", "60");
    values.put("SOUTHLEAF_ADMIN_OUTBOX_RETRY_BASE_SECONDS", "5");
    values.put("SOUTHLEAF_ADMIN_OUTBOX_RETRY_MAX_SECONDS", "60");
    values.put("SOUTHLEAF_ADMIN_OUTBOX_BATCH_SIZE", "10");
    values.put("SOUTHLEAF_ADMIN_OUTBOX_POLL_INTERVAL_MS", "5000");
    values.put("SOUTHLEAF_ADMIN_OUTBOX_WORKER_ID", "worker-demo-1");
    values.put("SOUTHLEAF_ADMIN_BOOKING_MODE", "enabled");
    values.put("SOUTHLEAF_ADMIN_BOOKING_DB_USERNAME", "southleaf_booking_writer");
    values.put("SOUTHLEAF_ADMIN_BOOKING_DB_PASSWORD", "BOOKING_SENTINEL");
    values.put("SOUTHLEAF_ADMIN_BOOKING_DB_EXPECTED_USER", "southleaf_booking_writer@localhost");
    values.put("SOUTHLEAF_ADMIN_BOOKING_DATA_MODE", "SYNTHETIC");
    return values;
  }
}
