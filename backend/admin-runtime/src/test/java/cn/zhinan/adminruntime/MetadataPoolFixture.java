package cn.zhinan.adminruntime;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

final class MetadataPoolFixture implements VerifiedAdminDataSource.Pool {
  final Map<String, List<String[]>> rows = new LinkedHashMap<>();
  final List<String> queries = new ArrayList<>();
  int opened, connectionsClosed, statementsClosed, resultSetsClosed, poolsClosed, queryTimeout;
  String failOn;
  final DataSource dataSource =
      new AbstractDataSource() {
        @Override
        public Connection getConnection() throws SQLException {
          return connection();
        }

        @Override
        public Connection getConnection(String u, String p) {
          throw new UnsupportedOperationException("Credential override forbidden");
        }
      };

  MetadataPoolFixture() {
    rows.put(
        AdminDatabaseVerifier.IDENTITY,
        new ArrayList<>(
            List.<String[]>of(
                new String[] {"southleaf_admin_it", "fixture_writer@localhost", "NONE", ""})));
    rows.put(
        AdminDatabaseVerifier.TLS,
        new ArrayList<>(List.<String[]>of(new String[] {"Ssl_cipher", "TLS_AES_256_GCM_SHA384"})));
    rows.put(
        AdminDatabaseVerifier.AUTH_EVENT_SHAPE,
        new ArrayList<>(
            List.of(
                new String[] {"event_id", "char(36)", "NO"},
                new String[] {"occurred_at", "datetime(6)", "NO"},
                new String[] {"event_type", "varchar(40)", "NO"},
                new String[] {"actor_account_id", "varchar(64)", "YES"},
                new String[] {"request_id", "char(36)", "NO"})));
    String to = " TO `fixture_writer`@`localhost`";
    rows.put(
        AdminDatabaseVerifier.GRANTS,
        new ArrayList<>(
            List.of(
                new String[] {"GRANT USAGE ON *.*" + to},
                new String[] {"GRANT SELECT ON `southleaf_admin_it`.`admin_account`" + to},
                new String[] {
                  "GRANT SELECT ON `southleaf_admin_it`.`admin_account_permission`" + to
                },
                new String[] {
                  "GRANT SELECT, INSERT, UPDATE ON `southleaf_admin_it`.`admin_session`" + to
                },
                new String[] {"GRANT INSERT ON `southleaf_admin_it`.`admin_security_audit`" + to},
                new String[] {"GRANT INSERT ON `southleaf_admin_it`.`admin_auth_event`" + to})));
  }

  public DataSource source() {
    return dataSource;
  }

  public void close() {
    poolsClosed++;
  }

  private Connection connection() throws SQLException {
    if ("connection".equals(failOn))
      throw new SQLException("FAKE_SECRET_SENTINEL: raw server detail");
    opened++;
    return proxy(
        Connection.class,
        (p, m, args) ->
            switch (m.getName()) {
              case "createStatement" -> statement();
              case "close" -> {
                connectionsClosed++;
                yield null;
              }
              default -> throw new UnsupportedOperationException(m.getName());
            });
  }

  private Statement statement() {
    return proxy(
        Statement.class,
        (p, m, args) ->
            switch (m.getName()) {
              case "setQueryTimeout" -> {
                queryTimeout = (Integer) args[0];
                yield null;
              }
              case "executeQuery" -> {
                String sql = (String) args[0];
                queries.add(sql);
                if (sql.equals(failOn))
                  throw new SQLException("FAKE_SECRET_SENTINEL: raw SQL detail");
                if (!rows.containsKey(sql)) throw new AssertionError("Unexpected SQL");
                yield result(rows.get(sql));
              }
              case "close" -> {
                statementsClosed++;
                yield null;
              }
              default -> throw new UnsupportedOperationException(m.getName());
            });
  }

  private ResultSet result(List<String[]> data) {
    int[] index = {-1};
    return proxy(
        ResultSet.class,
        (p, m, args) ->
            switch (m.getName()) {
              case "next" -> ++index[0] < data.size();
              case "getString" -> data.get(index[0])[(Integer) args[0] - 1];
              case "close" -> {
                resultSetsClosed++;
                yield null;
              }
              default -> throw new UnsupportedOperationException(m.getName());
            });
  }

  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
  }
}
