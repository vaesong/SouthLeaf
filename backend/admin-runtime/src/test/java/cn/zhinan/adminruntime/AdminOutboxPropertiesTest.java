package cn.zhinan.adminruntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdminOutboxPropertiesTest {
  private static Map<String, String> valid() {
    Map<String, String> values = new HashMap<>();
    values.put("SOUTHLEAF_ADMIN_OUTBOX_MODE", "enabled");
    values.put("SOUTHLEAF_ADMIN_OUTBOX_DB_USERNAME", "southleaf_outbox");
    values.put("SOUTHLEAF_ADMIN_OUTBOX_DB_PASSWORD", "must-not-be-printed");
    values.put("SOUTHLEAF_ADMIN_OUTBOX_DB_EXPECTED_USER", "southleaf_outbox@localhost");
    values.put("SOUTHLEAF_ADMIN_OUTBOX_DELIVERY_MODE", "TEST_SINK");
    values.put("SOUTHLEAF_ADMIN_OUTBOX_MAX_ATTEMPTS", "5");
    values.put("SOUTHLEAF_ADMIN_OUTBOX_LEASE_SECONDS", "60");
    values.put("SOUTHLEAF_ADMIN_OUTBOX_RETRY_BASE_SECONDS", "5");
    values.put("SOUTHLEAF_ADMIN_OUTBOX_RETRY_MAX_SECONDS", "60");
    values.put("SOUTHLEAF_ADMIN_OUTBOX_BATCH_SIZE", "10");
    values.put("SOUTHLEAF_ADMIN_OUTBOX_POLL_INTERVAL_MS", "5000");
    values.put("SOUTHLEAF_ADMIN_OUTBOX_WORKER_ID", "worker-demo-1");
    return values;
  }

  @Test
  void explicitDemoSettingsBuildPolicyAndRedactCredentials() {
    var properties =
        AdminOutboxProperties.load(
            valid(), "demo", "southleaf_admin", "southleaf_admin_runtime", true);

    assertThat(properties.enabled()).isTrue();
    assertThat(properties.deliveryMode()).isEqualTo("TEST_SINK");
    assertThat(properties.policy().maxAttempts()).isEqualTo(5);
    assertThat(properties.policy().batchSize()).isEqualTo(10);
    assertThat(properties.pollIntervalMillis()).isEqualTo(5000);
    assertThat(properties.toString()).doesNotContain("must-not-be-printed");
  }

  @Test
  void absentSettingsDisableTheCapabilityButPartialSettingsFailClosed() {
    assertThat(
            AdminOutboxProperties.load(
                    Map.of(), "demo", "southleaf_admin", "southleaf_admin_runtime", true)
                .enabled())
        .isFalse();
    assertThatThrownBy(
            () ->
                AdminOutboxProperties.load(
                    Map.of("SOUTHLEAF_ADMIN_OUTBOX_MODE", "enabled"),
                    "demo",
                    "southleaf_admin",
                    "southleaf_admin_runtime",
                    true))
        .isInstanceOf(AdminStartupException.class);
  }

  @Test
  void theDemoSinkCannotBeEnabledForProductionOrWithoutWebSessionSecurity() {
    assertThatThrownBy(
            () ->
                AdminOutboxProperties.load(
                    valid(), "production", "southleaf_admin", "southleaf_admin_runtime", true))
        .isInstanceOf(AdminStartupException.class);
    assertThatThrownBy(
            () ->
                AdminOutboxProperties.load(
                    valid(), "demo", "southleaf_admin", "southleaf_admin_runtime", false))
        .isInstanceOf(AdminStartupException.class);
  }

  @Test
  void invalidRetryPolicyAndWorkerNameAreRejected() {
    Map<String, String> invalidPolicy = valid();
    invalidPolicy.put("SOUTHLEAF_ADMIN_OUTBOX_MAX_ATTEMPTS", "0");
    assertThatThrownBy(
            () ->
                AdminOutboxProperties.load(
                    invalidPolicy, "demo", "southleaf_admin", "southleaf_admin_runtime", true))
        .isInstanceOf(AdminStartupException.class);

    Map<String, String> invalidWorker = valid();
    invalidWorker.put("SOUTHLEAF_ADMIN_OUTBOX_WORKER_ID", "unsafe worker id");
    assertThatThrownBy(
            () ->
                AdminOutboxProperties.load(
                    invalidWorker, "demo", "southleaf_admin", "southleaf_admin_runtime", true))
        .isInstanceOf(AdminStartupException.class);
  }
}
