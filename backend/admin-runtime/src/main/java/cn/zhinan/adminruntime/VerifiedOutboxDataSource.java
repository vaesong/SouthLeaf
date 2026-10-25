package cn.zhinan.adminruntime;

import cn.zhinan.admindbpolicy.AdminOutboxGrantPolicy;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/** Dedicated, exact-grant database identity for dispatch and operator recovery only. */
final class VerifiedOutboxDataSource implements AutoCloseable {
  private final HikariDataSource pool;
  private final AtomicBoolean closed = new AtomicBoolean();

  private VerifiedOutboxDataSource(HikariDataSource pool) {
    this.pool = pool;
  }

  static VerifiedOutboxDataSource open(
      AdminRuntimeProperties runtime, AdminOutboxProperties outbox) {
    if (!outbox.enabled())
      throw new AdminStartupException(AdminStartupException.Reason.CONFIGURATION);
    HikariDataSource pool = null;
    boolean accepted = false;
    try {
      pool = new HikariDataSource();
      pool.setPoolName("southleaf-admin-outbox");
      pool.setJdbcUrl(runtime.url());
      pool.setUsername(outbox.databaseUser());
      pool.setPassword(outbox.databasePassword());
      pool.setMaximumPoolSize(3);
      pool.setMinimumIdle(0);
      pool.setConnectionTimeout(5000);
      pool.setValidationTimeout(2000);
      pool.setInitializationFailTimeout(-1);
      pool.setRegisterMbeans(false);
      pool.setAutoCommit(true);
      pool.addDataSourceProperty("logger", "com.mysql.cj.log.Slf4JLogger");
      verify(pool, runtime.schema(), outbox.expectedUser());
      VerifiedOutboxDataSource verified = new VerifiedOutboxDataSource(pool);
      accepted = true;
      return verified;
    } catch (SQLException failure) {
      throw new AdminStartupException(AdminStartupException.Reason.DATABASE_CHECK);
    } catch (RuntimeException | Error failure) {
      if (failure instanceof AdminStartupException startup) throw startup;
      throw new AdminStartupException(AdminStartupException.Reason.DATABASE_CHECK);
    } finally {
      if (!accepted && pool != null) pool.close();
    }
  }

  private static void verify(DataSource source, String schema, String expectedUser)
      throws SQLException {
    try (Connection connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      try (var rows = statement.executeQuery(AdminDatabaseVerifier.IDENTITY)) {
        if (!rows.next()
            || !schema.equals(rows.getString(1))
            || !expectedUser.equals(rows.getString(2))
            || !"NONE".equals(rows.getString(3))
            || rows.getString(4) == null
            || !rows.getString(4).isBlank()
            || rows.next()) refuse(AdminStartupException.Reason.IDENTITY);
      }
      try (var rows = statement.executeQuery(AdminDatabaseVerifier.TLS)) {
        if (!rows.next()
            || !"Ssl_cipher".equalsIgnoreCase(rows.getString(1))
            || rows.getString(2) == null
            || rows.getString(2).isBlank()
            || rows.next()) refuse(AdminStartupException.Reason.TLS);
      }
      var grants = new ArrayList<String>();
      try (var rows = statement.executeQuery(AdminDatabaseVerifier.GRANTS)) {
        while (rows.next()) {
          if (grants.size() >= 16) refuse(AdminStartupException.Reason.GRANTS);
          grants.add(rows.getString(1));
        }
      }
      try {
        AdminOutboxGrantPolicy.validate(schema, expectedUser, grants);
      } catch (IllegalArgumentException rejected) {
        throw new AdminStartupException(AdminStartupException.Reason.GRANTS);
      }
      try (var rows =
          statement.executeQuery(
              "SELECT id, intent, recipient_ref, object_ref, due_at, status, attempts, lease_until,"
                  + " lease_owner, last_error FROM booking_notification_outbox WHERE 1=0")) {
        if (rows.next()) refuse(AdminStartupException.Reason.DATABASE_CHECK);
      }
      try (var rows =
          statement.executeQuery(
              "SELECT actor_id, operation, idempotency_key, request_sha256, result_ref"
                  + " FROM booking_command WHERE 1=0")) {
        if (rows.next()) refuse(AdminStartupException.Reason.DATABASE_CHECK);
      }
    }
  }

  private static void refuse(AdminStartupException.Reason reason) {
    throw new AdminStartupException(reason);
  }

  DataSource dataSource() {
    return pool;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) pool.close();
  }

  @Override
  public String toString() {
    return "VerifiedOutboxDataSource[verified]";
  }
}
