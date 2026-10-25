package cn.zhinan.adminruntime;

import cn.zhinan.adminbooking.application.OutboxOperations;
import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.OutboxRules;
import cn.zhinan.adminbooking.port.BookingPorts.OutboxMessage;
import cn.zhinan.adminsecurity.application.AdminSessionService;
import cn.zhinan.adminsecurity.domain.AdminAuthenticationException;
import cn.zhinan.adminsecurity.domain.AdminPermission;
import cn.zhinan.adminsecurity.domain.AdminPrincipal;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;

/** Same-origin outbox board. Responses omit recipient refs, object refs and provider details. */
@RestController
@ResponseBody
@RequestMapping("/api/admin/v1/notifications/outbox")
@ConditionalOnWebApplication(type = Type.SERVLET)
class AdminOutboxController {
  private static final int MAX_BODY_BYTES = 1024;
  private static final JsonFactory STRICT_JSON =
      JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  private static final Pattern SAFE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

  private final OutboxOperations outbox;
  private final AdminSessionService sessions;
  private final AdminOutboxProperties properties;

  AdminOutboxController(
      OutboxOperations outbox, AdminSessionService sessions, AdminRuntimeProperties runtime) {
    this.outbox = outbox;
    this.sessions = sessions;
    this.properties = runtime.outbox();
  }

  @GetMapping
  ResponseEntity<?> list(HttpServletRequest request) {
    return handle(
        request,
        AdminPermission.OUTBOX_READ,
        (principal, requestId) ->
            ok(
                requestId,
                new BoardView(
                    properties.deliveryMode(),
                    false,
                    outbox.pendingCount(),
                    outbox.deadLetters(OutboxRules.MAX_LEASE_BATCH).stream()
                        .map(AdminOutboxController::failure)
                        .toList())));
  }

  @PostMapping("/{messageId}/recover")
  ResponseEntity<?> recover(HttpServletRequest request, @PathVariable String messageId) {
    return handle(
        request,
        AdminPermission.OUTBOX_RECOVER,
        (principal, requestId) -> {
          String key = request.getHeader("Idempotency-Key");
          String reasonCode = recoveryReason(request);
          var result = outbox.retryDeadLetter(principal.accountId(), key, messageId, reasonCode);
          return ok(
              requestId,
              new RecoveryView(
                  result.commandId(), result.messageId(), "QUEUED", result.replayed()));
        });
  }

  private ResponseEntity<?> handle(
      HttpServletRequest request, AdminPermission permission, Handler handler) {
    String requestId = UUID.randomUUID().toString();
    String token = sessionCookie(request);
    if (token == null)
      return error(401, "AUTHENTICATION_REQUIRED", "Authentication is required.", requestId);
    final AdminPrincipal principal;
    try {
      principal = sessions.authenticateWithExpiry(token).principal();
    } catch (AdminAuthenticationException invalid) {
      return error(401, "AUTHENTICATION_REQUIRED", "Authentication is required.", requestId);
    } catch (RuntimeException unavailable) {
      return error(
          503, "AUTHORIZATION_UNAVAILABLE", "Authorization is temporarily unavailable.", requestId);
    }
    if (!principal.permissions().contains(permission))
      return error(403, "CAPABILITY_REQUIRED", "The required capability is missing.", requestId);
    try {
      return handler.apply(principal, requestId);
    } catch (RequestRejected rejected) {
      return error(rejected.status, rejected.code, rejected.getMessage(), requestId);
    } catch (BookingException failure) {
      return bookingFailure(failure, requestId);
    } catch (RuntimeException unavailable) {
      return error(
          503,
          "OUTBOX_SERVICE_UNAVAILABLE",
          "The outbox service is temporarily unavailable.",
          requestId);
    }
  }

