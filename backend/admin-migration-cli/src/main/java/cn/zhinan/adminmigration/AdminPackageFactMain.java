package cn.zhinan.adminmigration;

import cn.zhinan.admindbpolicy.AdminMigrationPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Controlled entry point for immutable price facts.
 *
 * <p>{@code admin_package_fact} has no update path by design: correcting a price means writing a
 * new {@code version_ref}. This tool therefore only ever inserts, treats an identical existing row
 * as a no-op, and refuses the whole batch when an existing ref would change.
 */
public final class AdminPackageFactMain {
  private static final String INSERT =
      "INSERT INTO admin_package_fact"
          + " (version_ref, display_name, currency_code, amount_minor_units, price_version,"
          + " data_mode, created_at) VALUES (?,?,?,?,?,?,?)";
  private static final String SELECT_EXISTING =
      "SELECT display_name, currency_code, amount_minor_units, price_version, data_mode"
          + " FROM admin_package_fact WHERE version_ref = ?";

  private AdminPackageFactMain() {}

  /** What happened to one fact. A conflict aborts the batch before anything is committed. */
  enum Outcome {
    INSERT,
    EXISTING
  }

  public static void main(String[] args) {
    try {
      run(args);
    } catch (IllegalArgumentException refused) {
      System.out.println("PACKAGE_FACT_REFUSED:" + refused.getMessage());
      System.exit(2);
    } catch (Exception failure) {
      System.out.println("PACKAGE_FACT_REFUSED:" + rootCause(failure));
      System.exit(2);
    }
  }

  static void run(String[] args) throws Exception {
    boolean dryRun = false;
    String file = null;
    for (int index = 0; index < args.length; index++) {
      switch (args[index]) {
        case "--dry-run" -> dryRun = true;
        case "--import" -> {
          if (index + 1 >= args.length) throw new IllegalArgumentException("ARGUMENTS");
          file = args[++index];
        }
        default -> throw new IllegalArgumentException("ARGUMENTS");
      }
    }
    if (file == null) throw new IllegalArgumentException("ARGUMENTS");

    PackageFactDocument document =
        PackageFactDocument.parse(Files.readString(Path.of(file), StandardCharsets.UTF_8));

    var source =
        new DriverManagerDataSource(
            required("SOUTHLEAF_ADMIN_MIGRATION_URL"),
            required("SOUTHLEAF_ADMIN_MIGRATION_USERNAME"),
            required("SOUTHLEAF_ADMIN_MIGRATION_PASSWORD"));
    AdminMigrationPolicy.validateMigrationUrl(source.getUrl());
    String expectedSchema = required("SOUTHLEAF_ADMIN_MIGRATION_SCHEMA");

    try (Connection connection = source.getConnection()) {
      // Same target verification as the migration entry point: right schema, right user, TLS on.
      requireVerifiedTarget(connection, expectedSchema);

      connection.setAutoCommit(false);
      final List<Outcome> outcomes;
      try {
        outcomes = classify(connection, document, !dryRun);
        if (dryRun || outcomes.stream().noneMatch(outcome -> outcome == Outcome.INSERT)) {
          connection.rollback();
        } else {
          connection.commit();
        }
      } catch (RuntimeException | SQLException failure) {
        connection.rollback();
        throw failure;
      }
      long inserted = outcomes.stream().filter(outcome -> outcome == Outcome.INSERT).count();
      long existing = outcomes.stream().filter(outcome -> outcome == Outcome.EXISTING).count();
      System.out.println(
          (dryRun ? "PACKAGE_FACT_DRY_RUN" : "PACKAGE_FACT_IMPORTED")
              + ":insert="
              + inserted
              + ":existing="
              + existing);
    }
  }

  /**
   * Decides per fact whether it is new, already present, or a rewrite of a released price, and
   * writes the new rows when {@code write} is set. Never issues an UPDATE.
   */
  static List<Outcome> classify(Connection connection, PackageFactDocument document, boolean write)
      throws SQLException {
    List<Outcome> outcomes = new ArrayList<>();
    for (PackageFactDocument.Fact fact : document.facts()) {
      Boolean identical = findExisting(connection, fact, document.dataMode());
      if (identical == null) {
        if (write) insert(connection, fact, document.dataMode());
        outcomes.add(Outcome.INSERT);
      } else if (identical) {
        outcomes.add(Outcome.EXISTING);
      } else {
        // A released ref that differs in any field is a conflict: the fix is a new version_ref.
        throw new IllegalArgumentException("VERSION_REF_CONFLICT:" + fact.versionRef());
      }
    }
    return outcomes;
  }

  /**
   * @return null when the ref is absent, true when the stored row is identical.
   */
  private static Boolean findExisting(
      Connection connection, PackageFactDocument.Fact fact, String dataMode) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SELECT_EXISTING)) {
      statement.setString(1, fact.versionRef());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) return null;
        return rows.getString(1).equals(fact.displayName())
            && rows.getString(2).equals(fact.currencyCode())
            && rows.getLong(3) == fact.amountMinorUnits()
            && rows.getString(4).equals(fact.priceVersion())
            && rows.getString(5).equals(dataMode);
      }
    }
  }

  private static void requireVerifiedTarget(Connection connection, String expectedSchema)
      throws SQLException {
    String schema;
    String user;
    String cipher = "";
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT DATABASE(),CURRENT_USER()")) {
      rows.next();
      schema = rows.getString(1);
      user = rows.getString(2);
    }
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
      if (rows.next()) cipher = rows.getString(2);
    }
    AdminMigrationPolicy.validateMigrationTarget(expectedSchema, schema, user, cipher);
  }

  private static void insert(Connection connection, PackageFactDocument.Fact fact, String dataMode)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
      statement.setString(1, fact.versionRef());
      statement.setString(2, fact.displayName());
      statement.setString(3, fact.currencyCode());
      statement.setLong(4, fact.amountMinorUnits());
      statement.setString(5, fact.priceVersion());
      statement.setString(6, dataMode);
      statement.setTimestamp(7, Timestamp.from(Instant.now()));
      statement.executeUpdate();
    }
  }

  private static String required(String key) {
    String value = System.getenv(key);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Required environment variable: " + key);
    return value;
  }

  private static String rootCause(Exception failure) {
    Throwable cause = failure;
    while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
    return cause.getClass().getSimpleName();
  }
}
