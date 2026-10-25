package cn.zhinan.adminsecurity;

import static org.assertj.core.api.Assertions.*;

import cn.zhinan.admincredentialcrypto.Argon2PasswordService;
import cn.zhinan.adminsecurity.adapter.jdbc.JdbcAdminSessionStore;
import cn.zhinan.adminsecurity.adapter.jdbc.JdbcAuthenticationEventSink;
import cn.zhinan.adminsecurity.adapter.jdbc.JdbcCredentialLookup;
import cn.zhinan.adminsecurity.application.AdminLoginService;
import cn.zhinan.adminsecurity.application.AdminSessionService;
import cn.zhinan.adminsecurity.domain.AdminAuthenticationException;
import cn.zhinan.adminsecurity.domain.AuthenticationEvent;
import cn.zhinan.adminsecurity.domain.CredentialDependencyException;
import cn.zhinan.adminsecurity.domain.PasswordMatch;
import cn.zhinan.adminsecurity.port.AdminSecurityStorageException;
import cn.zhinan.adminsecurity.port.AuthenticationEventSink;
import cn.zhinan.adminsecurity.port.PasswordVerifier;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real JDBC/KDF composition. Requires the explicitly owned instance; never migrates or skips. */
class AdminCredentialLoginIT {
  // Public independent argon2-cffi vectors reused from C1, not real account credentials.
  private static final String PASSWORD = "synthetic fixture é 👩‍💻  ";
  private static final String CURRENT =
      "$argon2id$v=19$m=65536,t=3,p=1$MDEyMzQ1Njc4OWFiY2RlZg$UK8wQh8PyocEgY7zRrNJQtvCpiKS/EbCJs3f3ZHk+Vg";
  private static final String PREVIOUS =
      "$argon2id$v=19$m=19456,t=2,p=1$MDEyMzQ1Njc4OWFiY2RlZg$NSs0yH3oMQld+x00Go1Bv5v8R/qdb5tQQ78tUAUHpyw";
  private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");
  private static DataSource source;
  private static JdbcTemplate jdbc;
  private final Argon2PasswordService crypto = new Argon2PasswordService();
  private final List<UUID> requests = new ArrayList<>();
  private String accountId;
  private JdbcAuthenticationEventSink events;

