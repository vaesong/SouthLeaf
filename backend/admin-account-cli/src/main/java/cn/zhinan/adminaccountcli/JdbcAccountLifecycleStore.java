package cn.zhinan.adminaccountcli;

import cn.zhinan.adminsecurity.domain.AccountLifecycleException;
import cn.zhinan.adminsecurity.domain.AccountLifecycleOperation;
import cn.zhinan.adminsecurity.domain.AccountLifecycleResult;
import cn.zhinan.adminsecurity.domain.AdminPermission;
import cn.zhinan.adminsecurity.port.AccountLifecycleStore;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dedicated C3 writer. Every mutation first locks the singleton guard row, serializing account
 * lifecycle changes before locking a target account. Runtime has neither this adapter nor its DB
 * grants.
 */
final class JdbcAccountLifecycleStore implements AccountLifecycleStore {
  private static final String GUARD_LOCK =
      "SELECT completed_at,bootstrap_command_id FROM admin_bootstrap_guard WHERE guard_id=1 FOR UPDATE";
  private final JdbcTemplate jdbc;
  private final TransactionTemplate write;
  private final TransactionTemplate read;
  private final String executionIdentity;

  JdbcAccountLifecycleStore(DataSource dataSource, String executionIdentity) {
    Objects.requireNonNull(dataSource);
    if (executionIdentity == null
        || executionIdentity.isBlank()
        || executionIdentity.length() > 288) {
      throw new IllegalArgumentException("Invalid verified execution identity");
    }
    jdbc = new JdbcTemplate(dataSource);
    var manager = new DataSourceTransactionManager(dataSource);
    write = transaction(manager, false);
    read = transaction(manager, true);
    this.executionIdentity = executionIdentity;
  }

  @Override
  public Optional<AccountLifecycleResult> findResult(UUID commandId) {
    if (commandId == null) return Optional.empty();
    try {
      return Objects.requireNonNull(
          read.execute(status -> command(commandId).map(StoredCommand::result)));
    } catch (DataAccessException | TransactionException ex) {
      throw database();
    }
  }

  @Override
  public AccountLifecycleResult bootstrap(Bootstrap command) {
    Objects.requireNonNull(command);
    return secret(
        command.commandId(),
        () -> {
          Guard guard = lockGuard();
          if (guard.completed())
            reject(AccountLifecycleException.Reason.BOOTSTRAP_ALREADY_COMPLETED);
          if (lockAccountIfPresent(command.accountId()).isPresent()) {
            reject(AccountLifecycleException.Reason.ACCOUNT_ALREADY_EXISTS);
          }
          Instant now = Instant.now();
          jdbc.update(
              "INSERT INTO admin_account(account_id,enabled,security_version,credential_hash) VALUES(?,TRUE,0,?)",
              command.accountId(),
              command.credentialHash());
          replacePermissions(command.accountId(), command.permissions());
          AccountLifecycleResult result =
              insertCommand(
                  command.commandId(),
                  AccountLifecycleOperation.BOOTSTRAP,
                  command.accountId(),
                  null,
                  0,
                  null,
                  now);
          int updated =
              jdbc.update(
                  "UPDATE admin_bootstrap_guard SET completed_at=?,bootstrap_command_id=? WHERE guard_id=1 AND completed_at IS NULL",
                  utc(now),
                  command.commandId().toString());
          if (updated != 1) reject(AccountLifecycleException.Reason.STORE_INVARIANT);
          return result;
        });
  }

  @Override
  public AccountLifecycleResult changePassword(PasswordChange command) {
    Objects.requireNonNull(command);
    return secret(
        command.commandId(),
        () -> {
          requireBootstrapCompleted(lockGuard());
          AccountState state = lockRequiredAccount(command.accountId());
          requireCurrent(state, command.expectedSecurityVersion());
          requireEnabled(state);
          long resultVersion = increment(command.accountId(), command.credentialHash(), null);
          return insertCommand(
              command.commandId(),
              AccountLifecycleOperation.CHANGE_PASSWORD,
              command.accountId(),
              command.expectedSecurityVersion(),
              resultVersion,
              null,
              Instant.now());
        });
  }

  @Override
  public AccountLifecycleResult disableAccount(Disable command) {
    Objects.requireNonNull(command);
    return nonSecret(
        command.commandId(),
        AccountLifecycleOperation.DISABLE_ACCOUNT,
        command.accountId(),
        command.expectedSecurityVersion(),
        command.payloadSha256(),
        () -> {
          requireBootstrapCompleted(lockGuard());
          Optional<AccountLifecycleResult> existing =
              existing(
                  command.commandId(),
                  AccountLifecycleOperation.DISABLE_ACCOUNT,
                  command.accountId(),
                  command.expectedSecurityVersion(),
                  command.payloadSha256());
          if (existing.isPresent()) return existing.get();
          AccountState state = lockRequiredAccount(command.accountId());
          requireCurrent(state, command.expectedSecurityVersion());
          requireEnabled(state);
          requireNotLastMaintainer(command.accountId(), true, Set.of());
          long resultVersion = increment(command.accountId(), null, Boolean.FALSE);
          return insertCommand(
              command.commandId(),
              AccountLifecycleOperation.DISABLE_ACCOUNT,
              command.accountId(),
              command.expectedSecurityVersion(),
              resultVersion,
              command.payloadSha256(),
              Instant.now());
        });
  }

