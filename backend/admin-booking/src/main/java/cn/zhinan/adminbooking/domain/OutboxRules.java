package cn.zhinan.adminbooking.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Policy-independent bounds and transition rules for the notification outbox.
 *
 * <p>The attempt budget, lease duration and retry intervals are deployment policy, not hardcoded
 * business operating values. {@link cn.zhinan.adminbooking.application.OutboxPolicy} requires the
 * runtime to provide them explicitly. These bounds only protect the storage and worker from
 * unbounded input.
 */
public final class OutboxRules {
  private OutboxRules() {}

  public static final int MAX_ATTEMPTS = 100;
  public static final Duration MAX_LEASE_DURATION = Duration.ofHours(1);
  public static final Duration MAX_RETRY_DELAY = Duration.ofDays(7);
  public static final int MAX_LEASE_BATCH = 100;

  public static int maxAttempts(int value) {
    if (value < 1 || value > MAX_ATTEMPTS)
      throw new BookingException(BookingFailure.INVALID_REQUEST, "OUTBOX_ATTEMPTS_INVALID");
    return value;
  }

  public static Duration leaseDuration(Duration value) {
    if (value == null
        || value.isNegative()
        || value.isZero()
        || value.compareTo(MAX_LEASE_DURATION) > 0)
      throw new BookingException(BookingFailure.INVALID_REQUEST, "OUTBOX_LEASE_INVALID");
    return value;
  }

  public static int leaseBatch(int value) {
    if (value < 1 || value > MAX_LEASE_BATCH)
      throw new BookingException(BookingFailure.INVALID_REQUEST, "OUTBOX_BATCH_INVALID");
    return value;
  }

  /** A bounded, non-secret lease owner identifier. */
  public static String workerOwner(String value) {
    if (value == null || !value.matches("[A-Za-z0-9._:-]{1,64}"))
      throw new BookingException(BookingFailure.INVALID_REQUEST, "OUTBOX_OWNER_INVALID");
    return value;
  }

  /** A bounded stable code only; provider messages and exception text are never persisted. */
  public static String reasonCode(String value) {
    if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,63}")) return "DELIVERY_FAILED";
    return value;
  }

  /**
   * Whether another attempt is still allowed. The attempt counter is increased when a message is
   * leased, so the message that has already used its last attempt is detected before redelivery.
   */
  public static boolean mayRetry(int attempts, int maxAttempts) {
    return attempts < maxAttempts;
  }

  /**
   * Exponential retry delay using the explicitly supplied deployment policy, with no hardcoded
   * operating interval.
   */
  public static Duration retryDelay(int attempts, Duration baseDelay, Duration maximumDelay) {
    if (attempts < 1
        || baseDelay == null
        || baseDelay.isNegative()
        || baseDelay.isZero()
        || maximumDelay == null
        || maximumDelay.compareTo(baseDelay) < 0
        || maximumDelay.compareTo(MAX_RETRY_DELAY) > 0)
      throw new BookingException(BookingFailure.INVALID_REQUEST, "OUTBOX_RETRY_POLICY_INVALID");
    int shift = Math.min(attempts - 1, 20);
    Duration delay;
    try {
      delay = baseDelay.multipliedBy(1L << shift);
    } catch (ArithmeticException overflow) {
      return maximumDelay;
    }
    return delay.compareTo(maximumDelay) > 0 ? maximumDelay : delay;
  }

  public static Instant nextAttemptAt(
      Instant now, int attempts, Duration baseDelay, Duration maximumDelay) {
    return now.plus(retryDelay(attempts, baseDelay, maximumDelay));
  }

  public static boolean becomesDead(boolean retryable, int attempts, int maxAttempts) {
    if (!retryable) return true;
    return !mayRetry(attempts, maxAttempts);
  }
}
