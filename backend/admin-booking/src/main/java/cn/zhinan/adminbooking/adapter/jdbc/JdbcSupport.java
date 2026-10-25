package cn.zhinan.adminbooking.adapter.jdbc;

import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.BookingFailure;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/** Small JDBC helpers. No SQL, table or column name ever reaches the domain layer. */
final class JdbcSupport {
  private JdbcSupport() {}

  interface RowMapper<T> {
    T map(ResultSet resultSet) throws SQLException;
  }

  static <T> T queryOne(
      JdbcConnectionProvider connections, String sql, RowMapper<T> mapper, Object... parameters) {
    try (PreparedStatement statement = connections.current().prepareStatement(sql)) {
      bind(statement, parameters);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) return null;
        return mapper.map(resultSet);
      }
    } catch (SQLException failure) {
      throw translate(failure, null);
    } finally {
      connections.releaseIfIdle();
    }
  }

  static <T> java.util.List<T> queryList(
      JdbcConnectionProvider connections, String sql, RowMapper<T> mapper, Object... parameters) {
    java.util.List<T> results = new java.util.ArrayList<>();
    try (PreparedStatement statement = connections.current().prepareStatement(sql)) {
      bind(statement, parameters);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          results.add(mapper.map(resultSet));
        }
      }
    } catch (SQLException failure) {
      throw translate(failure, null);
    } finally {
      connections.releaseIfIdle();
    }
    return results;
  }

  static int update(JdbcConnectionProvider connections, String sql, Object... parameters) {
    try (PreparedStatement statement = connections.current().prepareStatement(sql)) {
      bind(statement, parameters);
      return statement.executeUpdate();
    } catch (SQLException failure) {
      throw translate(failure, null);
    } finally {
      connections.releaseIfIdle();
    }
  }

  /**
   * Executes a statement whose uniqueness is a business invariant. A duplicate key is reported as
   * the declared conflict reason, so the caller never has to parse a driver specific message.
   */
  static int updateProtected(
      JdbcConnectionProvider connections, String conflictReason, String sql, Object... parameters) {
    try (PreparedStatement statement = connections.current().prepareStatement(sql)) {
      bind(statement, parameters);
      return statement.executeUpdate();
    } catch (SQLException failure) {
      throw translate(failure, conflictReason);
    } finally {
      connections.releaseIfIdle();
    }
  }

  static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
    for (int index = 0; index < parameters.length; index++) {
      Object value = parameters[index];
      if (value instanceof Instant instant) {
        statement.setTimestamp(index + 1, Timestamp.from(instant));
      } else if (value == null) {
        statement.setObject(index + 1, null);
      } else {
        statement.setObject(index + 1, value);
      }
    }
  }

  static Instant instant(ResultSet resultSet, String column) throws SQLException {
    Timestamp value = resultSet.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  /**
   * Maps a driver failure onto a stable category.
   *
   * <p>The decision uses the SQL state and the vendor error code only. The driver message is never
   * inspected, because its wording and the key name it quotes differ between server versions and
   * between MySQL and its forks. The caller declares which invariant a duplicate key violates.
   */
  static BookingException translate(SQLException failure, String conflictReason) {
    String state = failure.getSQLState();
    int code = failure.getErrorCode();
    if (state != null && state.startsWith("23")) {
      if (code == 1062) {
        return conflictReason == null
            ? new BookingException(BookingFailure.CONFLICT, "UNIQUE_CONSTRAINT_VIOLATION")
            : new BookingException(BookingFailure.CONFLICT, conflictReason);
      }
      return new BookingException(BookingFailure.CONFLICT, "CONSTRAINT_VIOLATION");
    }
    if (state != null && state.startsWith("40")) {
      return new BookingException(BookingFailure.VERSION_CONFLICT, "CONCURRENT_UPDATE");
    }
    // InnoDB reports a concurrent write conflict on the same row as an internal error with the
    // vendor codes below, and a deadlock or a lock wait timeout with its own codes. They are not
    // storage failures: the transaction lost a race and can be retried by the caller.
    if (code == 1020 || code == 1205 || code == 1213 || code == 3572) {
      return new BookingException(BookingFailure.VERSION_CONFLICT, "CONCURRENT_UPDATE");
    }
    // The SQL state and the vendor code are stable and carry no schema, column or value detail.
    String detail = "SQL_" + (state == null ? "UNKNOWN" : state) + "_" + code;
    return new BookingException(BookingFailure.STORAGE_UNAVAILABLE, detail);
  }
}
