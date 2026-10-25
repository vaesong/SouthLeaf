package cn.zhinan.adminsecurity.adapter.jdbc;

import cn.zhinan.adminsecurity.domain.AdminAccount;
import cn.zhinan.adminsecurity.domain.AdminPermission;
import cn.zhinan.adminsecurity.domain.AdminSession;
import cn.zhinan.adminsecurity.port.AdminSecurityStorageException;
import cn.zhinan.adminsecurity.port.AdminSessionStore;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicitly wired management writer adapter; never auto-discovered by the public runtime. */
public final class JdbcAdminSessionStore implements AdminSessionStore {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate write;
  private final TransactionTemplate read;

  public JdbcAdminSessionStore(DataSource writer) {
    Objects.requireNonNull(writer);
    jdbc = new JdbcTemplate(writer);
    var manager = new DataSourceTransactionManager(writer);
    write = transaction(manager, false);
    read = transaction(manager, true);
  }

  private static TransactionTemplate transaction(
      DataSourceTransactionManager manager, boolean readOnly) {
    var template = new TransactionTemplate(manager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    template.setReadOnly(readOnly);
    template.setTimeout(5);
    return template;
  }

  @Override
  public boolean createIfAccountCurrent(AdminSession session) {
    Objects.requireNonNull(session);
    try {
      return Boolean.TRUE.equals(
          write.execute(
              status -> {
                var accounts =
                    jdbc.query(
                        // A shared locking read blocks account mutations without granting account
                        // UPDATE.
                        "SELECT enabled,security_version FROM admin_account WHERE account_id=? FOR SHARE",
                        (row, n) ->
                            row.getBoolean("enabled")
                                && row.getLong("security_version") == session.securityVersion(),
                        session.accountId());
                if (accounts.isEmpty() || !accounts.getFirst()) return false;
                String sessionId = UUID.randomUUID().toString();
                jdbc.update(
                    "INSERT INTO admin_session(token_digest,session_id,account_id,security_version,issued_at,expires_at) VALUES(?,?,?,?,?,?)",
                    session.tokenDigest(),
                    sessionId,
                    session.accountId(),
                    session.securityVersion(),
                    utc(session.issuedAt()),
                    utc(session.expiresAt()));
                audit(session.accountId(), sessionId, "ADMIN_SESSION_OPENED", session.issuedAt());
                return true;
              }));
    } catch (DataAccessException | TransactionException | IllegalArgumentException ex) {
      throw new AdminSecurityStorageException(ex);
    }
  }

  @Override
  public Optional<SessionState> findCurrent(String tokenDigest) {
    requireDigest(tokenDigest);
    try {
      return Objects.requireNonNull(
          read.execute(
              status ->
                  jdbc.query(
                      "SELECT s.token_digest,s.account_id,s.security_version AS session_version,s.issued_at,s.expires_at,"
                          + "a.enabled,a.security_version AS account_version,p.permission_code "
                          + "FROM admin_session s JOIN admin_account a ON a.account_id=s.account_id "
                          + "LEFT JOIN admin_account_permission p ON p.account_id=a.account_id "
                          + "WHERE s.token_digest=? AND s.revoked_at IS NULL ORDER BY p.permission_code",
                      rows -> {
                        if (!rows.next()) return Optional.<SessionState>empty();
                        var session =
                            new AdminSession(
                                rows.getString("token_digest"),
                                rows.getString("account_id"),
                                rows.getLong("session_version"),
                                rows.getObject("issued_at", LocalDateTime.class)
                                    .toInstant(ZoneOffset.UTC),
                                rows.getObject("expires_at", LocalDateTime.class)
                                    .toInstant(ZoneOffset.UTC));
                        boolean enabled = rows.getBoolean("enabled");
                        long version = rows.getLong("account_version");
                        Set<AdminPermission> permissions = EnumSet.noneOf(AdminPermission.class);
                        do {
                          String code = rows.getString("permission_code");
                          if (code != null) permissions.add(AdminPermission.valueOf(code));
                        } while (rows.next());
                        return Optional.of(
                            new SessionState(
                                session,
                                new AdminAccount(
                                    session.accountId(), enabled, version, permissions)));
                      },
                      tokenDigest)));
    } catch (DataAccessException | TransactionException | IllegalArgumentException ex) {
      throw new AdminSecurityStorageException(ex);
    }
  }

  @Override
  public void revoke(String tokenDigest, Instant revokedAt) {
    requireDigest(tokenDigest);
    Objects.requireNonNull(revokedAt);
    try {
      write.executeWithoutResult(
          status -> {
            var sessions =
                jdbc.query(
                    "SELECT session_id,account_id FROM admin_session WHERE token_digest=? AND revoked_at IS NULL FOR UPDATE",
                    (row, n) ->
                        new RevocationTarget(
                            row.getString("session_id"), row.getString("account_id")),
                    tokenDigest);
            if (sessions.isEmpty()) return;
            var target = sessions.getFirst();
            jdbc.update(
                "UPDATE admin_session SET revoked_at=? WHERE token_digest=?",
                utc(revokedAt),
                tokenDigest);
            audit(target.accountId(), target.sessionId(), "ADMIN_SESSION_REVOKED", revokedAt);
          });
    } catch (DataAccessException | TransactionException | IllegalArgumentException ex) {
      throw new AdminSecurityStorageException(ex);
    }
  }

  private void audit(String accountId, String sessionId, String action, Instant occurredAt) {
    jdbc.update(
        "INSERT INTO admin_security_audit(event_id,actor_account_id,target_session_id,action,occurred_at) VALUES(?,?,?,?,?)",
        UUID.randomUUID().toString(),
        accountId,
        sessionId,
        action,
        utc(occurredAt));
  }

  private static LocalDateTime utc(Instant time) {
    return LocalDateTime.ofInstant(time, ZoneOffset.UTC);
  }

  private static void requireDigest(String digest) {
    if (digest == null || !digest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Expected session digest, never a raw token");
    }
  }

  private record RevocationTarget(String sessionId, String accountId) {}
}
