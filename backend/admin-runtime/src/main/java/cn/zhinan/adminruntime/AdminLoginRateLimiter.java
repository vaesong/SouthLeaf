package cn.zhinan.adminruntime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded, single-process login attempt budget keyed by configured origin and exact account ID. */
final class AdminLoginRateLimiter {
  private static final int MAX_KEYS = 2048;
  private static final int MAX_ATTEMPTS = 5;
  private static final Duration WINDOW = Duration.ofMinutes(5);

  private final Clock clock;
  private final Map<String, Window> windows = new LinkedHashMap<>();

  AdminLoginRateLimiter(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
  }

  synchronized Decision tryAcquire(String origin, String accountId) {
    Objects.requireNonNull(origin);
    Objects.requireNonNull(accountId);
    Instant now = clock.instant();
    windows.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().endsAt));

    String key = origin.length() + ":" + origin + accountId;
    Window window = windows.get(key);
    if (window == null) {
      if (windows.size() >= MAX_KEYS) return Decision.limited(60);
      window = new Window(now.plus(WINDOW));
      windows.put(key, window);
    }
    if (window.attempts >= MAX_ATTEMPTS) {
      long seconds = Math.max(1, Duration.between(now, window.endsAt).toSeconds());
      return Decision.limited(seconds);
    }
    window.attempts++;
    return Decision.permitted();
  }

  synchronized void recordSuccess(String origin, String accountId) {
    Objects.requireNonNull(origin);
    Objects.requireNonNull(accountId);
    windows.remove(origin.length() + ":" + origin + accountId);
  }

  record Decision(boolean allowed, long retryAfterSeconds) {
    static Decision permitted() {
      return new Decision(true, 0);
    }

    static Decision limited(long retryAfterSeconds) {
      return new Decision(false, Math.max(1, retryAfterSeconds));
    }
  }

  private static final class Window {
    private final Instant endsAt;
    private int attempts;

    private Window(Instant endsAt) {
      this.endsAt = endsAt;
    }
  }
}