  private static ResponseEntity<?> bookingFailure(BookingException failure, String requestId) {
    return switch (failure.failure()) {
      case INVALID_REQUEST -> error(400, "INVALID_REQUEST", "The request is invalid.", requestId);
      case NOT_FOUND ->
          error(404, "RESOURCE_UNAVAILABLE", "The resource is unavailable.", requestId);
      case VERSION_CONFLICT ->
          error(409, "OUTBOX_NOT_RECOVERABLE", "The failure is no longer recoverable.", requestId);
      case IDEMPOTENCY_CONFLICT ->
          error(409, "IDEMPOTENCY_KEY_REUSE", "The key was used for another request.", requestId);
      case STORAGE_UNAVAILABLE ->
          error(
              503,
              "OUTBOX_SERVICE_UNAVAILABLE",
              "The outbox service is temporarily unavailable.",
              requestId);
      default -> error(400, "INVALID_REQUEST", "The request is invalid.", requestId);
    };
  }

  private static String recoveryReason(HttpServletRequest request) {
    String type = request.getContentType();
    if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json"))
      throw new RequestRejected(415, "JSON_REQUIRED", "A JSON request is required.");
    if (request.getContentLengthLong() > MAX_BODY_BYTES)
      throw new RequestRejected(413, "BODY_TOO_LARGE", "The request is too large.");
    final byte[] bytes;
    try {
      bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
    } catch (IOException unreadable) {
      throw new RequestRejected(400, "BODY_UNREADABLE", "The request is invalid.");
    }
    if (bytes.length > MAX_BODY_BYTES)
      throw new RequestRejected(413, "BODY_TOO_LARGE", "The request is too large.");
    try (JsonParser parser = STRICT_JSON.createParser(bytes)) {
      if (parser.nextToken() != JsonToken.START_OBJECT
          || parser.nextToken() != JsonToken.PROPERTY_NAME
          || !"reasonCode".equals(parser.currentName())
          || parser.nextToken() != JsonToken.VALUE_STRING) invalid();
      String reason = parser.getText();
      if (reason.length() > 40 || !reason.matches("[A-Z][A-Z0-9_]{0,39}")) invalid();
      if (parser.nextToken() != JsonToken.END_OBJECT || parser.nextToken() != null) invalid();
      return reason;
    } catch (JacksonException malformed) {
      throw new RequestRejected(400, "JSON_MALFORMED", "The request is invalid.");
    }
  }

  private static void invalid() {
    throw new RequestRejected(400, "REQUEST_INVALID", "The request is invalid.");
  }

  private static String sessionCookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) return null;
    String value = null;
    int count = 0;
    for (Cookie cookie : cookies)
      if (AdminAuthController.SESSION_COOKIE.equals(cookie.getName())) {
        count++;
        value = cookie.getValue();
      }
    return count == 1 ? value : null;
  }

  private static FailureView failure(OutboxMessage message) {
    return new FailureView(
        message.messageId(),
        safe(message.intent()),
        message.status(),
        message.attempts(),
        safe(message.lastError()),
        message.dueAt().toString());
  }

  private static String safe(String value) {
    return value != null && SAFE_CODE.matcher(value).matches() ? value : "UNKNOWN";
  }

  private static <T> ResponseEntity<Envelope<T>> ok(String requestId, T data) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("X-Request-Id", requestId)
        .header("X-Content-Type-Options", "nosniff")
        .body(new Envelope<>("OK", requestId, data));
  }

  private static ResponseEntity<ErrorEnvelope> error(
      int status, String code, String message, String requestId) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header("X-Request-Id", requestId)
        .header("X-Content-Type-Options", "nosniff")
        .body(new ErrorEnvelope(code, message, requestId));
  }

  @FunctionalInterface
  private interface Handler {
    ResponseEntity<?> apply(AdminPrincipal principal, String requestId);
  }

  private static final class RequestRejected extends RuntimeException {
    private final int status;
    private final String code;

    private RequestRejected(int status, String code, String message) {
      super(message);
      this.status = status;
      this.code = code;
    }
  }

  record Envelope<T>(String code, String requestId, T data) {}

  record ErrorEnvelope(String code, String message, String requestId) {}

  record BoardView(
      String deliveryMode,
      boolean externalDeliveryEnabled,
      int pendingCount,
      List<FailureView> failures) {}

  record FailureView(
      String id, String intent, String status, int attempts, String lastError, String dueAt) {}

  record RecoveryView(String commandId, String messageId, String status, boolean replayed) {}
}
