package cn.zhinan.adminruntime;

import cn.zhinan.adminsecurity.application.AdminSessionService;
import cn.zhinan.adminsecurity.domain.AdminAuthenticationException;
import cn.zhinan.adminsecurity.domain.AdminPermission;
import cn.zhinan.adminsecurity.domain.AdminPrincipal;
import cn.zhinan.appuser.AppUserException;
import cn.zhinan.appuser.AppUserModels.ShareParticipant;
import cn.zhinan.appuser.AppUserModels.StoredReservation;
import cn.zhinan.appuser.AppUserModels.StoredShare;
import cn.zhinan.appuser.AppUserModels.StoredUser;
import cn.zhinan.appuser.AppUserModels.StoredVisit;
import cn.zhinan.appuser.AppUserModels.UserPage;
import cn.zhinan.appuser.AppUserRules;
import cn.zhinan.appuser.AppUserService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff view of mini-program users. The list never includes a raw platform identifier. */
@RestController
@RequestMapping("/api/admin/v1")
@ConditionalOnWebApplication(type = Type.SERVLET)
class AdminUserController {
  private static final Pattern ETAG = Pattern.compile("\"u(0|[1-9][0-9]{0,8})\"");
  private static final Duration RETENTION = Duration.ofDays(180);

  private final AppUserService users;
  private final AdminSessionService sessions;
  private final Clock clock;

  AdminUserController(AppUserService users, AdminSessionService sessions, Clock clock) {
    this.users = users;
    this.sessions = sessions;
    this.clock = clock;
  }

  @GetMapping("/users")
  ResponseEntity<?> list(
      HttpServletRequest request,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String cursor) {
    return handle(
        request,
        AdminPermission.USER_READ,
        (principal, requestId) -> {
          users.purge(clock.instant().minus(RETENTION));
          Cursor position = cursor(cursor);
          UserPage page =
              users.list(
                  q,
                  position == null ? null : position.seenAt(),
                  position == null ? null : position.id());
          String next =
              page.nextSeenAt() == null ? null : page.nextSeenAt().toString() + "_" + page.nextId();
          return ok(
              requestId,
              new UserList(page.users().stream().map(AdminUserController::summary).toList(), next));
        });
  }

  @GetMapping("/users/{userId}")
  ResponseEntity<?> detail(HttpServletRequest request, @PathVariable String userId) {
    return handle(
        request,
        AdminPermission.USER_READ,
        (principal, requestId) -> {
          StoredUser user = users.profile(userId);
          return okBuilder(requestId)
              .eTag("u" + user.version())
              .body(new Envelope<>("OK", requestId, detailView(user)));
        });
  }

  @PatchMapping("/users/{userId}")
  ResponseEntity<?> edit(
      HttpServletRequest request,
      @PathVariable String userId,
      @RequestBody(required = false) Map<String, Object> body) {
    return handle(
        request,
        AdminPermission.USER_PROFILE_WRITE,
        (principal, requestId) -> {
          StoredUser user =
              users.edit(
                  userId,
                  ifMatch(request),
                  text(body, "displayName"),
                  text(body, "note"),
                  principal.accountId(),
                  clock.instant());
          return okBuilder(requestId)
              .eTag("u" + user.version())
              .body(new Envelope<>("OK", requestId, detailView(user)));
        });
  }

  @GetMapping(value = "/users/{userId}/avatar", produces = MediaType.IMAGE_JPEG_VALUE)
  ResponseEntity<?> avatar(HttpServletRequest request, @PathVariable String userId) {
    return handle(
        request,
        AdminPermission.USER_READ,
        (principal, requestId) ->
            ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Request-Id", requestId)
                .contentType(MediaType.IMAGE_JPEG)
                .body(users.avatar(userId).content()));
  }

  @GetMapping("/users/{userId}/visits")
  ResponseEntity<?> visits(HttpServletRequest request, @PathVariable String userId) {
    return handle(
        request,
        AdminPermission.USER_READ,
        (principal, requestId) ->
            ok(requestId, users.visits(userId).stream().map(AdminUserController::visit).toList()));
  }

  @GetMapping("/shares/{shareId}")
  ResponseEntity<?> share(HttpServletRequest request, @PathVariable String shareId) {
    return handle(
        request,
        AdminPermission.USER_READ,
        (principal, requestId) -> {
          List<StoredShare> chain = users.chain(shareId);
          StoredShare card = chain.get(chain.size() - 1);
          return ok(
              requestId,
              new ShareView(
                  card.id(),
                  card.page(),
                  card.contentId(),
                  chain.stream().map(AdminUserController::shareHop).toList(),
                  users.opens(shareId).stream().map(AdminUserController::person).toList(),
                  users.forwards(shareId).stream().map(AdminUserController::person).toList()));
        });
  }

  private UserDetail detailView(StoredUser user) {
    var counts = users.counts(user.id());
    return new UserDetail(
        summary(user),
        user.note(),
        user.version(),
        user.createdAt().toString(),
        counts.visits(),
        counts.shares(),
        counts.forwards(),
        users.reservations(user.id(), clock.instant()).stream()
            .map(AdminUserController::reservation)
            .toList(),
        users.visits(user.id()).stream().map(AdminUserController::visit).toList());
  }

  private static UserSummary summary(StoredUser user) {
    return new UserSummary(
        user.id(),
        user.displayName(),
        AppUserRules.displayLabel(user.displayName(), user.fingerprint()),
        AppUserRules.shortId(user.fingerprint()),
        user.displayName() != null,
        user.lastSeenAt().toString());
  }

