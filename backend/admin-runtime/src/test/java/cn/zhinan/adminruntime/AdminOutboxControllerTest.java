package cn.zhinan.adminruntime;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhinan.adminbooking.application.OutboxOperations;
import cn.zhinan.adminbooking.port.BookingPorts;
import cn.zhinan.adminbooking.port.BookingPorts.OutboxMessage;
import cn.zhinan.adminsecurity.application.AdminSessionService;
import cn.zhinan.adminsecurity.domain.AdminAccount;
import cn.zhinan.adminsecurity.domain.AdminPermission;
import cn.zhinan.adminsecurity.domain.AdminSession;
import cn.zhinan.adminsecurity.port.AdminSessionStore;
import cn.zhinan.adminsecurity.port.SessionTokenCodec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AdminOutboxControllerTest {
  private static final String TOKEN = "T".repeat(43);
  private static final String MESSAGE = "00000000-0000-0000-0000-000000000001";
  private static final String IDEMPOTENCY_KEY = "a".repeat(32);
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneOffset.UTC);

  @Test
  void listsOnlyMinimizedFailureFieldsForAnAuthenticatedReader() {
    var fixture = new Fixture(Set.of(AdminPermission.OUTBOX_READ));
    var response = fixture.controller.list(request("GET", "/api/admin/v1/notifications/outbox"));

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(response.getHeaders().getFirst("X-Request-Id")).matches("[0-9a-f-]{36}");
    var envelope = (AdminOutboxController.Envelope<?>) response.getBody();
    var board = (AdminOutboxController.BoardView) envelope.data();
    assertThat(board.deliveryMode()).isEqualTo("TEST_SINK");
    assertThat(board.externalDeliveryEnabled()).isFalse();
    assertThat(board.pendingCount()).isEqualTo(2);
    assertThat(board.failures()).hasSize(1);
    var failure = board.failures().getFirst();
    assertThat(failure.id()).isEqualTo(MESSAGE);
    assertThat(failure.lastError()).isEqualTo("UNKNOWN");
    assertThat(failure.toString())
        .doesNotContain("private-recipient", "private-object", "provider response body");
  }

  @Test
  void requiresASessionAndTheExactReadCapabilityBeforeTouchingTheOutbox() {
    var noSession = new Fixture(Set.of(AdminPermission.OUTBOX_READ));
    assertThat(
            noSession
                .controller
                .list(new MockHttpServletRequest("GET", "/outbox"))
                .getStatusCode()
                .value())
        .isEqualTo(401);
    assertThat(noSession.worker.readCalls).hasValue(0);

    var wrongCapability = new Fixture(Set.of(AdminPermission.OUTBOX_RECOVER));
    var response =
        wrongCapability.controller.list(request("GET", "/api/admin/v1/notifications/outbox"));
    assertThat(response.getStatusCode().value()).isEqualTo(403);
    assertThat(wrongCapability.worker.readCalls).hasValue(0);
  }

  @Test
  void recoveryIsAnAuditedIdempotentCommandAndDoesNotResetAttemptCount() {
    var fixture = new Fixture(Set.of(AdminPermission.OUTBOX_RECOVER));
    var first = fixture.controller.recover(recoveryRequest(MESSAGE, IDEMPOTENCY_KEY), MESSAGE);
    assertThat(first.getStatusCode().value()).isEqualTo(200);
    var firstBody = (AdminOutboxController.Envelope<?>) first.getBody();
    var firstResult = (AdminOutboxController.RecoveryView) firstBody.data();
    assertThat(firstResult.status()).isEqualTo("QUEUED");
    assertThat(firstResult.replayed()).isFalse();
    assertThat(fixture.worker.retryCalls).hasValue(1);
    assertThat(fixture.audit.calls).hasValue(1);
    assertThat(fixture.ledger.commands).hasSize(1);

    var replay = fixture.controller.recover(recoveryRequest(MESSAGE, IDEMPOTENCY_KEY), MESSAGE);
    var replayResult =
        (AdminOutboxController.RecoveryView)
            ((AdminOutboxController.Envelope<?>) replay.getBody()).data();
    assertThat(replay.getStatusCode().value()).isEqualTo(200);
    assertThat(replayResult.replayed()).isTrue();
    assertThat(fixture.worker.retryCalls).hasValue(1);
    assertThat(fixture.audit.calls).hasValue(1);

    String otherMessage = "00000000-0000-0000-0000-000000000002";
    var conflict =
        fixture.controller.recover(recoveryRequest(otherMessage, IDEMPOTENCY_KEY), otherMessage);
    assertThat(conflict.getStatusCode().value()).isEqualTo(409);
    assertThat(((AdminOutboxController.ErrorEnvelope) conflict.getBody()).code())
        .isEqualTo("IDEMPOTENCY_KEY_REUSE");
    assertThat(fixture.worker.retryCalls).hasValue(1);
  }

  @Test
  void rejectsUnknownOrDuplicateRecoveryBodyFieldsBeforeExecutingTheCommand() {
    var fixture = new Fixture(Set.of(AdminPermission.OUTBOX_RECOVER));
    for (String body :
        List.of(
            "{\"reasonCode\":\"OPERATOR_REVIEWED\",\"extra\":1}",
            "{\"reasonCode\":\"OPERATOR_REVIEWED\",\"reasonCode\":\"OTHER\"}")) {
      var request = recoveryRequest(MESSAGE, IDEMPOTENCY_KEY);
      request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var response = fixture.controller.recover(request, MESSAGE);
      assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
    assertThat(fixture.worker.retryCalls).hasValue(0);
    assertThat(fixture.audit.calls).hasValue(0);
  }

  private static MockHttpServletRequest recoveryRequest(String id, String key) {
    var request = request("POST", "/api/admin/v1/notifications/outbox/" + id + "/recover");
    request.setContentType("application/json; charset=UTF-8");
    request.setContent(
        "{\"reasonCode\":\"OPERATOR_REVIEWED\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    request.addHeader("Idempotency-Key", key);
    return request;
  }

  private static MockHttpServletRequest request(String method, String path) {
    var request = new MockHttpServletRequest(method, path);
    request.setSecure(true);
    request.setCookies(new jakarta.servlet.http.Cookie(AdminAuthController.SESSION_COOKIE, TOKEN));
    return request;
  }

  private static final class Fixture {
    private final Worker worker = new Worker();
    private final Audit audit = new Audit();
    private final Ledger ledger = new Ledger();
    private final OutboxOperations operations =
        new OutboxOperations(worker, ledger, audit, new Transactions(), CLOCK);
    private final AdminOutboxController controller;

    private Fixture(Set<AdminPermission> permissions) {
      var session =
          new AdminSessionService(
              new Sessions(permissions), new FixedTokenCodec(), CLOCK, Duration.ofHours(2));
      controller = new AdminOutboxController(operations, session, runtimeProperties());
    }

    private static AdminRuntimeProperties runtimeProperties() {
      Map<String, String> env = AdminRuntimePropertiesTest.valid();
      env.put("SOUTHLEAF_ADMIN_WEB_MODE", "demo");
      env.put("SOUTHLEAF_ADMIN_WEB_ORIGIN", "https://localhost:18443");
      env.put("SOUTHLEAF_ADMIN_WEB_PORT", "18443");
      env.put("SOUTHLEAF_ADMIN_WEB_KEYSTORE_PATH", "C:/owned/test.jks");
      env.put("SOUTHLEAF_ADMIN_WEB_KEYSTORE_PASSWORD", "FAKE_SECRET_SENTINEL");
      env.put("SOUTHLEAF_ADMIN_WEB_KEYSTORE_TYPE", "JKS");
      env.put("SOUTHLEAF_ADMIN_OUTBOX_MODE", "enabled");
      env.put("SOUTHLEAF_ADMIN_OUTBOX_DB_USERNAME", "southleaf_outbox");
      env.put("SOUTHLEAF_ADMIN_OUTBOX_DB_PASSWORD", "FAKE_OUTBOX_SENTINEL");
      env.put("SOUTHLEAF_ADMIN_OUTBOX_DB_EXPECTED_USER", "southleaf_outbox@localhost");
      env.put("SOUTHLEAF_ADMIN_OUTBOX_DELIVERY_MODE", "TEST_SINK");
      env.put("SOUTHLEAF_ADMIN_OUTBOX_MAX_ATTEMPTS", "5");
      env.put("SOUTHLEAF_ADMIN_OUTBOX_LEASE_SECONDS", "60");
      env.put("SOUTHLEAF_ADMIN_OUTBOX_RETRY_BASE_SECONDS", "5");
      env.put("SOUTHLEAF_ADMIN_OUTBOX_RETRY_MAX_SECONDS", "60");
      env.put("SOUTHLEAF_ADMIN_OUTBOX_BATCH_SIZE", "10");
      env.put("SOUTHLEAF_ADMIN_OUTBOX_POLL_INTERVAL_MS", "5000");
      env.put("SOUTHLEAF_ADMIN_OUTBOX_WORKER_ID", "worker-demo-1");
      return AdminRuntimeProperties.load(env, new Properties());
    }
  }

  private static final class Sessions implements AdminSessionStore {
    private final AdminAccount account;
    private final AdminSession session =
        new AdminSession(
            "0".repeat(64),
            "operator",
            0,
            CLOCK.instant().minusSeconds(60),
            CLOCK.instant().plusSeconds(3600));

    private Sessions(Set<AdminPermission> permissions) {
      account = new AdminAccount("operator", true, 0, permissions);
    }

    @Override
    public boolean createIfAccountCurrent(AdminSession ignored) {
      return false;
    }

    @Override
    public Optional<SessionState> findCurrent(String digest) {
      return "0".repeat(64).equals(digest)
          ? Optional.of(new SessionState(session, account))
          : Optional.empty();
    }

    @Override
    public void revoke(String digest, Instant revokedAt) {}
  }

  private static final class FixedTokenCodec implements SessionTokenCodec {
    @Override
    public String generate() {
      return TOKEN;
    }

    @Override
    public String digest(String token) {
      return "0".repeat(64);
    }
  }

  private static final class Worker implements BookingPorts.OutboxWorker {
    private final java.util.concurrent.atomic.AtomicInteger readCalls =
        new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger retryCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public List<OutboxMessage> lease(String owner, int limit, Instant now, Duration leaseDuration) {
      return List.of();
    }

    @Override
    public void markDelivered(String messageId, String owner, Instant now) {}

    @Override
    public void markFailed(
        String messageId,
        String owner,
        String reasonCode,
        boolean retryable,
        Instant now,
        Instant retryAt) {}

    @Override
    public void markDead(String messageId, String owner, String reasonCode, Instant now) {}

    @Override
    public List<OutboxMessage> deadLetters(int limit) {
      readCalls.incrementAndGet();
      return List.of(
          new OutboxMessage(
              MESSAGE,
              "RESERVATION_CONFIRMED",
              "private-recipient",
              "private-object",
              "dedup",
              CLOCK.instant(),
              "FAILED",
              5,
              null,
              null,
              "provider response body"));
    }

    @Override
    public boolean retryDeadLetter(String messageId, Instant now) {
      retryCalls.incrementAndGet();
      return true;
    }

    @Override
    public int pendingCount(Instant now) {
      readCalls.incrementAndGet();
      return 2;
    }
  }

  private static final class Ledger implements BookingPorts.CommandLedger {
    private final Map<String, BookingPorts.StoredCommand> commands = new HashMap<>();

    @Override
    public Optional<BookingPorts.StoredCommand> find(String actorId, String operation, String key) {
      return Optional.ofNullable(commands.get(actorId + ":" + operation + ":" + key));
    }

    @Override
    public void record(
        String actorId,
        String operation,
        String key,
        String requestSha256,
        String resultId,
        Instant completedAt) {
      commands.put(
          actorId + ":" + operation + ":" + key,
          new BookingPorts.StoredCommand(requestSha256, resultId));
    }
  }

  private static final class Audit implements BookingPorts.BookingAudit {
    private final java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public void record(
        String actorId,
        String commandId,
        String objectType,
        String objectId,
        String action,
        String reasonCode,
        Instant now) {
      calls.incrementAndGet();
    }
  }

  private static final class Transactions implements BookingPorts.TransactionRunner {
    @Override
    public <T> T inTransaction(java.util.function.Supplier<T> work) {
      return work.get();
    }

    @Override
    public void inTransaction(Runnable work) {
      work.run();
    }
  }
}
