package cn.zhinan.adminruntime;

import cn.zhinan.adminbooking.application.OutboxPolicy;
import cn.zhinan.adminbooking.domain.OutboxRules;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** Optional local-only outbox composition. Every operating interval is supplied explicitly. */
final class AdminOutboxProperties {
  static final Set<String> KEYS =
      Set.of(
          "SOUTHLEAF_ADMIN_OUTBOX_MODE",
          "SOUTHLEAF_ADMIN_OUTBOX_DB_USERNAME",
          "SOUTHLEAF_ADMIN_OUTBOX_DB_PASSWORD",
          "SOUTHLEAF_ADMIN_OUTBOX_DB_EXPECTED_USER",
          "SOUTHLEAF_ADMIN_OUTBOX_DELIVERY_MODE",
          "SOUTHLEAF_ADMIN_OUTBOX_MAX_ATTEMPTS",
          "SOUTHLEAF_ADMIN_OUTBOX_LEASE_SECONDS",
          "SOUTHLEAF_ADMIN_OUTBOX_RETRY_BASE_SECONDS",
          "SOUTHLEAF_ADMIN_OUTBOX_RETRY_MAX_SECONDS",
          "SOUTHLEAF_ADMIN_OUTBOX_BATCH_SIZE",
          "SOUTHLEAF_ADMIN_OUTBOX_POLL_INTERVAL_MS",
          "SOUTHLEAF_ADMIN_OUTBOX_WORKER_ID");
  static final AdminOutboxProperties DISABLED =
      new AdminOutboxProperties(false, null, null, null, null, null, 0, null);

  private final boolean enabled;
  private final String databaseUser;
  private final String databasePassword;
  private final String expectedUser;
  private final String workerId;
  private final OutboxPolicy policy;
  private final long pollIntervalMillis;
  private final String deliveryMode;

  private AdminOutboxProperties(
      boolean enabled,
      String databaseUser,
      String databasePassword,
      String expectedUser,
      String workerId,
      OutboxPolicy policy,
      long pollIntervalMillis,
      String deliveryMode) {
    this.enabled = enabled;
    this.databaseUser = databaseUser;
    this.databasePassword = databasePassword;
    this.expectedUser = expectedUser;
    this.workerId = workerId;
    this.policy = policy;
    this.pollIntervalMillis = pollIntervalMillis;
    this.deliveryMode = deliveryMode;
  }

  static AdminOutboxProperties load(
      Map<String, String> env,
      String adminMode,
      String schema,
      String runtimeUser,
      boolean webEnabled) {
    String mode = env.get("SOUTHLEAF_ADMIN_OUTBOX_MODE");
    boolean supplied = KEYS.stream().anyMatch(env::containsKey);
    if (mode == null) {
      if (supplied) refuse();
      return DISABLED;
    }
    if (!"enabled".equals(mode) || !webEnabled) refuse();
    // The test sink never reports a real delivery, so it may only run where nobody relies on it.
    // The day a real channel exists, this is the single line that has to change.
    if (!"TEST_SINK".equals(env.get("SOUTHLEAF_ADMIN_OUTBOX_DELIVERY_MODE"))
        || "production".equals(adminMode)) refuse();
    for (String key : KEYS) requireValue(env.get(key));

    String username = env.get("SOUTHLEAF_ADMIN_OUTBOX_DB_USERNAME");
    String expected = env.get("SOUTHLEAF_ADMIN_OUTBOX_DB_EXPECTED_USER");
    if (!username.matches("[A-Za-z][A-Za-z0-9_]{0,31}")
        || username.equalsIgnoreCase("root")
        || username.equalsIgnoreCase(runtimeUser)
        || !expected.matches("[A-Za-z][A-Za-z0-9_]{0,31}@[A-Za-z0-9._%:/-]{1,255}")
        || !expected.startsWith(username + "@")
        || !schema.matches("[a-z][a-z0-9_]{0,63}")) refuse();

    String delivery = env.get("SOUTHLEAF_ADMIN_OUTBOX_DELIVERY_MODE");
    int maxAttempts = integer(env, "SOUTHLEAF_ADMIN_OUTBOX_MAX_ATTEMPTS");
    int leaseSeconds = integer(env, "SOUTHLEAF_ADMIN_OUTBOX_LEASE_SECONDS");
    int baseRetrySeconds = integer(env, "SOUTHLEAF_ADMIN_OUTBOX_RETRY_BASE_SECONDS");
    int maxRetrySeconds = integer(env, "SOUTHLEAF_ADMIN_OUTBOX_RETRY_MAX_SECONDS");
    int batchSize = integer(env, "SOUTHLEAF_ADMIN_OUTBOX_BATCH_SIZE");
    long pollMillis = longValue(env, "SOUTHLEAF_ADMIN_OUTBOX_POLL_INTERVAL_MS");
    if (pollMillis < 1000 || pollMillis > 300000) refuse();
    String worker;
    OutboxPolicy policy;
    try {
      worker = OutboxRules.workerOwner(env.get("SOUTHLEAF_ADMIN_OUTBOX_WORKER_ID"));
      policy =
          new OutboxPolicy(
              maxAttempts,
              Duration.ofSeconds(leaseSeconds),
              Duration.ofSeconds(baseRetrySeconds),
              Duration.ofSeconds(maxRetrySeconds),
              batchSize);
    } catch (RuntimeException invalid) {
      throw refused();
    }
    return new AdminOutboxProperties(
        true,
        username,
        env.get("SOUTHLEAF_ADMIN_OUTBOX_DB_PASSWORD"),
        expected,
        worker,
        policy,
        pollMillis,
        delivery);
  }

  private static int integer(Map<String, String> env, String name) {
    try {
      return Integer.parseInt(env.get(name));
    } catch (RuntimeException invalid) {
      throw refused();
    }
  }

  private static long longValue(Map<String, String> env, String name) {
    try {
      return Long.parseLong(env.get(name));
    } catch (RuntimeException invalid) {
      throw refused();
    }
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

  String databaseUser() {
    return databaseUser;
  }

  String databasePassword() {
    return databasePassword;
  }

  String expectedUser() {
    return expectedUser;
  }

  String workerId() {
    return workerId;
  }

  OutboxPolicy policy() {
    return policy;
  }

  long pollIntervalMillis() {
    return pollIntervalMillis;
  }

  String deliveryMode() {
    return deliveryMode;
  }

  @Override
  public String toString() {
    return "AdminOutboxProperties[REDACTED]";
  }
}