  @Override
  public AccountLifecycleResult replacePermissions(PermissionReplacement command) {
    Objects.requireNonNull(command);
    return nonSecret(
        command.commandId(),
        AccountLifecycleOperation.REPLACE_PERMISSIONS,
        command.accountId(),
        command.expectedSecurityVersion(),
        command.payloadSha256(),
        () -> {
          requireBootstrapCompleted(lockGuard());
          Optional<AccountLifecycleResult> existing =
              existing(
                  command.commandId(),
                  AccountLifecycleOperation.REPLACE_PERMISSIONS,
                  command.accountId(),
                  command.expectedSecurityVersion(),
                  command.payloadSha256());
          if (existing.isPresent()) return existing.get();
          AccountState state = lockRequiredAccount(command.accountId());
          requireCurrent(state, command.expectedSecurityVersion());
          requireEnabled(state);
          requireNotLastMaintainer(command.accountId(), false, command.permissions());
          replacePermissions(command.accountId(), command.permissions());
          long resultVersion = increment(command.accountId(), null, null);
          return insertCommand(
              command.commandId(),
              AccountLifecycleOperation.REPLACE_PERMISSIONS,
              command.accountId(),
              command.expectedSecurityVersion(),
              resultVersion,
              command.payloadSha256(),
              Instant.now());
        });
  }

  private AccountLifecycleResult secret(UUID commandId, Mutation mutation) {
    return execute(
        () -> {
          lockGuard();
          if (command(commandId).isPresent()) {
            reject(AccountLifecycleException.Reason.SECRET_RETRY_REQUIRES_RESULT_QUERY);
          }
          return mutation.run();
        });
  }

  private AccountLifecycleResult nonSecret(
      UUID commandId,
      AccountLifecycleOperation operation,
      String accountId,
      long expectedVersion,
      byte[] fingerprint,
      Mutation mutation) {
    return execute(
        () -> {
          // The guard is deliberately acquired before this lookup. It serializes the only writer
          // principal, making same-command idempotency and last-maintainer checks deterministic.
          return mutation.run();
        });
  }

  private AccountLifecycleResult execute(Mutation mutation) {
    try {
      return Objects.requireNonNull(write.execute(status -> mutation.run()));
    } catch (AccountLifecycleException ex) {
      throw ex;
    } catch (DataAccessException | TransactionException ex) {
      throw database();
    }
  }

  private Guard lockGuard() {
    List<Guard> rows = jdbc.query(GUARD_LOCK, guardMapper());
    if (rows.size() != 1) reject(AccountLifecycleException.Reason.STORE_INVARIANT);
    return rows.getFirst();
  }

  private Optional<AccountState> lockAccountIfPresent(String accountId) {
    return jdbc
        .query(
            "SELECT enabled,security_version FROM admin_account WHERE account_id=? FOR UPDATE",
            accountMapper(),
            accountId)
        .stream()
        .findFirst();
  }

  private AccountState lockRequiredAccount(String accountId) {
    return lockAccountIfPresent(accountId)
        .orElseThrow(
            () ->
                new AccountLifecycleException(AccountLifecycleException.Reason.ACCOUNT_NOT_FOUND));
  }

  private Optional<AccountLifecycleResult> existing(
      UUID commandId,
      AccountLifecycleOperation operation,
      String accountId,
      long expectedVersion,
      byte[] fingerprint) {
    return command(commandId)
        .map(
            stored -> {
              if (stored.operation() != operation
                  || !stored.accountId().equals(accountId)
                  || !Long.valueOf(expectedVersion).equals(stored.expectedVersion())
                  || !Arrays.equals(fingerprint, stored.payloadSha256())) {
                reject(AccountLifecycleException.Reason.COMMAND_ID_REUSED);
              }
              return stored.result();
            });
  }

  private Optional<StoredCommand> command(UUID commandId) {
    return jdbc
        .query(
            "SELECT command_id,command_type,target_account_id,expected_security_version,result_security_version,payload_sha256,occurred_at "
                + "FROM admin_account_command WHERE command_id=?",
            commandMapper(),
            commandId.toString())
        .stream()
        .findFirst();
  }

  private AccountLifecycleResult insertCommand(
      UUID commandId,
      AccountLifecycleOperation operation,
      String accountId,
      Long expectedVersion,
      long resultVersion,
      byte[] fingerprint,
      Instant occurredAt) {
    jdbc.update(
        "INSERT INTO admin_account_command(command_id,command_type,target_account_id,expected_security_version,result_security_version,payload_sha256,execution_identity,occurred_at) VALUES(?,?,?,?,?,?,?,?)",
        commandId.toString(),
        operation.name(),
        accountId,
        expectedVersion,
        resultVersion,
        fingerprint,
        executionIdentity,
        utc(occurredAt));
    return new AccountLifecycleResult(commandId, operation, accountId, resultVersion, occurredAt);
  }

