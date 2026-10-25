package cn.zhinan.adminruntime;

import cn.zhinan.admindbpolicy.AdminContentGrantPolicy;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/**
 * Two separately verified pools: the business writer (management draft/media facts) and the
 * publication writer (cross-schema publish/withdraw). Neither is exposed as a DataSource Bean.
 */
final class VerifiedContentDataSources implements AutoCloseable {
  private final HikariDataSource business;
  private final HikariDataSource publication;
  private final AtomicBoolean closed = new AtomicBoolean();

  private VerifiedContentDataSources(HikariDataSource business, HikariDataSource publication) {
    this.business = business;
    this.publication = publication;
  }

  static VerifiedContentDataSources open(AdminRuntimeProperties properties) {
    var content = properties.content();
    if (!content.enabled())
      throw new AdminStartupException(AdminStartupException.Reason.CONFIGURATION);
    HikariDataSource business = null;
    HikariDataSource publication = null;
    boolean accepted = false;
    try {
      business =
          pool(
              "southleaf-admin-content-business",
              properties.url(),
              content.businessUser(),
              content.businessPassword());
      publication =
          pool(
              "southleaf-admin-content-publication",
              properties.url(),
              content.publicationUser(),
              content.publicationPassword());
      try (var connection = business.getConnection()) {
        verify(
            connection,
            AdminContentGrantPolicy.Identity.BUSINESS_WRITER,
            properties.schema(),
            null,
            content.businessExpected());
        probe(connection, "SELECT COUNT(*) FROM admin_work_case WHERE 1=0", 0);
      }
      try (var connection = publication.getConnection()) {
        verify(
            connection,
            AdminContentGrantPolicy.Identity.PUBLICATION_WRITER,
            properties.schema(),
            content.publicSchema(),
            content.publicationExpected());
        probe(
            connection,
            "SELECT COUNT(*) FROM `"
                + content.publicSchema()
                + "`.public_work_projection_epoch WHERE singleton_id=1",
            1);
      }
      var verified = new VerifiedContentDataSources(business, publication);
      accepted = true;
      return verified;
    } catch (SQLException ex) {
      throw new AdminStartupException(AdminStartupException.Reason.DATABASE_CHECK);
    } finally {
      if (!accepted) {
        if (business != null) business.close();
        if (publication != null) publication.close();
      }
    }
  }

  private static HikariDataSource pool(String name, String url, String user, String password) {
    var source = new HikariDataSource();
    try {
      source.setPoolName(name);
      source.setJdbcUrl(url);
      source.setUsername(user);
      source.setPassword(password);
      source.setMaximumPoolSize(4);
      source.setMinimumIdle(0);
      source.setConnectionTimeout(5000);
      source.setValidationTimeout(2000);
      source.setInitializationFailTimeout(-1);
      source.setRegisterMbeans(false);
      source.setAutoCommit(true);
      source.addDataSourceProperty("logger", "com.mysql.cj.log.Slf4JLogger");
      return source;
    } catch (RuntimeException | Error ex) {
      source.close();
      throw ex;
    }
  }

  private static void verify(
      Connection connection,
      AdminContentGrantPolicy.Identity identity,
      String adminSchema,
      String publicSchema,
      String expectedUser)
      throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      try (var rows = statement.executeQuery(AdminDatabaseVerifier.IDENTITY)) {
        if (!rows.next()) refuse(AdminStartupException.Reason.IDENTITY);
        String schema = cell(rows, 1);
        String user = cell(rows, 2);
        String role = cell(rows, 3);
        String mandatory = cell(rows, 4);
        if (!adminSchema.equals(schema) || !expectedUser.equals(user) || rows.next())
          refuse(AdminStartupException.Reason.IDENTITY);
        if (!"NONE".equals(role) || mandatory == null || !mandatory.isBlank())
          refuse(AdminStartupException.Reason.ROLES);
      }
      try (var rows = statement.executeQuery(AdminDatabaseVerifier.TLS)) {
        if (!rows.next() || !"Ssl_cipher".equalsIgnoreCase(cell(rows, 1)))
          refuse(AdminStartupException.Reason.TLS);
        String cipher = cell(rows, 2);
        if (cipher == null || cipher.isBlank() || rows.next())
          refuse(AdminStartupException.Reason.TLS);
      }
      var grants = new ArrayList<String>();
      try (var rows = statement.executeQuery(AdminDatabaseVerifier.GRANTS)) {
        while (rows.next()) {
          if (grants.size() >= 64) refuse(AdminStartupException.Reason.GRANTS);
          grants.add(cell(rows, 1));
        }
      }
      try {
        AdminContentGrantPolicy.validate(identity, adminSchema, publicSchema, expectedUser, grants);
      } catch (IllegalArgumentException ex) {
        refuse(AdminStartupException.Reason.GRANTS);
      }
    }
  }

  private static void probe(Connection connection, String sql, long expected) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      try (var rows = statement.executeQuery(sql)) {
        if (!rows.next() || rows.getLong(1) != expected || rows.next())
          refuse(AdminStartupException.Reason.DATABASE_CHECK);
      }
    }
  }

  private static String cell(ResultSet rows, int column) throws SQLException {
    String value = rows.getString(column);
    if (value != null && value.length() > 4096) refuse(AdminStartupException.Reason.DATABASE_CHECK);
    return value;
  }

  private static void refuse(AdminStartupException.Reason reason) {
    throw new AdminStartupException(reason);
  }

  DataSource business() {
    return business;
  }

  DataSource publication() {
    return publication;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      try {
        business.close();
      } finally {
        publication.close();
      }
    }
  }

  @Override
  public String toString() {
    return "VerifiedContentDataSources[verified]";
  }
}
