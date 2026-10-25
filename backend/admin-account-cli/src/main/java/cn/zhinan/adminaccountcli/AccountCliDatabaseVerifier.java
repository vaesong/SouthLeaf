package cn.zhinan.adminaccountcli;

import cn.zhinan.admindbpolicy.AdminAccountCliGrantPolicy;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Opens the separate account-CLI pool only after proving identity and its exact direct grants. */
final class AccountCliDatabaseVerifier {
  private AccountCliDatabaseVerifier() {}

  static VerifiedDatabase open(AccountCliProperties properties) {
    Objects.requireNonNull(properties);
    HikariDataSource dataSource = null;
    try {
      HikariConfig config = new HikariConfig();
      config.setJdbcUrl(properties.url());
      config.setUsername(properties.username());
      config.setPassword(properties.password());
      config.setMaximumPoolSize(1);
      config.setMinimumIdle(0);
      config.setConnectionTimeout(5_000);
      config.setValidationTimeout(5_000);
      config.setInitializationFailTimeout(5_000);
      config.setPoolName("southleaf-account-cli-verified");
      dataSource = new HikariDataSource(config);
      try (Connection connection = dataSource.getConnection()) {
        return new VerifiedDatabase(dataSource, verify(connection, properties));
      }
    } catch (AccountCliException ex) {
      if (dataSource != null) dataSource.close();
      throw ex;
    } catch (SQLException | RuntimeException ex) {
      if (dataSource != null) dataSource.close();
      reject();
      throw new AssertionError(ex);
    }
  }

  static String verify(Connection connection, AccountCliProperties properties) throws SQLException {
    Objects.requireNonNull(connection);
    Objects.requireNonNull(properties);
    String schema = scalar(connection, "SELECT DATABASE()");
    if (!properties.schema().equals(schema)) reject();
    String currentUser = currentUser(connection);
    if (!properties.expectedUser().equals(currentUser)) reject();
    if (!"NONE".equals(scalar(connection, "SELECT CURRENT_ROLE()"))) reject();
    if (!"".equals(scalar(connection, "SELECT @@GLOBAL.mandatory_roles"))) reject();
    try {
      AdminAccountCliGrantPolicy.validate(properties.schema(), currentUser, grants(connection));
    } catch (IllegalArgumentException ex) {
      reject();
    }
    return currentUser;
  }

  private static String currentUser(Connection connection) throws SQLException {
    String user = scalar(connection, "SELECT CURRENT_USER()");
    if (user == null || user.length() > 288 || user.isBlank()) reject();
    return user;
  }

  private static String scalar(Connection connection, String query) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(query)) {
      if (!rows.next()) reject();
      String value = rows.getString(1);
      if (rows.next()) reject();
      return value;
    }
  }

  private static List<String> grants(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SHOW GRANTS FOR CURRENT_USER()")) {
      List<String> grants = new ArrayList<>();
      while (rows.next()) grants.add(rows.getString(1));
      return List.copyOf(grants);
    }
  }

  private static void reject() {
    throw new AccountCliException(AccountCliException.Reason.DATABASE_CHECK);
  }

  record VerifiedDatabase(HikariDataSource dataSource, String executionIdentity)
      implements AutoCloseable {
    VerifiedDatabase {
      Objects.requireNonNull(dataSource);
      Objects.requireNonNull(executionIdentity);
    }

    @Override
    public void close() {
      dataSource.close();
    }
  }
}
