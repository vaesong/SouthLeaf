package cn.zhinan.adminbooking.integration;

import cn.zhinan.adminbooking.adapter.jdbc.JdbcConnectionProvider;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcOutboxWorker;
import cn.zhinan.adminbooking.application.OutboxDispatcher;
import cn.zhinan.adminbooking.application.OutboxPolicy;
import cn.zhinan.adminbooking.port.BookingPorts.DeliveryOutcome;
import cn.zhinan.adminbooking.port.BookingPorts.OutboxMessage;
import cn.zhinan.adminbooking.port.BookingPorts.OutboxWorker;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;

/**
 * One outbox worker process. The parent test starts it twice: the first claims a message and exits
 * without reporting an outcome, and the second delivers that message after the lease time.
 *
 * <p>Stdout is a single fixed marker. Connection details and exception text stay off the stream.
 */
public final class OutboxLeaseProcess {
  private OutboxLeaseProcess() {}

  public static void main(String[] args) {
    int exit = 1;
    try {
      if (args.length != 3) throw new IllegalArgumentException("arguments");
      String url = required("SOUTHLEAF_BOOKING_IT_URL");
      String user = required("SOUTHLEAF_BOOKING_IT_USERNAME");
      String password = required("SOUTHLEAF_BOOKING_IT_PASSWORD");
      BookingItTarget.assertOwned(
          url, user, password, required("SOUTHLEAF_BOOKING_IT_INSTANCE_ID"));
      OutboxWorker worker =
          new JdbcOutboxWorker(new JdbcConnectionProvider(dataSource(url, user, password)));
      Instant now = Instant.parse(args[2]);
      if ("lease-and-exit".equals(args[0])) {
        List<OutboxMessage> leased = worker.lease("worker-a", 1, now, Duration.ofMinutes(2));
        if (leased.size() != 1 || !args[1].equals(leased.get(0).messageId())) {
          throw new IllegalStateException("lease");
        }
        System.out.println("OUTBOX_LEASED");
      } else if ("recover-and-deliver".equals(args[0])) {
        OutboxPolicy policy =
            new OutboxPolicy(
                5, Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofHours(1), 100);
        OutboxDispatcher.DispatchResult result =
            new OutboxDispatcher(worker, message -> DeliveryOutcome.success(), policy)
                .dispatch("worker-b", 1, now);
        if (result.delivered() != 1) throw new IllegalStateException("recover");
        System.out.println("OUTBOX_RECOVERED");
      } else {
        throw new IllegalArgumentException("command");
      }
      exit = 0;
    } catch (Exception ignored) {
      System.out.println("OUTBOX_PROCESS_FAILED");
    }
    System.exit(exit);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException("environment");
    return value;
  }

  private static DataSource dataSource(String url, String user, String password) {
    return new DataSource() {
      @Override
      public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
      }

      @Override
      public Connection getConnection(String username, String password) throws SQLException {
        return DriverManager.getConnection(url, username, password);
      }

      @Override
      public java.io.PrintWriter getLogWriter() {
        return null;
      }

      @Override
      public void setLogWriter(java.io.PrintWriter out) {}

      @Override
      public void setLoginTimeout(int seconds) {}

      @Override
      public int getLoginTimeout() {
        return 0;
      }

      @Override
      public java.util.logging.Logger getParentLogger() {
        return java.util.logging.Logger.getGlobal();
      }

      @Override
      public <T> T unwrap(Class<T> iface) {
        throw new UnsupportedOperationException();
      }

      @Override
      public boolean isWrapperFor(Class<?> iface) {
        return false;
      }
    };
  }
}
