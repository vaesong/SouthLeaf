package cn.zhinan.adminruntime;

import cn.zhinan.adminsecurity.application.AdminLoginService;
import cn.zhinan.adminsecurity.application.AdminSessionService;
import cn.zhinan.adminsecurity.domain.AdminAuthenticationException;
import cn.zhinan.adminsecurity.domain.AdminPrincipal;
import cn.zhinan.adminsecurity.domain.CredentialDependencyException;
import cn.zhinan.adminsecurity.domain.PasswordInput;
import cn.zhinan.adminsecurity.port.AdminSecurityStorageException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity.BodyBuilder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;

/** Same-origin login/session adapter. All account and session decisions stay in core services. */
@RestController
@ResponseBody
@RequestMapping("/api/admin/v1/auth")
@ConditionalOnWebApplication(type = Type.SERVLET)
class AdminAuthController {
  static final String SESSION_COOKIE = "__Host-sl_admin_session";
  static final String CSRF_COOKIE = "__Host-sl_admin_csrf";
  private static final int MAX_BODY_BYTES = 4096;
  private static final int MAX_ACCOUNT_ID_CHARS = 64;
  private static final int MAX_PASSWORD_CHARS = 512;
  private static final JsonFactory STRICT_JSON =
      JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private final AdminLoginService logins;
  private final AdminSessionService sessions;
  private final AdminWebProperties webProperties;
  private final AdminLoginRateLimiter rateLimiter;
  private final Clock clock;

