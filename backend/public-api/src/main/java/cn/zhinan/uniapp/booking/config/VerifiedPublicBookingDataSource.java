package cn.zhinan.uniapp.booking.config;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * A separately pooled, exactly granted intake identity. Startup verifies the database user, the TLS
 * cipher and every table privilege, so a widened grant is a startup failure rather than a silent
 * capability the customer entry must not have.
 */
final class VerifiedPublicBookingDataSource implements AutoCloseable {
  private static final Pattern GRANT =
      Pattern.compile(
          "GRANT (.+?) ON (`[A-Za-z0-9_]+`|\\*)\\.(`[A-Za-z0-9_]+`|\\*) TO `([A-Za-z][A-Za-z0-9_]{0,31})`@`([A-Za-z0-9._%:/-]{1,255})`");

  private final HikariDataSource pool;
  private final AtomicBoolean closed = new AtomicBoolean();

  private VerifiedPublicBookingDataSource(HikariDataSource pool) {
    this.pool = pool;
  }

  static VerifiedPublicBookingDataSource open(PublicBookingProperties properties) {
    HikariDataSource pool = null;
    boolean accepted = false;
    try {
      pool = new HikariDataSource();
      pool.setPoolName("southleaf-public-booking");
      pool.setJdbcUrl(properties.databaseUrl());
      pool.setUsername(properties.databaseUser());
      pool.setPassword(properties.databasePassword());
      pool.setMaximumPoolSize(4);
      pool.setMinimumIdle(0);
      pool.setConnectionTimeout(5000);
      pool.setValidationTimeout(2000);
      pool.setInitializationFailTimeout(-1);
      pool.setRegisterMbeans(false);
      pool.setAutoCommit(true);
      pool.addDataSourceProperty("logger", "com.mysql.cj.log.Slf4JLogger");
      verify(pool, properties.expectedUser());
      var verified = new VerifiedPublicBookingDataSource(pool);
      accepted = true;
      return verified;
    } catch (SQLException failure) {
      throw new PublicBookingStartupException(PublicBookingStartupException.Reason.DATABASE);
    } catch (RuntimeException | Error failure) {
      if (failure instanceof PublicBookingStartupException startup) throw startup;
      throw new PublicBookingStartupException(PublicBookingStartupException.Reason.DATABASE);
    } finally {
      if (!accepted && pool != null) pool.close();
    }
  }

  private static void verify(DataSource source, String expectedUser) throws SQLException {
    try (Connection connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      String schema;
      try (var rows = statement.executeQuery("SELECT DATABASE(), CURRENT_USER()")) {
        if (!rows.next()) refuse(PublicBookingStartupException.Reason.IDENTITY);
        schema = rows.getString(1);
        if (!expectedUser.equals(rows.getString(2)))
          refuse(PublicBookingStartupException.Reason.IDENTITY);
      }
      if (schema == null || !schema.matches("[a-z][a-z0-9_]{0,63}"))
        refuse(PublicBookingStartupException.Reason.IDENTITY);
      try (var rows = statement.executeQuery("SHOW STATUS LIKE 'Ssl_cipher'")) {
        if (!rows.next() || rows.getString(2) == null || rows.getString(2).isBlank())
          refuse(PublicBookingStartupException.Reason.TLS);
      }
      var grants = new ArrayList<String>();
      try (var rows = statement.executeQuery("SHOW GRANTS FOR CURRENT_USER()")) {
        while (rows.next()) {
          String line = rows.getString(1);
          if (line == null || line.length() > 4096 || grants.size() >= 64)
            refuse(PublicBookingStartupException.Reason.GRANTS);
          grants.add(line);
        }
      }
      try {
        validateGrants(schema, expectedUser, grants);
      } catch (IllegalArgumentException rejected) {
        refuse(PublicBookingStartupException.Reason.GRANTS);
      }
    }
  }

  static void validateGrants(String schema, String expectedUser, List<String> grants) {
    if (schema == null
        || !schema.matches("[a-z][a-z0-9_]{0,63}")
        || expectedUser == null
        || grants == null
        || grants.isEmpty()) throw new IllegalArgumentException("Booking intake grants rejected");
    Set<String> required = new HashSet<>();
    for (var entry : PublicBookingGrantPolicy.GRANTS.entrySet())
      for (String privilege : entry.getValue()) required.add(entry.getKey() + ":" + privilege);
    Set<String> found = new HashSet<>();
    boolean usage = false;
    for (String line : grants) {
      Matcher match = GRANT.matcher(line);
      if (!match.matches()) throw new IllegalArgumentException("Unparseable grant");
      if (!(match.group(4) + "@" + match.group(5)).equals(expectedUser))
        throw new IllegalArgumentException("Grant owner mismatch");
      boolean global = match.group(2).equals("*") && match.group(3).equals("*");
      if (global) {
        if (usage || !match.group(1).strip().equals("USAGE"))
          throw new IllegalArgumentException("Global grant must be USAGE only");
        usage = true;
        continue;
      }
      if (!match.group(2).equals("`" + schema + "`"))
        throw new IllegalArgumentException("Grant outside the booking schema");
      String table = match.group(3).replace("`", "");
      List<String> allowed = PublicBookingGrantPolicy.GRANTS.get(table);
      if (allowed == null) throw new IllegalArgumentException("Unexpected booking table grant");
      List<String> granted = parsePrivileges(match.group(1));
      if (granted.size() != allowed.size() || !allowed.containsAll(granted))
        throw new IllegalArgumentException("Privilege set mismatch on " + table);
      for (String privilege : granted)
        if (!found.add(table + ":" + privilege))
          throw new IllegalArgumentException("Duplicate grant on " + table);
    }
    if (!usage || !found.equals(required))
      throw new IllegalArgumentException("Booking intake grant set incomplete");
  }

  private static List<String> parsePrivileges(String value) {
    List<String> result = new ArrayList<>();
    for (String part : value.toUpperCase(Locale.ROOT).split(",", -1)) {
      String privilege = part.strip();
      if (!privilege.matches("[A-Z ]{3,40}")) throw new IllegalArgumentException("Privilege text");
      result.add(privilege);
    }
    return List.copyOf(result);
  }

  private static void refuse(PublicBookingStartupException.Reason reason) {
    throw new PublicBookingStartupException(reason);
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
    return "VerifiedPublicBookingDataSource[verified]";
  }
}
