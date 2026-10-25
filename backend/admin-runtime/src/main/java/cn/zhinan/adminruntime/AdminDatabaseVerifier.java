package cn.zhinan.adminruntime;

import cn.zhinan.admindbpolicy.AdminRuntimeGrantPolicy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;

/** Read-only startup inspection, not a permanent privilege monitor. No schema or data writes. */
final class AdminDatabaseVerifier {
  static final String IDENTITY =
      "SELECT DATABASE(), CURRENT_USER(), CURRENT_ROLE(), @@GLOBAL.mandatory_roles";
  static final String TLS = "SHOW SESSION STATUS LIKE 'Ssl_cipher'";
  static final String GRANTS = "SHOW GRANTS FOR CURRENT_USER()";

  static final String AUTH_EVENT_SHAPE =
      "SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='admin_auth_event' ORDER BY ORDINAL_POSITION";
  private static final String[][] AUTH_COLUMNS = {
    {"event_id", "char(36)", "NO"}, {"occurred_at", "datetime(6)", "NO"},
    {"event_type", "varchar(40)", "NO"}, {"actor_account_id", "varchar(64)", "YES"},
    {"request_id", "char(36)", "NO"}
  };

  private AdminDatabaseVerifier() {}

  static void verify(Connection connection, AdminRuntimeProperties properties) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      try (var rows = statement.executeQuery(IDENTITY)) {
        if (!rows.next()) refuse(AdminStartupException.Reason.IDENTITY);
        String schema = cell(rows, 1),
            user = cell(rows, 2),
            role = cell(rows, 3),
            mandatory = cell(rows, 4);
        if (!properties.schema().equals(schema)
            || !properties.expectedUser().equals(user)
            || rows.next()) refuse(AdminStartupException.Reason.IDENTITY);
        if (!"NONE".equals(role) || mandatory == null || !mandatory.isBlank())
          refuse(AdminStartupException.Reason.ROLES);
      }
      try (var rows = statement.executeQuery(TLS)) {
        if (!rows.next() || !"Ssl_cipher".equalsIgnoreCase(cell(rows, 1)))
          refuse(AdminStartupException.Reason.TLS);
        String cipher = cell(rows, 2);
        if (cipher == null || cipher.isBlank() || rows.next())
          refuse(AdminStartupException.Reason.TLS);
      }
      var grants = new ArrayList<String>();
      try (var rows = statement.executeQuery(GRANTS)) {
        while (rows.next()) {
          if (grants.size() >= 64) refuse(AdminStartupException.Reason.GRANTS);
          grants.add(cell(rows, 1));
        }
      }
      try {
        AdminRuntimeGrantPolicy.validate(properties.schema(), properties.expectedUser(), grants);
      } catch (IllegalArgumentException ex) {
        throw new AdminStartupException(AdminStartupException.Reason.GRANTS);
      }
      try (var rows = statement.executeQuery(AUTH_EVENT_SHAPE)) {
        for (String[] column : AUTH_COLUMNS) {
          if (!rows.next()) refuse(AdminStartupException.Reason.DATABASE_CHECK);
          for (int i = 0; i < column.length; i++)
            if (!column[i].equals(cell(rows, i + 1)))
              refuse(AdminStartupException.Reason.DATABASE_CHECK);
        }
        if (rows.next()) refuse(AdminStartupException.Reason.DATABASE_CHECK);
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
}
