package cn.zhinan.adminbooking.adapter.jdbc;

import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.BookingFailure;
import cn.zhinan.adminbooking.domain.OperatingRule;
import cn.zhinan.adminbooking.port.OperatingRulePorts.OperatingRuleRepository;
import cn.zhinan.adminbooking.port.OperatingRulePorts.StoredRule;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Head pointer plus append-only JSON versions. Reads do not invent a default rule. */
public final class JdbcOperatingRuleRepository implements OperatingRuleRepository {
  private final JdbcConnectionProvider connections;

  public JdbcOperatingRuleRepository(JdbcConnectionProvider connections) {
    this.connections = connections;
  }

  @Override
  public Optional<StoredRule> current() {
    StoredRule rule =
        JdbcSupport.queryOne(
            connections,
            "SELECT v.version, CAST(v.payload_json AS CHAR) AS payload_json, v.updated_by, v.updated_at"
                + " FROM admin_booking_operating_rule h"
                + " JOIN admin_booking_operating_rule_version v ON v.version = h.current_version"
                + " WHERE h.singleton_id = 1 AND h.current_version >= 1",
            this::map);
    return Optional.ofNullable(rule);
  }

  @Override
  public List<StoredRule> history() {
    return JdbcSupport.queryList(
        connections,
        "SELECT version, CAST(payload_json AS CHAR) AS payload_json, updated_by, updated_at"
            + " FROM admin_booking_operating_rule_version ORDER BY version DESC",
        this::map);
  }

  @Override
  public Optional<Integer> lockHead() {
    Integer version =
        JdbcSupport.queryOne(
            connections,
            "SELECT current_version FROM admin_booking_operating_rule WHERE singleton_id = 1 FOR UPDATE",
            resultSet -> resultSet.getInt(1));
    return Optional.ofNullable(version);
  }

  @Override
  public void insertVersion(int version, String payloadJson, String updatedBy, Instant updatedAt) {
    JdbcSupport.updateProtected(
        connections,
        "RULE_VERSION_CONFLICT",
        "INSERT INTO admin_booking_operating_rule_version"
            + " (version, payload_json, updated_by, updated_at) VALUES (?, ?, ?, ?)",
        version,
        payloadJson,
        updatedBy,
        Timestamp.from(updatedAt));
  }

  @Override
  public void insertHead(int version) {
    JdbcSupport.updateProtected(
        connections,
        "RULE_VERSION_CONFLICT",
        "INSERT INTO admin_booking_operating_rule (singleton_id, current_version) VALUES (1, ?)",
        version);
  }

  @Override
  public void updateHead(int expectedVersion, int version) {
    int updated =
        JdbcSupport.update(
            connections,
            "UPDATE admin_booking_operating_rule SET current_version = ?"
                + " WHERE singleton_id = 1 AND current_version = ?",
            version,
            expectedVersion);
    if (updated != 1)
      throw new BookingException(BookingFailure.VERSION_CONFLICT, "RULE_VERSION_CONFLICT");
  }

  private StoredRule map(java.sql.ResultSet resultSet) throws java.sql.SQLException {
    Timestamp updatedAt = resultSet.getTimestamp("updated_at");
    return new StoredRule(
        resultSet.getInt("version"),
        OperatingRule.fromStoredJson(resultSet.getString("payload_json")),
        resultSet.getString("updated_by"),
        updatedAt == null ? null : updatedAt.toInstant());
  }
}
