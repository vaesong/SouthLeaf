package cn.zhinan.admincredentialcrypto;

import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Explicit synthetic, test-classpath benchmark; never an account tool or production entry point.
 */
public final class CredentialKdfBenchmark {
  private CredentialKdfBenchmark() {}

  public static void main(String[] args) throws Exception {
    var adapter = new Argon2PasswordService();
    char[] raw = "synthetic benchmark only 👩‍💻".toCharArray();
    String hash = adapter.hash(raw);
    for (int i = 0; i < 3; i++) measure(adapter, raw, hash);
    List<Double> serial = new ArrayList<>();
    List<Double> concurrent = new ArrayList<>();
    AtomicBoolean sampling = new AtomicBoolean(true);
    AtomicLong peak = new AtomicLong();
    Thread sampler =
        Thread.ofPlatform()
            .daemon()
            .start(
                () -> {
                  while (sampling.get()) {
                    peak.accumulateAndGet(
                        ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                        Math::max);
                    try {
                      Thread.sleep(5);
                    } catch (InterruptedException ex) {
                      Thread.currentThread().interrupt();
                      break;
                    }
                  }
                });
    try {
      for (int i = 0; i < 8; i++) serial.add(measure(adapter, raw, hash));
      try (var pool = Executors.newFixedThreadPool(2)) {
        for (int i = 0; i < 4; i++) {
          var start = new CountDownLatch(1);
          Callable<Double> work =
              () -> {
                start.await();
                return measure(new Argon2PasswordService(), raw, hash);
              };
          var a = pool.submit(work);
          var b = pool.submit(work);
          start.countDown();
          concurrent.add(a.get(20, TimeUnit.SECONDS));
          concurrent.add(b.get(20, TimeUnit.SECONDS));
        }
      }
    } finally {
      sampling.set(false);
      sampler.join(1000);
      Arrays.fill(raw, '\0');
    }
    System.out.printf(
        Locale.ROOT,
        "{\"profile\":\"argon2id-v19-m65536-t3-p1\",\"warmup\":3,\"samplesPerMode\":8,\"maxConcurrent\":2,\"javaVersion\":\"%s\",\"availableProcessors\":%d,\"maxHeapBytes\":%d,\"sampledPeakHeapBytes\":%d,\"serialP50Ms\":%.3f,\"serialMaxMs\":%.3f,\"concurrentP50Ms\":%.3f,\"concurrentMaxMs\":%.3f}%n",
        System.getProperty("java.version"),
        Runtime.getRuntime().availableProcessors(),
        Runtime.getRuntime().maxMemory(),
        peak.get(),
        median(serial),
        Collections.max(serial),
        median(concurrent),
        Collections.max(concurrent));
  }

  private static double measure(Argon2PasswordService adapter, char[] raw, String hash) {
    long started = System.nanoTime();
    if (!adapter.verify(raw, hash).matches())
      throw new IllegalStateException("Synthetic benchmark mismatch");
    return (System.nanoTime() - started) / 1_000_000.0;
  }

  private static double median(List<Double> values) {
    var sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    return (sorted.get(3) + sorted.get(4)) / 2;
  }
}
