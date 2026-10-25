package cn.zhinan.adminruntime;

import cn.zhinan.adminsecurity.adapter.jdbc.JdbcAdminSessionStore;
import cn.zhinan.adminsecurity.adapter.jdbc.JdbcAuthenticationEventSink;
import cn.zhinan.adminsecurity.adapter.jdbc.JdbcCredentialLookup;
import cn.zhinan.adminsecurity.port.AdminSessionStore;
import cn.zhinan.adminsecurity.port.AuthenticationEventSink;
import cn.zhinan.adminsecurity.port.CredentialLookup;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/** Capability wrapper, not a DataSource Bean. No bare-pool accessor or public constructor. */
final class VerifiedAdminDataSource implements AutoCloseable {
  // Package-only seams for resource/metadata tests, never selected by environment or arguments.
  interface Pool extends AutoCloseable {
    DataSource source();

    @Override
    void close();
  }

  interface Factory {
    Pool create(AdminRuntimeProperties properties);
  }

  private final Pool pool;
  private final AdminSessionStore store;
  private final CredentialLookup credentials;
  private final AuthenticationEventSink events;
  private final AtomicBoolean closed = new AtomicBoolean();

  private VerifiedAdminDataSource(Pool pool, DataSource verifiedSource) {
    this.pool = pool;
    this.store = new JdbcAdminSessionStore(verifiedSource);
    this.credentials = new JdbcCredentialLookup(verifiedSource);
    this.events = new JdbcAuthenticationEventSink(verifiedSource);
  }

  static VerifiedAdminDataSource open(AdminRuntimeProperties properties, Factory factory) {
    Pool pool = factory.create(properties);
    boolean accepted = false;
    try {
      DataSource source = pool.source();
      try (var connection = source.getConnection()) {
        AdminDatabaseVerifier.verify(connection, properties);
      } catch (SQLException ex) {
        throw new AdminStartupException(AdminStartupException.Reason.DATABASE_CHECK);
      }
      var verified = new VerifiedAdminDataSource(pool, source);
      accepted = true;
      return verified;
    } finally {
      if (!accepted) pool.close();
    }
  }

  static Pool createPool(AdminRuntimeProperties p) {
    var source = new HikariDataSource();
    try {
      source.setPoolName("southleaf-admin-verified");
      source.setJdbcUrl(p.url());
      source.setUsername(p.username());
      source.setPassword(p.password());
      source.setMaximumPoolSize(4);
      source.setMinimumIdle(0);
      source.setConnectionTimeout(5000);
      source.setValidationTimeout(2000);
      source.setInitializationFailTimeout(-1);
      source.setRegisterMbeans(false);
      source.addDataSourceProperty("logger", "com.mysql.cj.log.Slf4JLogger");
      return new Pool() {
        public DataSource source() {
          return source;
        }

        public void close() {
          source.close();
        }
      };
    } catch (RuntimeException | Error ex) {
      source.close();
      throw ex;
    }
  }

  AdminSessionStore sessionStore() {
    return store;
  }

  CredentialLookup credentialLookup() {
    return credentials;
  }

  AuthenticationEventSink authenticationEvents() {
    return events;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) pool.close();
  }

  @Override
  public String toString() {
    return "VerifiedAdminDataSource[verified]";
  }
}
