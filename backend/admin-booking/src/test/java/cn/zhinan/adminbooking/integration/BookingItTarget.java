package cn.zhinan.adminbooking.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A fail-closed ownership guard for the destructive booking database integration suites. */
final class BookingItTarget {
  private static final Pattern URL =
      Pattern.compile(
          "^jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):(\\d+)/southleaf_booking_it(?:\\?.*)?$");

  private BookingItTarget() {}

  /**
   * Refuses to drop/recreate tables unless the connection points at the explicitly owned local
   * schema and its random per-instance ownership marker matches the launcher environment.
   */
  static void assertOwned(String url, String username, String password, String expectedInstanceId)
      throws Exception {
    Matcher matcher = URL.matcher(url);
    if (!matcher.matches())
      throw new IllegalStateException(
          "Booking IT refuses a non-isolated or non-loopback JDBC URL.");
    int port = Integer.parseInt(matcher.group(1));
    if (port == 3306)
      throw new IllegalStateException("The original MySQL server is not a booking IT target.");
    if (expectedInstanceId == null || expectedInstanceId.isBlank())
      throw new IllegalStateException("Booking IT requires its isolated instance marker.");

    try (Connection connection = DriverManager.getConnection(url, username, password)) {
      try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT instance_id FROM southleaf_booking_it_owner WHERE owner_id = 1");
          ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next() || !expectedInstanceId.equals(resultSet.getString(1)))
          throw new IllegalStateException("Booking IT ownership marker does not match.");
      }
      try (PreparedStatement statement = connection.prepareStatement("SELECT DATABASE(), @@port");
          ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()
            || !"southleaf_booking_it".equals(resultSet.getString(1))
            || resultSet.getInt(2) != port)
          throw new IllegalStateException("Booking IT connected to an unexpected schema or port.");
      }
    }
  }
}