  private static ReservationView reservation(StoredReservation reservation) {
    return new ReservationView(
        reservation.reservationId(),
        reservation.displayStatus(),
        reservation.localDate(),
        reservation.localStart(),
        reservation.localEnd());
  }

  private static VisitView visit(StoredVisit visit) {
    return new VisitView(
        visit.id(),
        visit.page(),
        visit.contentId(),
        visit.shareCardId(),
        visit.scene(),
        visit.occurredAt().toString());
  }

  private static ShareHop shareHop(StoredShare share) {
    return new ShareHop(share.id(), share.userId(), share.createdAt().toString());
  }

  private static PersonView person(ShareParticipant person) {
    return new PersonView(
        person.userId(),
        AppUserRules.displayLabel(person.displayName(), person.fingerprint()),
        AppUserRules.shortId(person.fingerprint()),
        person.occurredAt().toString());
  }

  private ResponseEntity<?> handle(
      HttpServletRequest request, AdminPermission permission, Handler handler) {
    String requestId = UUID.randomUUID().toString();
    String token = sessionCookie(request);
    if (token == null) {
      return error(401, "AUTHENTICATION_REQUIRED", "Authentication is required.", requestId);
    }
    final AdminPrincipal principal;
    try {
      principal = sessions.authenticateWithExpiry(token).principal();
    } catch (AdminAuthenticationException invalid) {
      return error(401, "AUTHENTICATION_REQUIRED", "Authentication is required.", requestId);
    } catch (RuntimeException unavailable) {
      return error(
          503, "AUTHORIZATION_UNAVAILABLE", "Authorization is temporarily unavailable.", requestId);
    }
    if (!principal.permissions().contains(permission)) {
      return error(403, "CAPABILITY_REQUIRED", "The required capability is missing.", requestId);
    }
    try {
      return handler.apply(principal, requestId);
    } catch (AppUserException failure) {
      return userFailure(failure, requestId);
    } catch (RuntimeException unavailable) {
      return error(503, "USER_SERVICE_UNAVAILABLE", "用户资料暂时无法读取。", requestId);
    }
  }

  private static ResponseEntity<ErrorEnvelope> userFailure(
      AppUserException failure, String requestId) {
    return switch (failure.code()) {
      case INVALID, AVATAR_REJECTED -> error(400, "USER_REQUEST_INVALID", "资料不符合要求。", requestId);
      case NOT_FOUND -> error(404, "USER_NOT_FOUND", "没有找到这位用户。", requestId);
      case CONFLICT -> error(409, "VERSION_CONFLICT", "资料刚刚被改过，请刷新后再保存。", requestId);
      default -> error(503, "USER_SERVICE_UNAVAILABLE", "用户资料暂时无法读取。", requestId);
    };
  }

  private static int ifMatch(HttpServletRequest request) {
    String value = request.getHeader("If-Match");
    var match = value == null ? null : ETAG.matcher(value);
    if (match == null || !match.matches()) {
      throw AppUserException.invalid("IF_MATCH");
    }
    return Integer.parseInt(match.group(1));
  }

  private static String text(Map<String, Object> body, String name) {
    if (body == null || !(body.get(name) instanceof String value))
      throw AppUserException.invalid(name);
    return value;
  }

  private static Cursor cursor(String raw) {
    if (raw == null || raw.isBlank()) return null;
    int split = raw.lastIndexOf('_');
    if (split <= 0 || split == raw.length() - 1) throw AppUserException.invalid("CURSOR");
    try {
      return new Cursor(Instant.parse(raw.substring(0, split)), raw.substring(split + 1));
    } catch (RuntimeException rejected) {
      throw AppUserException.invalid("CURSOR");
    }
  }

  private static String sessionCookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) return null;
    String value = null;
    for (Cookie cookie : cookies) {
      if (AdminAuthController.SESSION_COOKIE.equals(cookie.getName())) value = cookie.getValue();
    }
    return value;
  }

  private static ResponseEntity.BodyBuilder okBuilder(String requestId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("X-Request-Id", requestId)
        .header("X-Content-Type-Options", "nosniff");
  }

  private static <T> ResponseEntity<Envelope<T>> ok(String requestId, T data) {
    return okBuilder(requestId).body(new Envelope<>("OK", requestId, data));
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

  private record Cursor(Instant seenAt, String id) {}

  record Envelope<T>(String code, String requestId, T data) {}

  record ErrorEnvelope(String code, String message, String requestId) {}

  record UserList(List<UserSummary> users, String nextCursor) {}

  record UserSummary(
      String id,
      String displayName,
      String displayLabel,
      String shortId,
      boolean named,
      String lastSeenAt) {}

  record UserDetail(
      UserSummary user,
      String note,
      int version,
      String createdAt,
      int visitCount,
      int shareCount,
      int forwardCount,
      List<ReservationView> reservations,
      List<VisitView> visits) {}

  record ReservationView(
      String id, String status, String localDate, String localStart, String localEnd) {}

  record VisitView(
      String id,
      String page,
      String contentId,
      String shareCardId,
      Integer scene,
      String occurredAt) {}

  record ShareView(
      String id,
      String page,
      String contentId,
      List<ShareHop> chain,
      List<PersonView> opens,
      List<PersonView> forwards) {}

  record ShareHop(String id, String userId, String createdAt) {}

  record PersonView(String userId, String displayLabel, String shortId, String occurredAt) {}
}
