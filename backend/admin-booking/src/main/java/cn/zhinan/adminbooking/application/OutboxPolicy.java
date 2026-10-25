package cn.zhinan.adminbooking.application;

import cn.zhinan.adminbooking.domain.OutboxRules;
import java.time.Duration;

/**
 * Deployment supplied notification delivery policy. This keeps retry counts and intervals out of
 * the domain as unresolved operating values; a future runtime configuration must supply them.
 */
public record OutboxPolicy(
    int maxAttempts,
    Duration leaseDuration,
    Duration baseRetryDelay,
    Duration maximumRetryDelay,
    int batchSize) {

  public OutboxPolicy {
    OutboxRules.maxAttempts(maxAttempts);
    OutboxRules.leaseDuration(leaseDuration);
    OutboxRules.leaseBatch(batchSize);
    // Validate the interval pair without choosing an operating value.
    OutboxRules.retryDelay(1, baseRetryDelay, maximumRetryDelay);
  }
}
