package cn.zhinan.adminbooking.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Applies the admin-migration-cli sources in order. The booking IT does not keep its own copy. */
final class AdminSchemaMigrations {
  private AdminSchemaMigrations() {}

  static Path directory() {
    List<Path> candidates =
        List.of(
            Path.of("admin-migration-cli/src/main/resources/db/admin-migration"),
            Path.of("../admin-migration-cli/src/main/resources/db/admin-migration"),
            Path.of("backend/admin-migration-cli/src/main/resources/db/admin-migration"));
    for (Path candidate : candidates)
      if (Files.isDirectory(candidate)) return candidate.toAbsolutePath().normalize();
    throw new IllegalStateException(
        "Admin migration sources were not found from " + Path.of("").toAbsolutePath());
  }

  static void replaceIsolatedSchema(Connection connection) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET FOREIGN_KEY_CHECKS=0");
      List<String> tables = new ArrayList<>();
      try (ResultSet rows =
          statement.executeQuery(
              "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()")) {
        while (rows.next()) tables.add(rows.getString(1));
      }
      for (String table : tables) {
        if ("southleaf_booking_it_owner".equals(table)) continue;
        statement.execute("DROP TABLE IF EXISTS `" + table + "`");
      }
      statement.execute("SET FOREIGN_KEY_CHECKS=1");
    }
    apply(connection);
  }

  static void apply(Connection connection) throws Exception {
    List<Path> scripts;
    try (var files = Files.list(directory())) {
      scripts =
          files
              .filter(path -> path.getFileName().toString().matches("V\\d+__.+\\.sql"))
              .sorted(Comparator.comparingInt(AdminSchemaMigrations::version))
              .toList();
    }
    if (scripts.isEmpty()) throw new IllegalStateException("No admin migration scripts");
    try (Statement statement = connection.createStatement()) {
      for (Path script : scripts) {
        for (String sql : statements(Files.readString(script))) {
          try {
            statement.execute(sql);
          } catch (RuntimeException | java.sql.SQLException failure) {
            throw new IllegalStateException(
                "Migration failed in " + script.getFileName() + ": " + failure.getMessage(),
                failure);
          }
        }
      }
    }
  }

  private static int version(Path path) {
    String name = path.getFileName().toString();
    return Integer.parseInt(name.substring(1, name.indexOf('_')));
  }

  static List<String> statements(String text) {
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String line : text.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;
      current.append(line).append('\n');
      if (trimmed.endsWith(";")) {
        statements.add(current.toString());
        current.setLength(0);
      }
    }
    if (!current.isEmpty())
      throw new IllegalStateException("Admin migration has a trailing fragment");
    return statements;
  }
}
