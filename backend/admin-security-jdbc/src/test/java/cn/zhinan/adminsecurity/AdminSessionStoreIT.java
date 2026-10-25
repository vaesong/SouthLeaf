package cn.zhinan.adminsecurity;

import static cn.zhinan.adminsecurity.domain.AdminPermission.*;
import static org.assertj.core.api.Assertions.*;

import cn.zhinan.adminsecurity.adapter.jdbc.JdbcAdminSessionStore;
import cn.zhinan.adminsecurity.adapter.jdbc.JdbcAuthenticationEventSink;
import cn.zhinan.adminsecurity.adapter.jdbc.JdbcCredentialLookup;
import cn.zhinan.adminsecurity.application.AdminSessionService;
import cn.zhinan.adminsecurity.domain.*;
import cn.zhinan.adminsecurity.domain.AuthenticationEvent;
import cn.zhinan.adminsecurity.port.AdminSecurityStorageException;
import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Real MySQL transactions. Strictly isolated schema, test-owned row cleanup, no H2 or skips. */
class AdminSessionStoreIT {
  private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");
  private static DataSource source;
  private static JdbcTemplate jdbc;
  private String accountId;
  private JdbcAdminSessionStore store;
  private JdbcCredentialLookup credentials;
  private JdbcAuthenticationEventSink events;
  private final TestSessionTokenCodec tokens = new TestSessionTokenCodec();