  AdminAuthController(
      AdminLoginService logins,
      AdminSessionService sessions,
      AdminWebProperties webProperties,
      AdminLoginRateLimiter rateLimiter,
      Clock clock) {
    this.logins = logins;
    this.sessions = sessions;
    this.webProperties = webProperties;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  @GetMapping("/csrf")
  ResponseEntity<CsrfEnvelope> csrf(@RequestAttribute("_csrf") CsrfToken csrf) {
    String requestId = UUID.randomUUID().toString();
    return ok(
        requestId,
        new CsrfEnvelope("OK", requestId, new CsrfData(csrf.getToken(), csrf.getHeaderName())));
  }

  @PostMapping("/login")
  ResponseEntity<?> login(HttpServletRequest request) {
    UUID requestId = UUID.randomUUID();
    CookieValue existing = cookie(request, SESSION_COOKIE);
    if (existing.ambiguous)
      return error(400, "INVALID_REQUEST", "The request is invalid.", requestId, 0);
    if (existing.value != null) {
      try {
        sessions.authenticateWithExpiry(existing.value);
        return error(
            409,
            "ALREADY_AUTHENTICATED",
            "A valid session already exists; log out first.",
            requestId,
            0);
      } catch (AdminAuthenticationException invalidExistingSession) {
        // An expired or invalid old cookie must not prevent a fresh explicit login.
      } catch (RuntimeException unavailable) {
        return serviceUnavailable(requestId);
      }
    }

    LoginInput input;
    try {
      input = readLogin(request);
    } catch (RequestRejected rejected) {
      return error(rejected.status, rejected.code, rejected.message, requestId, 0);
    }

    try {
      String origin = webProperties.origin().toString();
      AdminLoginRateLimiter.Decision decision = rateLimiter.tryAcquire(origin, input.accountId);
      if (!decision.allowed())
        return error(
            429,
            "RATE_LIMITED",
            "Too many attempts. Try again later.",
            requestId,
            decision.retryAfterSeconds());

      AdminSessionService.IssuedSession issued =
          logins.login(input.accountId, input.password, requestId);
      AdminSessionService.AuthenticatedSession authenticated =
          sessions.authenticateWithExpiry(issued.token());
      rateLimiter.recordSuccess(origin, input.accountId);
      Identity identity = identity(authenticated.principal(), authenticated.expiresAt());
      Duration maxAge = Duration.between(clock.instant(), authenticated.expiresAt());
      if (maxAge.isNegative() || maxAge.isZero())
        return error(
            401,
            "INVALID_CREDENTIALS",
            "The account or password is incorrect, or the account is unavailable.",
            requestId,
            0);
      BodyBuilder response = okBuilder(requestId);
      response.header(
          HttpHeaders.SET_COOKIE,
          ResponseCookie.from(SESSION_COOKIE, issued.token())
              .secure(true)
              .httpOnly(true)
              .sameSite("Strict")
              .path("/")
              .maxAge(maxAge)
              .build()
              .toString());
      response.header(HttpHeaders.SET_COOKIE, clearCsrfCookie());
      return response.body(new IdentityEnvelope("OK", requestId.toString(), identity));
    } catch (AdminAuthenticationException denied) {
      return error(
          401,
          "INVALID_CREDENTIALS",
          "The account or password is incorrect, or the account is unavailable.",
          requestId,
          0);
    } catch (CredentialDependencyException dependency) {
      if (dependency.reason() == CredentialDependencyException.Reason.CAPACITY_EXHAUSTED)
        return error(
            429, "RATE_LIMITED", "Authentication capacity is temporarily full.", requestId, 2);
      return serviceUnavailable(requestId);
    } catch (AdminSecurityStorageException unavailable) {
      return serviceUnavailable(requestId);
    } catch (RuntimeException unexpected) {
      return serviceUnavailable(requestId);
    } finally {
      Arrays.fill(input.password, '\0');
    }
  }

  @GetMapping("/session")
  ResponseEntity<?> session(HttpServletRequest request) {
    UUID requestId = UUID.randomUUID();
    CookieValue value = cookie(request, SESSION_COOKIE);
    if (value.value == null) return unauthenticated(requestId, value.present);
    try {
      AdminSessionService.AuthenticatedSession current =
          sessions.authenticateWithExpiry(value.value);
      return ok(
          requestId.toString(),
          new IdentityEnvelope(
              "OK", requestId.toString(), identity(current.principal(), current.expiresAt())));
    } catch (AdminAuthenticationException invalid) {
      return unauthenticated(requestId, true);
    } catch (RuntimeException unavailable) {
      return serviceUnavailable(requestId);
    }
  }

  @PostMapping("/logout")
  ResponseEntity<?> logout(HttpServletRequest request) {
    UUID requestId = UUID.randomUUID();
    try {
      requireEmptyJsonObject(request);
    } catch (RequestRejected rejected) {
      return error(rejected.status, rejected.code, rejected.message, requestId, 0);
    }
    CookieValue value = cookie(request, SESSION_COOKIE);
    if (value.ambiguous)
      return error(400, "INVALID_REQUEST", "The request is invalid.", requestId, 0);
    try {
      if (value.value != null) sessions.revoke(value.value);
      HttpHeaders headers = new HttpHeaders();
      headers.setCacheControl("no-store");
      headers.set("X-Request-Id", requestId.toString());
      headers.add(HttpHeaders.SET_COOKIE, clearSessionCookie());
      headers.add(HttpHeaders.SET_COOKIE, clearCsrfCookie());
      return ResponseEntity.noContent().headers(headers).build();
    } catch (RuntimeException unavailable) {
      return serviceUnavailable(requestId);
    }
  }

  private static LoginInput readLogin(HttpServletRequest request) {
    byte[] body = readJsonBody(request);
    char[] password = null;
    boolean transferred = false;
    try (JsonParser parser = STRICT_JSON.createParser(body)) {
      if (parser.nextToken() != JsonToken.START_OBJECT) throw invalidRequest();
      String accountId = null;
      boolean hasAccountId = false;
      boolean hasPassword = false;
      JsonToken token;
      while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
        if (token != JsonToken.PROPERTY_NAME) throw invalidRequest();
        String field = parser.currentName();
        JsonToken value = parser.nextToken();
        if (value != JsonToken.VALUE_STRING) throw invalidRequest();
        int length = parser.getTextLength();
        if ("accountId".equals(field)) {
          if (hasAccountId || length < 1 || length > MAX_ACCOUNT_ID_CHARS) throw invalidRequest();
          accountId = parser.getText();
          hasAccountId = true;
        } else if ("password".equals(field)) {
          if (hasPassword || length < 1 || length > MAX_PASSWORD_CHARS) throw invalidRequest();
          char[] text = parser.getTextCharacters();
          int start = parser.getTextOffset();
          password = Arrays.copyOfRange(text, start, start + length);
          hasPassword = true;
        } else {
          throw invalidRequest();
        }
      }
      if (!hasAccountId
          || !hasPassword
          || parser.nextToken() != null
          || !PasswordInput.isBounded(password)) throw invalidRequest();
      transferred = true;
      return new LoginInput(accountId, password);
    } catch (JacksonException malformed) {
      throw invalidRequest();
    } finally {
      Arrays.fill(body, (byte) 0);
      if (!transferred && password != null) Arrays.fill(password, '\0');
    }
  }

  private static void requireEmptyJsonObject(HttpServletRequest request) {
    byte[] body = readJsonBody(request);
    try (JsonParser parser = STRICT_JSON.createParser(body)) {
      if (parser.nextToken() != JsonToken.START_OBJECT
          || parser.nextToken() != JsonToken.END_OBJECT
          || parser.nextToken() != null) throw invalidRequest();
    } catch (JacksonException malformed) {
      throw invalidRequest();
    } finally {
      Arrays.fill(body, (byte) 0);
    }
  }

