package cn.zhinan.adminruntime;

import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AdminLoginRateLimiterTest {
  @Test
  void allowsFiveAttemptsThenReturnsBoundedRetryAndClearsOnSuccess() {
    var limiter =
        new AdminLoginRateLimiter(
            Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC));
    for (int i = 0; i < 5; i++)
      assertThat(limiter.tryAcquire("https://localhost:18443", "staff").allowed()).isTrue();
    var limited = limiter.tryAcquire("https://localhost:18443", "staff");
    assertThat(limited.allowed()).isFalse();
    assertThat(limited.retryAfterSeconds()).isEqualTo(300);
    assertThat(limiter.tryAcquire("https://localhost:18443", "another").allowed()).isTrue();

    limiter.recordSuccess("https://localhost:18443", "staff");
    assertThat(limiter.tryAcquire("https://localhost:18443", "staff").allowed()).isTrue();
  }
}
