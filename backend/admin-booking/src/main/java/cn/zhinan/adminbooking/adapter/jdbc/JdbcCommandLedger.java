package cn.zhinan.adminbooking.adapter.jdbc;

import cn.zhinan.adminbooking.port.BookingPorts.CommandLedger;
import cn.zhinan.adminbooking.port.BookingPorts.StoredCommand;
import java.time.Instant;
import java.util.Optional;

/**
 * The command ledger that makes writes idempotent. The scope is actor + operation + client key; the
 * unique key on the table is the final guard against a second write for the same scope.
 */
public final class JdbcCommandLedger implements CommandLedger {
  private final JdbcConnectionProvider connections;

  public JdbcCommandLedger(JdbcConnectionProvider connections) {
    this.connections = connections;
  }

  @Override
  public Optional<StoredCommand> find(String actorId, String operation, String idempotencyKey) {
    StoredCommand command =
        JdbcSupport.queryOne(
            connections,
            "SELECT request_sha256, result_ref FROM booking_command"
                + " WHERE actor_id = ? AND operation = ? AND idempotency_key = ?",
            resultSet ->
                new StoredCommand(
                    resultSet.getString("request_sha256"), resultSet.getString("result_ref")),
            actorId,
            operation,
            idempotencyKey);
    return Optional.ofNullable(command);
  }

  @Override
  public void record(
      String actorId,
      String operation,
      String idempotencyKey,
      String requestSha256,
      String resultId,
      Instant completedAt) {
    JdbcSupport.updateProtected(
        connections,
        "IDEMPOTENCY_SCOPE_CONFLICT",
        "INSERT INTO booking_command"
            + " (id, actor_id, operation, idempotency_key, request_sha256, result_ref, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
        cn.zhinan.adminbooking.domain.BookingRules.newId(),
        actorId,
        operation,
        idempotencyKey,
        requestSha256,
        resultId,
        completedAt);
  }
}
