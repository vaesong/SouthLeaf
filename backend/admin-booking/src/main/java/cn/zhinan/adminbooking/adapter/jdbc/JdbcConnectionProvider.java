package cn.zhinan.adminbooking.adapter.jdbc;

import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.BookingFailure;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Owns the transactional boundary for the JDBC adapters. A connection is bound to the current
 * thread while a transaction is open, so every repository in one use case participates in the same
 * commit. The provider never leaks the raw {@link DataSource} to callers outside this package.
 *
 * <p>Outside a transaction the connection is still bound to the thread, so that the reads of one
 * use case see the same snapshot and the connection is released exactly once. A connection that is
 * opened per statement would exhaust the server under concurrency.
 */
public final class JdbcConnectionProvider {
  private final DataSource dataSource;
  private final ThreadLocal<Connection> bound = new ThreadLocal<>();
  private final ThreadLocal<Boolean> inTransaction = new ThreadLocal<>();

  public JdbcConnectionProvider(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /** Whether the current thread holds an open transaction. */
  public boolean transactionOpen() {
    return Boolean.TRUE.equals(inTransaction.get());
  }

  /** The connection of the current transaction, or the bound connection of the current thread. */
  public Connection current() {
    Connection connection = bound.get();
    if (connection != null) return connection;
    try {
      connection = dataSource.getConnection();
    } catch (SQLException failure) {
      throw new BookingException(BookingFailure.STORAGE_UNAVAILABLE, "CONNECTION_FAILED");
    }
    bound.set(connection);
    return connection;
  }

  public void begin() {
    Connection connection = bound.get();
    try {
      if (connection == null) {
        connection = dataSource.getConnection();
        bound.set(connection);
      }
      connection.setAutoCommit(false);
      inTransaction.set(Boolean.TRUE);
    } catch (SQLException failure) {
      closeAndClear();
      throw new BookingException(BookingFailure.STORAGE_UNAVAILABLE, "TRANSACTION_BEGIN_FAILED");
    }
  }

  public void commit() {
    Connection connection = bound.get();
    if (connection == null) return;
    try {
      connection.commit();
    } catch (SQLException failure) {
      throw new BookingException(BookingFailure.STORAGE_UNAVAILABLE, "COMMIT_FAILED");
    } finally {
      closeAndClear();
    }
  }

  public void rollback() {
    Connection connection = bound.get();
    if (connection == null) return;
    try {
      connection.rollback();
    } catch (SQLException suppressed) {
      // The original failure is the one worth reporting; the rollback error stays internal.
    } finally {
      closeAndClear();
    }
  }

  /**
   * Releases the connection of the current thread when no transaction is open. A transactional use
   * case releases its own connection in commit or rollback; this method covers the read only paths.
   */
  public void releaseIfIdle() {
    if (transactionOpen()) return;
    if (bound.get() == null) return;
    closeAndClear();
  }

  private void closeAndClear() {
    Connection connection = bound.get();
    bound.remove();
    inTransaction.remove();
    if (connection == null) return;
    try {
      connection.setAutoCommit(true);
    } catch (SQLException suppressed) {
      // A connection that cannot be reset is closed anyway.
    }
    try {
      connection.close();
    } catch (SQLException suppressed) {
      // Closing failures must not mask the business outcome.
    }
  }
}
