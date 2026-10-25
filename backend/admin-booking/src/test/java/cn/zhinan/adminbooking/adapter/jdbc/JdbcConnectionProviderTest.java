package cn.zhinan.adminbooking.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.BookingFailure;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Connection lifecycle and dialect failure mapping of the JDBC boundary. */
class JdbcConnectionProviderTest {
  private static final class ConnectionFixture {
    private final AtomicInteger opened = new AtomicInteger();
    private final AtomicInteger closed = new AtomicInteger();
    private final AtomicInteger committed = new AtomicInteger();
    private final AtomicInteger rolledBack = new AtomicInteger();

    private DataSource dataSource() {
      return (DataSource)
          Proxy.newProxyInstance(
              DataSource.class.getClassLoader(),
              new Class<?>[] {DataSource.class},
              (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) {
                  opened.incrementAndGet();
                  return connection();
                }
                return defaultValue(method.getReturnType());
              });
    }

    private Connection connection() {
      InvocationHandler handler =
          (proxy, method, args) -> {
            switch (method.getName()) {
              case "prepareStatement":
                return statement();
              case "close":
                closed.incrementAndGet();
                return null;
              case "commit":
                committed.incrementAndGet();
                return null;
              case "rollback":
                rolledBack.incrementAndGet();
                return null;
              case "isClosed":
                return false;
              default:
                return defaultValue(method.getReturnType());
            }
          };
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
    }

    private PreparedStatement statement() {
      InvocationHandler handler =
          (proxy, method, args) -> {
            if (method.getName().equals("executeUpdate")) return 1;
            return defaultValue(method.getReturnType());
          };
      return (PreparedStatement)
          Proxy.newProxyInstance(
              PreparedStatement.class.getClassLoader(),
              new Class<?>[] {PreparedStatement.class},
              handler);
    }
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive() || type == void.class) return null;
    if (type == boolean.class) return false;
    if (type == byte.class) return (byte) 0;
    if (type == short.class) return (short) 0;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    if (type == float.class) return 0.0f;
    if (type == double.class) return 0.0d;
    if (type == char.class) return '\0';
    return null;
  }

  @Test
  void aStandaloneStatementReleasesItsConnection() {
    ConnectionFixture fixture = new ConnectionFixture();
    JdbcConnectionProvider connections = new JdbcConnectionProvider(fixture.dataSource());

    int changed = JdbcSupport.update(connections, "UPDATE any_table SET value = ?", "value");

    assertThat(changed).isEqualTo(1);
    assertThat(fixture.opened).hasValue(1);
    assertThat(fixture.closed).hasValue(1);
  }

  @Test
  void statementsInsideATransactionShareOneConnectionUntilCommit() {
    ConnectionFixture fixture = new ConnectionFixture();
    JdbcConnectionProvider connections = new JdbcConnectionProvider(fixture.dataSource());
    connections.begin();

    JdbcSupport.update(connections, "UPDATE any_table SET value = ?", "value");
    JdbcSupport.update(connections, "UPDATE any_table SET value = ?", "value");

    assertThat(fixture.opened).hasValue(1);
    assertThat(fixture.closed).hasValue(0);
    assertThat(connections.transactionOpen()).isTrue();

    connections.commit();

    assertThat(fixture.committed).hasValue(1);
    assertThat(fixture.closed).hasValue(1);
    assertThat(connections.transactionOpen()).isFalse();
  }

  @Test
  void aRollbackReleasesTheTransactionConnection() {
    ConnectionFixture fixture = new ConnectionFixture();
    JdbcConnectionProvider connections = new JdbcConnectionProvider(fixture.dataSource());
    connections.begin();
    JdbcSupport.update(connections, "UPDATE any_table SET value = ?", "value");

    connections.rollback();

    assertThat(fixture.rolledBack).hasValue(1);
    assertThat(fixture.closed).hasValue(1);
    assertThat(connections.transactionOpen()).isFalse();
  }

  @Test
  void innodbConcurrentWriteCodesAreMappedToAConflictNotAStorageFailure() {
    BookingException changedRecord =
        JdbcSupport.translate(new SQLException("", "HY000", 1020), null);
    BookingException deadlock = JdbcSupport.translate(new SQLException("", "40001", 1213), null);
    BookingException lockTimeout = JdbcSupport.translate(new SQLException("", "HY000", 1205), null);

    assertThat(changedRecord.failure()).isEqualTo(BookingFailure.VERSION_CONFLICT);
    assertThat(deadlock.failure()).isEqualTo(BookingFailure.VERSION_CONFLICT);
    assertThat(lockTimeout.failure()).isEqualTo(BookingFailure.VERSION_CONFLICT);
  }

  @Test
  void aUniqueConflictUsesTheRepositoryDeclaredReason() {
    BookingException conflict =
        JdbcSupport.translate(
            new SQLException("driver message must not be parsed", "23000", 1062),
            "SLOT_OCCUPANCY_CONFLICT");

    assertThat(conflict.failure()).isEqualTo(BookingFailure.CONFLICT);
    assertThat(conflict.reason()).isEqualTo("SLOT_OCCUPANCY_CONFLICT");
  }
}