  private static byte[] readJsonBody(HttpServletRequest request) {
    if (!isJsonContentType(request.getContentType())
        || (request.getHeader("Content-Encoding") != null
            && !request.getHeader("Content-Encoding").isBlank()
            && !"identity".equalsIgnoreCase(request.getHeader("Content-Encoding").trim())))
      throw new RequestRejected(
          415, "UNSUPPORTED_MEDIA_TYPE", "Only application/json is accepted.");
    if (request.getContentLengthLong() > MAX_BODY_BYTES)
      throw new RequestRejected(413, "PAYLOAD_TOO_LARGE", "The request body exceeds 4 KiB.");
    try {
      byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
      if (body.length > MAX_BODY_BYTES) {
        Arrays.fill(body, (byte) 0);
        throw new RequestRejected(413, "PAYLOAD_TOO_LARGE", "The request body exceeds 4 KiB.");
      }
      return body;
    } catch (IOException unavailable) {
      throw invalidRequest();
    }
  }

  private static boolean isJsonContentType(String value) {
    if (value == null) return false;
    String[] parts = value.split(";", -1);
    if (!"application/json".equalsIgnoreCase(parts[0].trim())) return false;
    for (int i = 1; i < parts.length; i++) {
      String parameter = parts[i].trim();
      if (parameter.toLowerCase(java.util.Locale.ROOT).startsWith("charset=")) {
        String charset = parameter.substring("charset=".length()).replace("\"", "").trim();
        if (!"utf-8".equalsIgnoreCase(charset)) return false;
      }
    }
    return true;
  }

  private ResponseEntity<?> unauthenticated(UUID requestId, boolean clearCookie) {
    BodyBuilder response = errorBuilder(401, requestId, 0);
    if (clearCookie) response.header(HttpHeaders.SET_COOKIE, clearSessionCookie());
    return response.body(
        new ErrorEnvelope(
            "AUTHENTICATION_REQUIRED", "Authentication is required.", requestId.toString()));
  }

  private ResponseEntity<?> serviceUnavailable(UUID requestId) {
    return error(
        503,
        "AUTH_SERVICE_UNAVAILABLE",
        "Authentication is temporarily unavailable.",
        requestId,
        0);
  }

  private static Identity identity(AdminPrincipal principal, Instant expiresAt) {
    List<String> permissions =
        principal.permissions().stream().map(Enum::name).distinct().sorted().toList();
    return new Identity(principal.accountId(), permissions, expiresAt.toString());
  }

  private static CookieValue cookie(HttpServletRequest request, String name) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) return new CookieValue(false, false, null);
    String value = null;
    int count = 0;
    for (Cookie cookie : cookies) {
      if (name.equals(cookie.getName())) {
        count++;
        value = cookie.getValue();
      }
    }
    return new CookieValue(count > 0, count > 1, count == 1 ? value : null);
  }

  private static String clearSessionCookie() {
    return ResponseCookie.from(SESSION_COOKIE, "")
        .secure(true)
        .httpOnly(true)
        .sameSite("Strict")
        .path("/")
        .maxAge(Duration.ZERO)
        .build()
        .toString();
  }

  private static String clearCsrfCookie() {
    return ResponseCookie.from(CSRF_COOKIE, "")
        .secure(true)
        .httpOnly(false)
        .sameSite("Strict")
        .path("/")
        .maxAge(Duration.ZERO)
        .build()
        .toString();
  }

  private static <T> ResponseEntity<T> ok(String requestId, T body) {
    return okBuilder(requestId).body(body);
  }

  private static BodyBuilder okBuilder(UUID requestId) {
    return okBuilder(requestId.toString());
  }

  private static BodyBuilder okBuilder(String requestId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("X-Request-Id", requestId);
  }

  private static ResponseEntity<ErrorEnvelope> error(
      int status, String code, String message, UUID requestId, long retryAfterSeconds) {
    return errorBuilder(status, requestId, retryAfterSeconds)
        .body(new ErrorEnvelope(code, message, requestId.toString()));
  }

  private static BodyBuilder errorBuilder(int status, UUID requestId, long retryAfterSeconds) {
    BodyBuilder response =
        ResponseEntity.status(status)
            .cacheControl(CacheControl.noStore())
            .header("X-Request-Id", requestId.toString());
    if (retryAfterSeconds > 0) response.header("Retry-After", Long.toString(retryAfterSeconds));
    return response;
  }

  private static RequestRejected invalidRequest() {
    return new RequestRejected(400, "INVALID_REQUEST", "The request is invalid.");
  }

  record CsrfEnvelope(String code, String requestId, CsrfData data) {}

  record CsrfData(String csrfToken, String headerName) {}

  record IdentityEnvelope(String code, String requestId, Identity data) {}

  record Identity(String accountId, List<String> permissions, String expiresAt) {}

  record ErrorEnvelope(String code, String message, String requestId) {}

  private record LoginInput(String accountId, char[] password) {}

  private record CookieValue(boolean present, boolean ambiguous, String value) {}

  private static final class RequestRejected extends RuntimeException {
    private final int status;
    private final String code;
    private final String message;

    private RequestRejected(int status, String code, String message) {
      this.status = status;
      this.code = code;
      this.message = message;
    }
  }
}
