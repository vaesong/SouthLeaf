package cn.zhinan.adminbooking.adapter.jdbc;

import cn.zhinan.adminbooking.port.BookingPorts.TransactionRunner;
import java.util.function.Supplier;

/**
 * Runs a use case inside one database transaction. A business failure rolls the whole unit back, so
 * a command can never leave a confirmed reservation without its order.
 */
public final class JdbcTransactionRunner implements TransactionRunner {
  private final JdbcConnectionProvider connections;

  public JdbcTransactionRunner(JdbcConnectionProvider connections) {
    this.connections = connections;
  }

  @Override
  public <T> T inTransaction(Supplier<T> work) {
    connections.begin();
    try {
      T result = work.get();
      connections.commit();
      return result;
    } catch (RuntimeException failure) {
      connections.rollback();
      throw failure;
    }
  }

  @Override
  public void inTransaction(Runnable work) {
    inTransaction(
        () -> {
          work.run();
          return null;
        });
  }
}
