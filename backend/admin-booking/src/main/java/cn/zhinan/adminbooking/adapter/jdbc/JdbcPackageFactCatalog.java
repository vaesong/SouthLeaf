package cn.zhinan.adminbooking.adapter.jdbc;

import cn.zhinan.adminbooking.domain.BookingModels.PackageFact;
import cn.zhinan.adminbooking.port.BookingPorts.PackageCatalog;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only access to immutable package price facts (admin_package_fact).
 *
 * <p>The data mode is fixed when the catalog is constructed and is part of every lookup, so a
 * PRODUCTION runtime can never read a SYNTHETIC row and an acceptance run can never read production
 * pricing. There is deliberately no write path: a released fact is corrected by publishing a new
 * {@code version_ref}, never by editing the old one, so historical order snapshots stay
 * explainable.
 */
public final class JdbcPackageFactCatalog implements PackageCatalog {
  static final Set<String> DATA_MODES = Set.of("SYNTHETIC", "PRODUCTION");
  private static final int MAX_REF_LENGTH = 36;

  private final JdbcConnectionProvider connections;
  private final String dataMode;

  public JdbcPackageFactCatalog(JdbcConnectionProvider connections, String dataMode) {
    if (connections == null) throw new IllegalArgumentException("connections is required");
    this.connections = connections;
    this.dataMode = normalizeMode(dataMode);
  }

  static String normalizeMode(String dataMode) {
    String normalized = dataMode == null ? "" : dataMode.trim().toUpperCase(Locale.ROOT);
    if (!DATA_MODES.contains(normalized))
      throw new IllegalArgumentException("unsupported package data mode");
    return normalized;
  }

  @Override
  public Optional<PackageFact> findPackageVersion(String packageVersionRef) {
    if (packageVersionRef == null
        || packageVersionRef.isBlank()
        || packageVersionRef.length() > MAX_REF_LENGTH) return Optional.empty();
    PackageFact fact =
        JdbcSupport.queryOne(
            connections,
            "SELECT version_ref, display_name, currency_code, amount_minor_units, price_version"
                + " FROM admin_package_fact WHERE version_ref = ? AND data_mode = ?",
            resultSet ->
                new PackageFact(
                    resultSet.getString("version_ref"),
                    resultSet.getString("display_name"),
                    resultSet.getString("currency_code"),
                    resultSet.getLong("amount_minor_units"),
                    resultSet.getString("price_version")),
            packageVersionRef,
            dataMode);
    return Optional.ofNullable(fact);
  }

  public String dataMode() {
    return dataMode;
  }

  @Override
  public String toString() {
    return "JdbcPackageFactCatalog[" + dataMode + "]";
  }
}
