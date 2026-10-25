package cn.zhinan.adminsecurity.adapter.jdbc;

import cn.zhinan.adminsecurity.domain.AuthenticationEvent;
import cn.zhinan.adminsecurity.port.AdminSecurityStorageException;
import cn.zhinan.adminsecurity.port.AuthenticationEventSink;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** Append-only short transaction; no raw attempt identity, secret payload, retries or upsert. */
public final class JdbcAuthenticationEventSink implements AuthenticationEventSink {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate write;

  public JdbcAuthenticationEventSink(DataSource source) {
    Objects.requireNonNull(source);
    jdbc = new JdbcTemplate(source);
    write = AdminJdbcTransactions.independent(source, false);
  }

  @Override
  public void append(AuthenticationEvent event) {
    Objects.requireNonNull(event);
    try {
      write.executeWithoutResult(
          status -> {
            int inserted =
                jdbc.update(
                    "INSERT INTO admin_auth_event(event_id,occurred_at,event_type,actor_account_id,request_id) VALUES(?,?,?,?,?)",
                    UUID.randomUUID().toString(),
                    LocalDateTime.ofInstant(
                        event.occurredAt().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC),
                    event.kind().name(),
                    event.knownAccountId(),
                    event.requestId().toString());
            if (inserted != 1)
              throw new IllegalStateException("Authentication event not persisted");
          });
    } catch (DataAccessException | TransactionException | IllegalArgumentException ex) {
      throw new AdminSecurityStorageException(ex);
    }
  }
}
