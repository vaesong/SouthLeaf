package cn.zhinan.adminmigration;

import cn.zhinan.admindbpolicy.AdminMigrationPolicy;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Explicit management migration, separate schema/history. No default migrate, seed or repair. */
public final class AdminDatabaseMigrationMain {
  private AdminDatabaseMigrationMain() {}

  public static void main(String[] args) throws Exception {
    String action = action(args);
    String expected = required("SOUTHLEAF_ADMIN_MIGRATION_SCHEMA");
    String url = required("SOUTHLEAF_ADMIN_MIGRATION_URL");
    validateUrl(url);
    var source =
        new DriverManagerDataSource(
            url,
            required("SOUTHLEAF_ADMIN_MIGRATION_USERNAME"),
            required("SOUTHLEAF_ADMIN_MIGRATION_PASSWORD"));
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      String schema, user, cipher = "";
      try (var rows = statement.executeQuery("SELECT DATABASE(),CURRENT_USER()")) {
        rows.next();
        schema = rows.getString(1);
        user = rows.getString(2);
      }
      try (var rows = statement.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
        if (rows.next()) cipher = rows.getString(2);
      }
      validateTarget(expected, schema, user, cipher);
    }
    var flyway =
        Flyway.configure()
            .dataSource(source)
            .locations("classpath:db/admin-migration")
            .table("flyway_admin_schema_history")
            .createSchemas(false)
            .cleanDisabled(true)
            .baselineOnMigrate(false)
            .load();
    if (action.equals("--migrate")) flyway.migrate();
    flyway.validate();
    if (flyway.info().current() == null)
      throw new IllegalStateException("Management migration has not been applied");
    System.out.println(
        "ADMIN_SCHEMA_" + (action.equals("--migrate") ? "MIGRATION" : "VALIDATION") + "_PASS");
  }

  static String action(String[] args) {
    if (args == null
        || args.length != 1
        || (!args[0].equals("--migrate") && !args[0].equals("--validate"))) {
      throw new IllegalArgumentException("Explicit --migrate or --validate is required");
    }
    return args[0];
  }

  static void validateUrl(String url) {
    AdminMigrationPolicy.validateMigrationUrl(url);
  }

  static void validateTarget(String expected, String actual, String user, String cipher) {
    AdminMigrationPolicy.validateMigrationTarget(expected, actual, user, cipher);
  }

  private static String required(String key) {
    String value = System.getenv(key);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Required environment variable: " + key);
    return value;
  }
}
