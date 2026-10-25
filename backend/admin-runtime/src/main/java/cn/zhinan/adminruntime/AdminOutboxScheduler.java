package cn.zhinan.adminruntime;

import cn.zhinan.adminbooking.application.OutboxDispatcher;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** Periodic dispatcher, created only when the demo-only outbox capability is explicitly enabled. */
final class AdminOutboxScheduler implements AutoCloseable {
  private static final Logger LOG = Logger.getLogger(AdminOutboxScheduler.class.getName());
  private final OutboxDispatcher dispatcher;
  private final String workerId;
  private final int batchSize;
  private final long intervalMillis;
  private final Clock clock;
  private final AtomicBoolean started = new AtomicBoolean();
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            Thread thread = new Thread(task, "southleaf-outbox-dispatcher");
            thread.setDaemon(true);
            return thread;
          });

  AdminOutboxScheduler(
      OutboxDispatcher dispatcher,
      String workerId,
      int batchSize,
      long intervalMillis,
      Clock clock) {
    this.dispatcher = dispatcher;
    this.workerId = workerId;
    this.batchSize = batchSize;
    this.intervalMillis = intervalMillis;
    this.clock = clock;
  }

  void start() {
    if (!started.compareAndSet(false, true)) return;
    executor.scheduleWithFixedDelay(this::dispatchSafely, 0, intervalMillis, TimeUnit.MILLISECONDS);
  }

  private void dispatchSafely() {
    try {
      dispatcher.dispatch(workerId, batchSize, clock.instant());
    } catch (RuntimeException unavailable) {
      // Do not log provider exception text, message IDs, recipient refs or connection details.
      LOG.warning("OUTBOX_DISPATCH_PASS_FAILED");
    }
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
