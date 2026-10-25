package cn.zhinan.adminruntime;

import cn.zhinan.adminbooking.policy.AdminBookingGrantPolicy;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/** A separate, exact-table-grant pool for demo reservation reads and mutations. */
final class VerifiedBookingDataSource implements AutoCloseable {
  private static final Set<String> TABLE_PRIVILEGES =
      Set.of(
          "ALTER",
          "CREATE",
          "DELETE",
          "DROP",
          "INDEX",
          "INSERT",
          "REFERENCES",
          "SELECT",
          "TRIGGER",
          "UPDATE");
  private static final java.util.regex.Pattern GRANT =
      java.util.regex.Pattern.compile(
          "GRANT (.+) ON (\\*|`[A-Za-z0-9_]+`)\\.(\\*|`[A-Za-z0-9_]+`) TO `([A-Za-z][A-Za-z0-9_]{0,31})`@`([A-Za-z0-9._%:/-]{1,255})`");

  private final HikariDataSource pool;
  private final AtomicBoolean closed = new AtomicBoolean();

  private VerifiedBookingDataSource(HikariDataSource pool) {
    this.pool = pool;
  }

  static VerifiedBookingDataSource open(
      AdminRuntimeProperties runtime, AdminBookingProperties booking) {
    if (!booking.enabled())
      throw new AdminStartupException(AdminStartupException.Reason.CONFIGURATION);
    HikariDataSource pool = null;
    boolean accepted = false;
    try {
      pool = new HikariDataSource();
      pool.setPoolName("southleaf-admin-booking");
      pool.setJdbcUrl(runtime.url());
      pool.setUsername(booking.databaseUser());
      pool.setPassword(booking.databasePassword());
      pool.setMaximumPoolSize(4);
      pool.setMinimumIdle(0);
      pool.setConnectionTimeout(5000);
      pool.setValidationTimeout(2000);
      pool.setInitializationFailTimeout(-1);
      pool.setRegisterMbeans(false);
      pool.setAutoCommit(true);
      pool.addDataSourceProperty("logger", "com.mysql.cj.log.Slf4JLogger");
      verify(pool, runtime.schema(), booking.expectedUser());
      var verified = new VerifiedBookingDataSource(pool);
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
          if (grants.size() >= 64) refuse(AdminStartupException.Reason.GRANTS);
          String grant = rows.getString(1);
          if (grant == null || grant.length() > 4096) refuse(AdminStartupException.Reason.GRANTS);
          grants.add(grant);
        }
      }
      try {
        validateGrants(schema, expectedUser, grants);
      } catch (IllegalArgumentException rejected) {
        refuse(AdminStartupException.Reason.GRANTS);
      }
      for (String table : AdminBookingGrantPolicy.OWNED_TABLES) {
        try (var rows = statement.executeQuery("SELECT 1 FROM `" + table + "` WHERE 1=0")) {
          if (rows.next()) refuse(AdminStartupException.Reason.DATABASE_CHECK);
        }
      }
    }
  }

  static void validateGrants(String schema, String expectedUser, List<String> grants) {
    if (schema == null
        || !schema.matches("[a-z][a-z0-9_]{0,63}")
        || expectedUser == null
        || !expectedUser.matches("[A-Za-z][A-Za-z0-9_]{0,31}@[A-Za-z0-9._%:/-]{1,255}")
        || grants == null
        || grants.isEmpty()
        || grants.size() > 64) fail();
    var required = new HashSet<String>();
    for (String table : AdminBookingGrantPolicy.OWNED_TABLES)
      for (String privilege : TABLE_PRIVILEGES)
        required.add(schema + ":" + table + ":" + privilege);

    var found = new HashSet<String>();
    boolean usage = false;
    for (String line : grants) {
      if (line == null || line.length() > 4096) fail();
      var match = GRANT.matcher(line);
      if (!match.matches() || !(match.group(4) + "@" + match.group(5)).equals(expectedUser)) fail();
      String database = match.group(2);
      String table = match.group(3);
      List<String> granted = parsePrivileges(match.group(1));
      if (database.equals("*") && table.equals("*") && granted.equals(List.of("USAGE"))) {
        if (usage) fail();
        usage = true;
        continue;
      }
      if (!database.equals("`" + schema + "`") || table.equals("*")) fail();
      String name = table.substring(1, table.length() - 1);
      if (!AdminBookingGrantPolicy.OWNED_TABLES.contains(name)) fail();
      Set<String> privileges =
          granted.size() == 1
                  && (granted.getFirst().equals("ALL")
                      || granted.getFirst().equals("ALL PRIVILEGES"))
              ? TABLE_PRIVILEGES
              : Set.copyOf(granted);
      if (!TABLE_PRIVILEGES.containsAll(privileges) || privileges.isEmpty()) fail();
      for (String privilege : privileges)
        if (!found.add(schema + ":" + name + ":" + privilege)) fail();
    }
    if (!usage || !found.equals(required)) fail();
  }

  private static List<String> parsePrivileges(String value) {
    if (value == null || value.isBlank()) fail();
    String[] parts = value.toUpperCase(Locale.ROOT).split(", ", -1);
    var result = new ArrayList<String>(parts.length);
    for (String part : parts) {
      if (!part.matches("[A-Z ]{1,40}") || !part.equals(part.trim())) fail();
      result.add(part);
    }
    return List.copyOf(result);
  }

  private static void fail() {
    throw new IllegalArgumentException("Booking writer grants rejected");
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
    return "VerifiedBookingDataSource[verified]";
  }
}