  private void replacePermissions(String accountId, Set<AdminPermission> permissions) {
    jdbc.update("DELETE FROM admin_account_permission WHERE account_id=?", accountId);
    for (AdminPermission permission : permissions) {
      jdbc.update(
          "INSERT INTO admin_account_permission(account_id,permission_code) VALUES(?,?)",
          accountId,
          permission.name());
    }
  }

  private long increment(String accountId, String credentialHash, Boolean enabled) {
    AccountState after = lockRequiredAccount(accountId);
    long next = Math.addExact(after.securityVersion(), 1);
    int updates;
    if (credentialHash != null) {
      updates =
          jdbc.update(
              "UPDATE admin_account SET credential_hash=?,security_version=? WHERE account_id=?",
              credentialHash,
              next,
              accountId);
    } else if (enabled != null) {
      updates =
          jdbc.update(
              "UPDATE admin_account SET enabled=?,security_version=? WHERE account_id=?",
              enabled,
              next,
              accountId);
    } else {
      updates =
          jdbc.update(
              "UPDATE admin_account SET security_version=? WHERE account_id=?", next, accountId);
    }
    if (updates != 1) reject(AccountLifecycleException.Reason.STORE_INVARIANT);
    return next;
  }

  private void requireNotLastMaintainer(
      String accountId, boolean disabling, Set<AdminPermission> replacement) {
    boolean currentlyMaintainer =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM admin_account_permission WHERE account_id=? AND permission_code='STAFF_ACCESS_MANAGE')",
                Boolean.class,
                accountId));
    boolean retains = !disabling && replacement.contains(AdminPermission.STAFF_ACCESS_MANAGE);
    if (currentlyMaintainer && !retains) {
      Long maintainers =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM admin_account a JOIN admin_account_permission p ON p.account_id=a.account_id WHERE a.enabled=TRUE AND p.permission_code='STAFF_ACCESS_MANAGE'",
              Long.class);
      if (maintainers == null || maintainers <= 1) {
        reject(AccountLifecycleException.Reason.LAST_MAINTAINER);
      }
    }
  }

  private static void requireBootstrapCompleted(Guard guard) {
    if (!guard.completed()) reject(AccountLifecycleException.Reason.BOOTSTRAP_REQUIRED);
  }

  private static void requireCurrent(AccountState state, long expectedVersion) {
    if (state.securityVersion() != expectedVersion) {
      reject(AccountLifecycleException.Reason.VERSION_CONFLICT);
    }
  }

  private static void requireEnabled(AccountState state) {
    if (!state.enabled()) reject(AccountLifecycleException.Reason.ACCOUNT_ALREADY_DISABLED);
  }

  private static TransactionTemplate transaction(
      DataSourceTransactionManager manager, boolean readOnly) {
    var template = new TransactionTemplate(manager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setReadOnly(readOnly);
    template.setTimeout(5);
    return template;
  }

  private static RowMapper<Guard> guardMapper() {
    return (row, number) -> new Guard(row.getObject("completed_at") != null);
  }

  private static RowMapper<AccountState> accountMapper() {
    return (row, number) ->
        new AccountState(row.getBoolean("enabled"), row.getLong("security_version"));
  }

  private static RowMapper<StoredCommand> commandMapper() {
    return (row, number) -> {
      try {
        AccountLifecycleOperation operation =
            AccountLifecycleOperation.valueOf(row.getString("command_type"));
        LocalDateTime time = row.getObject("occurred_at", LocalDateTime.class);
        return new StoredCommand(
            operation,
            row.getString("target_account_id"),
            row.getObject("expected_security_version", Long.class),
            row.getBytes("payload_sha256"),
            new AccountLifecycleResult(
                UUID.fromString(row.getString("command_id")),
                operation,
                row.getString("target_account_id"),
                row.getLong("result_security_version"),
                time.toInstant(ZoneOffset.UTC)));
      } catch (IllegalArgumentException | SQLException ex) {
        throw new AccountLifecycleException(AccountLifecycleException.Reason.STORE_INVARIANT);
      }
    };
  }

  private static LocalDateTime utc(Instant value) {
    return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
  }

  private static AccountCliException database() {
    return new AccountCliException(AccountCliException.Reason.DATABASE_CHECK);
  }

  private static void reject(AccountLifecycleException.Reason reason) {
    throw new AccountLifecycleException(reason);
  }

  @FunctionalInterface
  private interface Mutation {
    AccountLifecycleResult run();
  }

  private record Guard(boolean completed) {}

  private record AccountState(boolean enabled, long securityVersion) {}

  private record StoredCommand(
      AccountLifecycleOperation operation,
      String accountId,
      Long expectedVersion,
      byte[] payloadSha256,
      AccountLifecycleResult result) {}
}