  @BeforeAll
  static void connectToOwnedFixture() {
    source = AdminDatabaseTestSupport.connect();
    jdbc = new JdbcTemplate(source);
    jdbc.setQueryTimeout(5);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='admin_auth_event'",
                Integer.class))
        .isEqualTo(5);
  }

  @BeforeEach
  void createOwnedAccount() {
    accountId = "c2-it-" + UUID.randomUUID().toString().replace("-", "");
    jdbc.update(
        "INSERT INTO admin_account(account_id,enabled,security_version,credential_hash) VALUES(?,true,7,?)",
        accountId,
        CURRENT);
    jdbc.update(
        "INSERT INTO admin_account_permission(account_id,permission_code) VALUES(?,'CONTENT_READ')",
        accountId);
    events = new JdbcAuthenticationEventSink(source);
  }

  @AfterEach
  void removeOnlyOwnedFacts() {
    for (UUID request : requests) {
      jdbc.update("DELETE FROM admin_auth_event WHERE request_id=?", request.toString());
    }
    if (accountId == null) return;
    jdbc.update("DELETE FROM admin_auth_event WHERE actor_account_id=?", accountId);
    jdbc.update("DELETE FROM admin_security_audit WHERE actor_account_id=?", accountId);
    jdbc.update("DELETE FROM admin_session WHERE account_id=?", accountId);
    jdbc.update("DELETE FROM admin_account_permission WHERE account_id=?", accountId);
    jdbc.update("DELETE FROM admin_account WHERE account_id=?", accountId);
  }

  private UUID request() {
    UUID request = UUID.randomUUID();
    requests.add(request);
    return request;
  }

  private AdminLoginService service(
      PasswordVerifier verifier, AuthenticationEventSink sink, DataSource storeSource) {
    var sessions =
        new AdminSessionService(
            new JdbcAdminSessionStore(storeSource),
            new TestSessionTokenCodec(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofHours(1));
    return new AdminLoginService(
        new JdbcCredentialLookup(source),
        verifier,
        sessions,
        sink,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private AdminLoginService service() {
    return service(crypto, events, source);
  }

  private AdminSessionService.IssuedSession attempt(
      AdminLoginService login, String id, String password, UUID request) {
    char[] supplied = password.toCharArray();
    try {
      return login.login(id, supplied, request);
    } finally {
      Arrays.fill(supplied, '\0');
    }
  }

  private int count(String table) {
    if (!List.of("admin_session", "admin_security_audit", "admin_auth_event").contains(table))
      throw new IllegalArgumentException();
    String key = table.equals("admin_session") ? "account_id" : "actor_account_id";
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE " + key + "=?", Integer.class, accountId);
  }

  @Test
  void currentRealKdfPreservesWhitespaceAndPersistsOneSessionWithAtomicAudit() {
    var issued = attempt(service(), accountId, PASSWORD, request());
    assertThat(issued.token()).matches("[A-Za-z0-9_-]{43}");
    assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(3600));
    assertThat(count("admin_session")).isEqualTo(1);
    assertThat(count("admin_security_audit")).isEqualTo(1);
    assertThat(count("admin_auth_event")).isZero();
    assertThatThrownBy(() -> attempt(service(), accountId, PASSWORD.stripTrailing(), request()))
        .isInstanceOf(AdminAuthenticationException.class);
    assertThat(count("admin_session")).isEqualTo(1);
    assertThat(count("admin_auth_event")).isEqualTo(1);
  }

  @Test
  void wrongAbsentAndDisabledAccountsDenyWithoutIssuanceAndRecordDurableEvents() {
    var login = service();
    assertThatThrownBy(() -> attempt(login, accountId, "wrong", request()))
        .isInstanceOf(AdminAuthenticationException.class);
    UUID absentRequest = request();
    assertThatThrownBy(() -> attempt(login, accountId + "_absent", PASSWORD, absentRequest))
        .isInstanceOf(AdminAuthenticationException.class);
    jdbc.update("UPDATE admin_account SET enabled=false WHERE account_id=?", accountId);
    assertThatThrownBy(() -> attempt(login, accountId, PASSWORD, request()))
        .isInstanceOf(AdminAuthenticationException.class);
    assertThat(count("admin_session")).isZero();
    assertThat(count("admin_security_audit")).isZero();
    assertThat(count("admin_auth_event")).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT actor_account_id FROM admin_auth_event WHERE request_id=?",
                String.class,
                absentRequest.toString()))
        .isNull();
  }

  @Test
  void oldProfileEventIsCommittedBeforeTheSessionInsertAndDoesNotRewriteCredential() {
    jdbc.update(
        "UPDATE admin_account SET credential_hash=? WHERE account_id=?", PREVIOUS, accountId);
    UUID request = request();
    var observed =
        observedSource(
            sql -> {
              if (sql.startsWith("INSERT INTO admin_session")) {
                assertThat(
                        jdbc.queryForObject(
                            "SELECT COUNT(*) FROM admin_auth_event WHERE request_id=? AND event_type='CREDENTIAL_UPGRADE_REQUIRED'",
                            Integer.class,
                            request.toString()))
                    .isEqualTo(1);
              }
            });
    attempt(service(crypto, events, observed), accountId, PASSWORD, request);
    assertThat(count("admin_session")).isEqualTo(1);
    assertThat(count("admin_security_audit")).isEqualTo(1);
    assertThat(count("admin_auth_event")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT credential_hash FROM admin_account WHERE account_id=?",
                String.class,
                accountId))
        .isEqualTo(PREVIOUS);
  }

  @Test
  void upgradeEventFailurePreventsAnySessionInsert() {
    jdbc.update(
        "UPDATE admin_account SET credential_hash=? WHERE account_id=?", PREVIOUS, accountId);
    var inserts = new AtomicInteger();
    var storeSource =
        observedSource(
            sql -> {
              if (sql.startsWith("INSERT INTO admin_session")) inserts.incrementAndGet();
            });
    var failingEvents =
        new JdbcAuthenticationEventSink(
            observedSource(
                sql -> {
                  if (sql.startsWith("INSERT INTO admin_auth_event"))
                    throw new SQLException("SYNTHETIC_EVENT_FAILURE");
                }));
    assertThatThrownBy(
            () ->
                attempt(
                    service(crypto, failingEvents, storeSource), accountId, PASSWORD, request()))
        .isInstanceOf(AdminSecurityStorageException.class);
    assertThat(inserts).hasValue(0);
    assertThat(count("admin_session")).isZero();
    assertThat(count("admin_security_audit")).isZero();
    assertThat(count("admin_auth_event")).isZero();
  }

  @Test
  void invalidStoredHashIsDependencyFailureNotDenialOrIssuance() {
    jdbc.update(
        "UPDATE admin_account SET credential_hash='INVALID_SYNTHETIC_PHC' WHERE account_id=?",
        accountId);
    assertThatThrownBy(() -> attempt(service(), accountId, PASSWORD, request()))
        .isInstanceOf(CredentialDependencyException.class)
        .isNotInstanceOf(AdminAuthenticationException.class);
    assertThat(count("admin_session")).isZero();
    assertThat(count("admin_security_audit")).isZero();
    assertThat(count("admin_auth_event")).isZero();
  }

  @Test
  void successAuditSqlFailureRollsBackTheRealSessionTransaction() {
    var failedStore =
        observedSource(
            sql -> {
              if (sql.startsWith("INSERT INTO admin_security_audit"))
                throw new SQLException("SYNTHETIC_AUDIT_FAILURE");
            });
    assertThatThrownBy(
            () -> attempt(service(crypto, events, failedStore), accountId, PASSWORD, request()))
        .isInstanceOf(AdminSecurityStorageException.class);
    assertThat(count("admin_session")).isZero();
    assertThat(count("admin_security_audit")).isZero();
  }

  @Test
  void credentialReadSuspendsTheOuterSnapshotAndRemainsCaseSensitive() {
    var credentials = new JdbcCredentialLookup(source);
    assertThat(credentials.find(accountId.toUpperCase(Locale.ROOT))).isEmpty();
    var outer = new TransactionTemplate(new DataSourceTransactionManager(source));
    outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    outer.setReadOnly(true);
    outer.setTimeout(8);
    outer.executeWithoutResult(
        status -> {
          assertThat(
                  jdbc.queryForObject(
                      "SELECT security_version FROM admin_account WHERE account_id=?",
                      Long.class,
                      accountId))
              .isEqualTo(7L);
          var executor = Executors.newSingleThreadExecutor();
          try {
            executor
                .submit(
                    () ->
                        jdbc.update(
                            "UPDATE admin_account SET security_version=8 WHERE account_id=?",
                            accountId))
                .get(5, TimeUnit.SECONDS);
            assertThat(credentials.find(accountId).orElseThrow().securityVersion()).isEqualTo(8);
            assertThat(
                    jdbc.queryForObject(
                        "SELECT security_version FROM admin_account WHERE account_id=?",
                        Long.class,
                        accountId))
                .isEqualTo(7L);
            status.setRollbackOnly();
          } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AssertionError("Independent snapshot fixture failed", ex);
          } finally {
            stopExecutor(executor);
          }
        });
  }

  @Test
  void resetBetweenRealKdfAndIssuanceDeniesWithoutHoldingCredentialLocks() throws Exception {
    raceAfterKdf(false);
  }

  @Test
  void disableBetweenRealKdfAndIssuanceDeniesWithoutHoldingCredentialLocks() throws Exception {
    raceAfterKdf(true);
  }

  private void raceAfterKdf(boolean disable) throws Exception {
    var verified = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var barrierVerifier =
        new PasswordVerifier() {
          @Override
          public PasswordMatch verify(char[] supplied, String hash) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            PasswordMatch match = crypto.verify(supplied, hash);
            verified.countDown();
            try {
              if (!release.await(6, TimeUnit.SECONDS))
                throw new AssertionError("KDF barrier timed out");
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
              throw new AssertionError(ex);
            }
            return match;
          }

          @Override
          public void consumeDummyCost(char[] supplied) {
            crypto.consumeDummyCost(supplied);
          }
        };
    UUID request = request();
    var login = service(barrierVerifier, events, source);
    var executor = Executors.newSingleThreadExecutor();
    try {
      var future = executor.submit(() -> attempt(login, accountId, PASSWORD, request));
      assertThat(verified.await(5, TimeUnit.SECONDS)).isTrue();
      // This must finish BEFORE releasing the KDF barrier: no credential-read lock remains held.
      String replacement = CURRENT;
      if (!disable) {
        char[] changed = "synthetic replacement, not a real credential".toCharArray();
        try {
          replacement = crypto.hash(changed);
        } finally {
          Arrays.fill(changed, '\0');
        }
      }
      jdbc.update(
          "UPDATE admin_account SET security_version=8,enabled=?,credential_hash=? WHERE account_id=?",
          !disable,
          replacement,
          accountId);
      release.countDown();
      assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(AdminAuthenticationException.class);
      assertThat(count("admin_session")).isZero();
      assertThat(count("admin_security_audit")).isZero();
      assertThat(count("admin_auth_event")).isEqualTo(1);
    } finally {
      release.countDown();
      stopExecutor(executor);
    }
  }

  @Test
  void authenticationEventIsUtcMicrosecondAndSurvivesOuterRollback() {
    UUID request = request();
    Instant at = NOW.plusNanos(987654321);
    var outer = new TransactionTemplate(new DataSourceTransactionManager(source));
    outer.setReadOnly(true);
    outer.executeWithoutResult(
        status -> {
          events.append(
              new AuthenticationEvent(
                  AuthenticationEvent.Kind.LOGIN_DENIED, accountId, request, at));
          status.setRollbackOnly();
        });
    var stored =
        jdbc.queryForObject(
            "SELECT occurred_at FROM admin_auth_event WHERE request_id=?",
            (row, index) -> row.getObject(1, LocalDateTime.class),
            request.toString());
    assertThat(stored)
        .isEqualTo(LocalDateTime.ofInstant(at.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC));
    assertThat(count("admin_auth_event")).isEqualTo(1);
  }

  private static void stopExecutor(ExecutorService executor) {
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(6, TimeUnit.SECONDS))
        throw new AssertionError("Owned test worker did not terminate");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Test cleanup interrupted", ex);
    }
  }

  @FunctionalInterface
  private interface SqlObserver {
    void beforePrepare(String sql) throws SQLException;
  }

  /** SQL fault/order hook only; successful work still runs on the real owned MySQL instance. */
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
