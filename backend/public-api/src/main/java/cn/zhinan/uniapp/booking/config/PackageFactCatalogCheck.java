package cn.zhinan.uniapp.booking.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Refuses to start when a configured package ref has no matching price fact.
 *
 * <p>The catalogue decides which packages are bookable; {@code admin_package_fact} decides what
 * they cost. A ref that exists in only one of them is a configuration mistake that would surface as
 * a failed confirmation minutes later, so it is turned into a startup refusal instead.
 */
final class PackageFactCatalogCheck {
  private PackageFactCatalogCheck() {}

  /**
   * Pure decision over already-read facts, so the rule is testable without a database.
   *
   * @return the refusal reason, or null when every configured ref has a fact in the right mode.
   */
  static String evaluate(List<String> refs, Map<String, String> factModes, String expectedMode) {
    for (String ref : refs) {
      String mode = factModes.get(ref);
      if (mode == null) return "PACKAGE_REF_UNKNOWN";
      if (!expectedMode.equals(mode)) return "PACKAGE_FACT_MODE_MISMATCH";
    }
    return null;
  }

  static void requireVerified(DataSource source, PublicBookingProperties properties) {
    List<String> refs = properties.packages().stream().map(Map.Entry::getKey).toList();
    String failure = evaluate(refs, factModes(source, refs), properties.dataMode());
    if (failure != null) throw new IllegalStateException("PUBLIC_BOOKING_REFUSED:" + failure);
  }

  private static Map<String, String> factModes(DataSource source, List<String> refs) {
    String sql =
        "SELECT version_ref, data_mode FROM admin_package_fact WHERE version_ref IN ("
            + String.join(",", Collections.nCopies(refs.size(), "?"))
            + ")";
    try (Connection connection = source.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < refs.size(); index++) {
        statement.setString(index + 1, refs.get(index));
      }
      try (ResultSet rows = statement.executeQuery()) {
        Map<String, String> modes = new HashMap<>();
        while (rows.next()) modes.put(rows.getString(1), rows.getString(2));
        return modes;
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("PUBLIC_BOOKING_REFUSED:PACKAGE_FACT_UNAVAILABLE", failure);
    }
  }
}