  @BeforeAll
  static void prepareIsolatedSchema() {
    source = AdminDatabaseTestSupport.connect();
    jdbc = new JdbcTemplate(source);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='admin_auth_event'",
                Integer.class))
        .isEqualTo(5);
  }

  @BeforeEach
  void createOwnedFixture() {
    accountId = "it-admin-" + UUID.randomUUID().toString().replace("-", "");
    jdbc.update(
        "INSERT INTO admin_account(account_id,enabled,security_version,credential_hash) VALUES(?,true,7,?)",
        accountId,
        "!TEST_ONLY_UNUSABLE!");
    jdbc.update(
        "INSERT INTO admin_account_permission(account_id,permission_code) VALUES(?,'CONTENT_READ')",
        accountId);
    store = new JdbcAdminSessionStore(source);
    credentials = new JdbcCredentialLookup(source);
    events = new JdbcAuthenticationEventSink(source);
  }

  @AfterEach
  void cleanOnlyOwnedRows() {
    if (accountId == null) return;
    jdbc.update("DELETE FROM admin_auth_event WHERE actor_account_id=?", accountId);
    jdbc.update("DELETE FROM admin_security_audit WHERE actor_account_id=?", accountId);
    jdbc.update("DELETE FROM admin_session WHERE account_id=?", accountId);
    jdbc.update("DELETE FROM admin_account_permission WHERE account_id=?", accountId);
    jdbc.update("DELETE FROM admin_account WHERE account_id=?", accountId);
  }

  private AdminSession session(String digest) {
    return new AdminSession(digest, accountId, 7, NOW, NOW.plusSeconds(3600));
  }

  private String digest() {
    return tokens.digest(tokens.generate());
  }

  private int count(String table) {
    if (!Set.of("admin_session", "admin_security_audit", "admin_auth_event").contains(table))
      throw new IllegalArgumentException();
    String column = table.equals("admin_session") ? "account_id" : "actor_account_id";
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", Integer.class, accountId);
  }

  private AdminSessionService service(JdbcAdminSessionStore adapter, Instant now) {
    return new AdminSessionService(
        adapter, tokens, Clock.fixed(now, ZoneOffset.UTC), Duration.ofHours(1));
  }

  @Test
  void isolatedV3AuthEventSchemaExistsWithoutPublicMigrationHistory() {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='admin_auth_event'",
                Integer.class))
        .isEqualTo(5);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='flyway_schema_history'",
                Integer.class))
        .isZero();
  }

  @Test
  void credentialLookupIsFreshBoundedAndNeverTreatsDatabaseFailureAsAbsence() {
    var original = credentials.find(accountId).orElseThrow();
    assertThat(original.enabled()).isTrue();
    assertThat(original.securityVersion()).isEqualTo(7);
    assertThat(original.credentialHash()).isEqualTo("!TEST_ONLY_UNUSABLE!");
    assertThat(credentials.find(accountId + "_missing")).isEmpty();
    jdbc.update(
        "UPDATE admin_account SET enabled=false,security_version=8 WHERE account_id=?", accountId);
    var fresh = credentials.find(accountId).orElseThrow();
    assertThat(fresh.enabled()).isFalse();
    assertThat(fresh.securityVersion()).isEqualTo(8);
    assertThat(original.securityVersion()).isEqualTo(7);
  }

  @Test
  void authEventAppendIsRestrictedAndIndependentOfAmbientRollback() {
    var event =
        new AuthenticationEvent(
            AuthenticationEvent.Kind.LOGIN_DENIED,
            accountId,
            UUID.fromString("a09d776f-93d7-4a5a-9aa1-456f968791a2"),
            NOW.plusNanos(123456));
    var outer =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
    outer.setReadOnly(true);
    outer.executeWithoutResult(
        status -> {
          events.append(event);
          status.setRollbackOnly();
        });
    var row =
        jdbc.queryForMap(
            "SELECT * FROM admin_auth_event WHERE request_id=?", event.requestId().toString());
    assertThat(row.get("event_type")).isEqualTo("LOGIN_DENIED");
    assertThat(row.get("actor_account_id")).isEqualTo(accountId);
    assertThat(row.get("occurred_at").toString()).contains("2026-10-18T08:00:00");
    assertThat(row.keySet())
        .containsExactlyInAnyOrder(
            "event_id", "occurred_at", "event_type", "actor_account_id", "request_id");
    assertThat(row.values().toString()).doesNotContain("secret", "hash", "password");
    assertThat(count("admin_auth_event")).isEqualTo(1);
  }

  @Test
  void credentialReadAndEventWriteFailuresRemainInfrastructureFailures() {
    var failingLookup =
        new JdbcCredentialLookup(
            observedSource(
                sql -> {
                  if (sql.startsWith("SELECT account_id,enabled,security_version,credential_hash"))
                    throw new SQLException("FAKE_SECRET_SENTINEL");
                }));
    assertThatThrownBy(() -> failingLookup.find(accountId))
        .isInstanceOf(AdminSecurityStorageException.class)
        .hasMessage("Administrator security storage unavailable");
    int before = count("admin_auth_event");
    var failingEvents =
        new JdbcAuthenticationEventSink(
            observedSource(
                sql -> {
                  if (sql.startsWith("INSERT INTO admin_auth_event"))
                    throw new SQLException("FAKE_SECRET_SENTINEL");
                }));
    var event =
        new AuthenticationEvent(
            AuthenticationEvent.Kind.LOGIN_DENIED, accountId, UUID.randomUUID(), NOW);
    assertThatThrownBy(() -> failingEvents.append(event))
        .isInstanceOf(AdminSecurityStorageException.class)
        .hasMessage("Administrator security storage unavailable");
    assertThat(count("admin_auth_event")).isEqualTo(before);
  }

  @Test
  void eventTableRejectsUnknownTypesAndUpgradeWithoutKnownActor() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO admin_auth_event(event_id,occurred_at,event_type,actor_account_id,request_id) VALUES(?,?,?,?,?)",
                    UUID.randomUUID().toString(),
                    NOW,
                    "UNKNOWN",
                    accountId,
                    UUID.randomUUID().toString()))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO admin_auth_event(event_id,occurred_at,event_type,actor_account_id,request_id) VALUES(?,?,?,?,?)",
                    UUID.randomUUID().toString(),
                    NOW,
                    "CREDENTIAL_UPGRADE_REQUIRED",
                    null,
                    UUID.randomUUID().toString()))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void createPersistsDigestAndAuditWithoutSecretMaterial() {
    String raw = tokens.generate();
    assertThat(store.createIfAccountCurrent(session(tokens.digest(raw)))).isTrue();
    var state = store.findCurrent(tokens.digest(raw)).orElseThrow();
    assertThat(state.session()).isEqualTo(session(tokens.digest(raw)));
    assertThat(state.account().permissions()).containsExactly(CONTENT_READ);
    var audit =
        jdbc.queryForMap("SELECT * FROM admin_security_audit WHERE actor_account_id=?", accountId);
    assertThat(audit.get("action")).isEqualTo("ADMIN_SESSION_OPENED");
    assertThat(audit.values().toString()).doesNotContain(raw, tokens.digest(raw));
    assertThat(
            jdbc.queryForObject(
                "SELECT session_id FROM admin_session WHERE account_id=?", String.class, accountId))
        .isEqualTo(audit.get("target_session_id"));
  }

  @Test
  void unknownDisabledAndChangedAccountsDoNotCreateRows() {
    var absent = new AdminSession(digest(), "it-admin-absent", 7, NOW, NOW.plusSeconds(60));
    assertThat(store.createIfAccountCurrent(absent)).isFalse();
    jdbc.update("UPDATE admin_account SET enabled=false WHERE account_id=?", accountId);
    assertThat(store.createIfAccountCurrent(session(digest()))).isFalse();
    jdbc.update(
        "UPDATE admin_account SET enabled=true,security_version=8 WHERE account_id=?", accountId);
    assertThat(store.createIfAccountCurrent(session(digest()))).isFalse();
    assertThat(count("admin_session")).isZero();
    assertThat(count("admin_security_audit")).isZero();
  }

  @Test
  void duplicateDigestCannotOverwriteOrDuplicateAudit() {
    String digest = digest();
    assertThat(store.createIfAccountCurrent(session(digest))).isTrue();
    assertThatThrownBy(() -> store.createIfAccountCurrent(session(digest)))
        .isInstanceOf(AdminSecurityStorageException.class);
    assertThat(count("admin_session")).isEqualTo(1);
    assertThat(count("admin_security_audit")).isEqualTo(1);
    assertThat(store.findCurrent(digest)).isPresent();
  }

  @Test
  void revokeIsTargetedIdempotentAndAuditedOnce() {
    String first = digest(), second = digest();
    store.createIfAccountCurrent(session(first));
    store.createIfAccountCurrent(session(second));
    store.revoke(first, NOW.plusSeconds(10));
    store.revoke(first, NOW.plusSeconds(11));
    store.revoke(digest(), NOW.plusSeconds(11));
    assertThat(store.findCurrent(first)).isEmpty();
    assertThat(store.findCurrent(second)).isPresent();
    assertThat(count("admin_security_audit")).isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT revoked_at FROM admin_session WHERE token_digest=?",
                LocalDateTime.class,
                first))
        .isEqualTo(LocalDateTime.ofInstant(NOW.plusSeconds(10), ZoneOffset.UTC));
  }

  @Test
  void auditFailureRollsBackCreationInRealDatabase() {
    var failing =
        new JdbcAdminSessionStore(
            observedSource(
                sql -> {
                  if (sql.startsWith("INSERT INTO admin_security_audit"))
                    throw new SQLException("Injected audit failure");
                }));
    assertThatThrownBy(() -> failing.createIfAccountCurrent(session(digest())))
        .isInstanceOf(AdminSecurityStorageException.class);
    assertThat(count("admin_session")).isZero();
    assertThat(count("admin_security_audit")).isZero();
  }

  @Test
  void auditFailureRollsBackRevocationInRealDatabase() {
    String digest = digest();
    store.createIfAccountCurrent(session(digest));
    var failing =
        new JdbcAdminSessionStore(
            observedSource(
                sql -> {
                  if (sql.startsWith("INSERT INTO admin_security_audit"))
                    throw new SQLException("Injected audit failure");
                }));
    assertThatThrownBy(() -> failing.revoke(digest, NOW.plusSeconds(1)))
        .isInstanceOf(AdminSecurityStorageException.class);
    assertThat(store.findCurrent(digest)).isPresent();
    assertThat(count("admin_security_audit")).isEqualTo(1);
  }

  @Test
  void freshAccountAndPermissionsAreVisibleAcrossStoreInstances() {
    var issued = service(store, NOW).openForVerifiedCredentials(accountId, 7);
    var restarted = service(new JdbcAdminSessionStore(source), NOW);
    assertThat(restarted.authenticate(issued.token()).permissions()).containsExactly(CONTENT_READ);
    jdbc.update("DELETE FROM admin_account_permission WHERE account_id=?", accountId);
    assertThat(restarted.authenticate(issued.token()).permissions()).isEmpty();
    jdbc.update("UPDATE admin_account SET security_version=8 WHERE account_id=?", accountId);
    assertThatThrownBy(() -> restarted.authenticate(issued.token()))
        .isInstanceOf(AdminAuthenticationException.class);
  }

  @Test
  void disabledAccountInvalidatesPersistentSession() {
    var issued = service(store, NOW).openForVerifiedCredentials(accountId, 7);
    jdbc.update("UPDATE admin_account SET enabled=false WHERE account_id=?", accountId);
    assertThatThrownBy(() -> service(store, NOW).authenticate(issued.token()))
        .isInstanceOf(AdminAuthenticationException.class);
  }

  @Test
  void microsecondDeadlineMatchesReceiptAndExactBoundary() {
    Instant now = NOW.plusNanos(123456789);
    var issued = service(store, now).openForVerifiedCredentials(accountId, 7);
    var persisted = store.findCurrent(tokens.digest(issued.token())).orElseThrow().session();
    assertThat(persisted.issuedAt()).isEqualTo(NOW.plusNanos(123456000));
    assertThat(persisted.expiresAt()).isEqualTo(issued.expiresAt());
    assertThat(service(store, issued.expiresAt().minusNanos(1)).authenticate(issued.token()))
        .isNotNull();
    assertThatThrownBy(() -> service(store, issued.expiresAt()).authenticate(issued.token()))
        .isInstanceOf(AdminAuthenticationException.class);
  }

  @Test
  void unknownDigestIsEmptyAndRawTokenRejectedBeforeJdbc() {
    assertThat(store.findCurrent(digest())).isEmpty();
    assertThatThrownBy(() -> store.findCurrent(tokens.generate()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.revoke(tokens.generate(), NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(count("admin_security_audit")).isZero();
  }

  @Test
  void databaseConstraintsRejectUnknownPermissionAndInvalidVersion() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO admin_account_permission(account_id,permission_code) VALUES(?,'SUPERUSER')",
                    accountId))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE admin_account SET security_version=-1 WHERE account_id=?", accountId))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void databaseFailureNeverBecomesMissingOrSuccessfulRevoke() {
    var failing =
        new JdbcAdminSessionStore(
            observedSource(
                sql -> {
                  throw new SQLException("Injected dependency failure");
                }));
    assertThatThrownBy(() -> failing.findCurrent(digest()))
        .isInstanceOf(AdminSecurityStorageException.class);
    assertThatThrownBy(() -> failing.revoke(digest(), NOW))
        .isInstanceOf(AdminSecurityStorageException.class);
    assertThatThrownBy(() -> failing.createIfAccountCurrent(session(digest())))
        .isInstanceOf(AdminSecurityStorageException.class);
  }

  @Test
  void concurrentRevokeCreatesExactlyOneRevocationAudit() throws Exception {
    String digest = digest();
    store.createIfAccountCurrent(session(digest));
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Void> revoke =
          () -> {
            await(start);
            new JdbcAdminSessionStore(source).revoke(digest, NOW.plusSeconds(1));
            return null;
          };
      var first = pool.submit(revoke);
      var second = pool.submit(revoke);
      start.countDown();
      first.get(8, TimeUnit.SECONDS);
      second.get(8, TimeUnit.SECONDS);
    } finally {
      start.countDown();
    }
    assertThat(store.findCurrent(digest)).isEmpty();
    assertThat(count("admin_security_audit")).isEqualTo(2);
  }

  @Test
  void resetHoldingAccountLockRejectsOldVerifiedVersionAfterCommit() throws Exception {
    mutationHoldingAccountLockRejectsIssueAfterCommit("security_version=8");
  }

  @Test
  void disableHoldingAccountLockRejectsIssueAfterCommit() throws Exception {
    mutationHoldingAccountLockRejectsIssueAfterCommit("enabled=false");
  }

  private void mutationHoldingAccountLockRejectsIssueAfterCommit(String assignment)
      throws Exception {
    String digest = digest();
    var attempting = new CountDownLatch(1);
    var observed =
        new JdbcAdminSessionStore(
            observedSource(
                sql -> {
                  if (sql.contains("FROM admin_account") && sql.endsWith("FOR SHARE"))
                    attempting.countDown();
                }));
    try (Connection lock = source.getConnection();
        var pool = Executors.newSingleThreadExecutor()) {
      lock.setAutoCommit(false);
      try {
        try (var statement =
            lock.prepareStatement(
                "SELECT account_id FROM admin_account WHERE account_id=? FOR UPDATE")) {
          statement.setString(1, accountId);
          try (var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
          }
        }
        var creation = pool.submit(() -> observed.createIfAccountCurrent(session(digest)));
        await(attempting);
        assertThatThrownBy(() -> creation.get(150, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
        try (var update =
            lock.prepareStatement(
                "UPDATE admin_account SET " + assignment + " WHERE account_id=?")) {
          update.setString(1, accountId);
          update.executeUpdate();
        }
        lock.commit();
        assertThat(creation.get(8, TimeUnit.SECONDS)).isFalse();
      } finally {
        lock.rollback();
      }
    }
    assertThat(count("admin_session")).isZero();
    assertThat(count("admin_security_audit")).isZero();
  }

  @Test
  void creationHoldingAccountLockCommitsThenResetInvalidatesIt() throws Exception {
    creationHoldingAccountLockBlocksMutationUntilAuditCommit("security_version=8");
  }

  @Test
  void creationHoldingSharedLockBlocksDisableUntilAuditCommit() throws Exception {
    creationHoldingAccountLockBlocksMutationUntilAuditCommit("enabled=false");
  }

  private void creationHoldingAccountLockBlocksMutationUntilAuditCommit(String assignment)
      throws Exception {
    String digest = digest();
    var inside = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var resetAttempt = new CountDownLatch(1);
    var observed =
        new JdbcAdminSessionStore(
            observedSource(
                sql -> {
                  if (sql.startsWith("INSERT INTO admin_security_audit")) {
                    inside.countDown();
                    await(release);
                  }
                }));
    try (var pool = Executors.newFixedThreadPool(2)) {
      try {
        var creation = pool.submit(() -> observed.createIfAccountCurrent(session(digest)));
        await(inside);
        var reset =
            pool.submit(
                () -> {
                  resetAttempt.countDown();
                  return jdbc.update(
                      "UPDATE admin_account SET " + assignment + " WHERE account_id=?", accountId);
                });
        await(resetAttempt);
        assertThatThrownBy(() -> reset.get(150, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
        release.countDown();
        assertThat(creation.get(8, TimeUnit.SECONDS)).isTrue();
        assertThat(reset.get(8, TimeUnit.SECONDS)).isEqualTo(1);
      } finally {
        release.countDown();
      }
    }
    var state = store.findCurrent(digest).orElseThrow();
    assertThat(state.session().activeFor(state.account(), NOW)).isFalse();
    assertThat(count("admin_security_audit")).isEqualTo(1);
  }

  @Test
  void concurrentIssuanceSharesAccountLockAndCommitsIndependentAudits() throws Exception {
    String firstDigest = digest(), secondDigest = digest();
    var bothHoldAccount = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var observed =
        new JdbcAdminSessionStore(
            observedSource(
                sql -> {
                  if (sql.startsWith("INSERT INTO admin_session")) {
                    bothHoldAccount.countDown();
                    await(release);
                  }
                }));
    try (var pool = Executors.newFixedThreadPool(2)) {
      try {
        var first = pool.submit(() -> observed.createIfAccountCurrent(session(firstDigest)));
        var second = pool.submit(() -> observed.createIfAccountCurrent(session(secondDigest)));
        // A return to exclusive account locking cannot pass this barrier.
        assertThat(bothHoldAccount.await(3, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        assertThat(first.get(8, TimeUnit.SECONDS)).isTrue();
        assertThat(second.get(8, TimeUnit.SECONDS)).isTrue();
      } finally {
        release.countDown();
      }
    }
    assertThat(store.findCurrent(firstDigest)).isPresent();
    assertThat(store.findCurrent(secondDigest)).isPresent();
    assertThat(count("admin_session")).isEqualTo(2);
    assertThat(count("admin_security_audit")).isEqualTo(2);
  }

  @Test
  void readingSeveralPermissionsUsesOneConsistentSelect() {
    String digest = digest();
    store.createIfAccountCurrent(session(digest));
    for (String code : List.of("CONTENT_EDIT", "CONTENT_PUBLISH", "AUDIT_READ")) {
      jdbc.update(
          "INSERT INTO admin_account_permission(account_id,permission_code) VALUES(?,?)",
          accountId,
          code);
    }
    var selects = new java.util.concurrent.atomic.AtomicInteger();
    var observed =
        new JdbcAdminSessionStore(
            observedSource(
                sql -> {
                  if (sql.startsWith("SELECT")) selects.incrementAndGet();
                }));
    assertThat(observed.findCurrent(digest).orElseThrow().account().permissions())
        .containsExactlyInAnyOrder(CONTENT_READ, CONTENT_EDIT, CONTENT_PUBLISH, AUDIT_READ);
    assertThat(selects.get()).isEqualTo(1);
  }

  @Test
  void ambientReadOnlyTransactionDoesNotDowngradeIndependentSecurityWrite() {
    var outer =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
    outer.setReadOnly(true);
    String digest = digest();
    outer.executeWithoutResult(
        status -> {
          assertThat(store.createIfAccountCurrent(session(digest))).isTrue();
          status.setRollbackOnly();
        });
    assertThat(store.findCurrent(digest)).isPresent();
    assertThat(count("admin_security_audit")).isEqualTo(1);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(6, TimeUnit.SECONDS))
        throw new IllegalStateException("Test barrier timed out");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Test interrupted", ex);
    }
  }

  @FunctionalInterface
  private interface SqlObserver {
    void beforePrepare(String sql) throws SQLException;
  }

  /** Test-only fault/barrier hook; all successful statements use real MySQL connections. */
  private static DataSource observedSource(SqlObserver observer) {
    return new AbstractDataSource() {
      @Override
      public Connection getConnection() throws SQLException {
        return wrap(source.getConnection());
      }

      @Override
      public Connection getConnection(String username, String password) throws SQLException {
        return wrap(source.getConnection(username, password));
      }

      private Connection wrap(Connection delegate) {
        return (Connection)
            Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("prepareStatement")
                      && args != null
                      && args[0] instanceof String sql) observer.beforePrepare(sql);
                  try {
                    return method.invoke(delegate, args);
                  } catch (InvocationTargetException ex) {
                    throw ex.getCause();
                  }
                });
      }
    };
  }
}
