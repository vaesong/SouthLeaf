package cn.zhinan.uniapp.user;

import cn.zhinan.appuser.AppUserException;
import cn.zhinan.appuser.AppUserModels.StoredReservation;
import cn.zhinan.appuser.AppUserModels.StoredShare;
import cn.zhinan.appuser.AppUserModels.StoredUser;
import cn.zhinan.appuser.AppUserRules;
import cn.zhinan.appuser.AppUserTokens;
import cn.zhinan.uniapp.dto.PublicDtos;
import cn.zhinan.uniapp.publicquery.contract.PublicApiResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Mini-program login, profile, visits and shares. Responses are never cacheable. */
@RestController
@RequestMapping("/api/v1/public/users")
public class PublicUserController {
  static final String CONTENT_VERSION = "user-1.0";

  private final PublicUserRuntime users;
  private final Clock clock;

  public PublicUserController(PublicUserRuntime users, Clock clock) {
    this.users = users;
    this.clock = clock;
  }

  @PostMapping("/session")
  public ResponseEntity<PublicApiResponse<SessionView>> session(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody(required = false) Map<String, Object> body) {
    Instant now = clock.instant();
    String loginCode = text(body, "loginCode");
    StoredUser user =
        loginCode != null
            ? users.service().login(users.subject(loginCode), now)
            : users.service().touch(users.tokens().userId(bearer(authorization), now), now);
    AppUserTokens.Issued issued = users.tokens().issue(user.id(), now);
    return ok(new SessionView(issued.token(), issued.expiresAt().toString(), profile(user)));
  }

  @GetMapping("/me")
  public ResponseEntity<PublicApiResponse<ProfileView>> me(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return ok(profile(current(authorization)));
  }

  @PutMapping("/me")
  public ResponseEntity<PublicApiResponse<ProfileView>> rename(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody(required = false) Map<String, Object> body) {
    StoredUser user = current(authorization);
    String name = text(body, "displayName");
    if (name == null) throw AppUserException.invalid("DISPLAY_NAME");
    return ok(profile(users.service().rename(user.id(), name, clock.instant())));
  }

  @GetMapping(value = "/me/avatar", produces = MediaType.IMAGE_JPEG_VALUE)
  public ResponseEntity<byte[]> avatar(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    byte[] jpeg = users.service().avatar(current(authorization).id()).content();
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(jpeg);
  }

  @PutMapping(value = "/me/avatar", consumes = MediaType.ALL_VALUE)
  public ResponseEntity<PublicApiResponse<ProfileView>> replaceAvatar(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "Content-Type", required = false) String contentType,
      @RequestBody(required = false) byte[] body) {
    StoredUser user = current(authorization);
    users.service().replaceAvatar(user.id(), contentType, body, clock.instant());
    return ok(profile(users.service().profile(user.id())));
  }

  @GetMapping("/me/reservations")
  public ResponseEntity<PublicApiResponse<ReservationList>> reservations(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    List<ReservationView> items =
        users.service().reservations(current(authorization).id(), clock.instant()).stream()
            .map(PublicUserController::reservation)
            .toList();
    return ok(new ReservationList(items));
  }

  @PostMapping("/visits")
  public ResponseEntity<PublicApiResponse<VisitView>> visit(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody(required = false) Map<String, Object> body) {
    StoredUser user = current(authorization);
    var stored =
        users
            .service()
            .visit(
                user.id(),
                text(body, "clientEventId"),
                text(body, "page"),
                text(body, "contentId"),
                text(body, "shareCardId"),
                integer(body, "scene"),
                clock.instant());
    return ok(new VisitView(stored.id()));
  }

  @PostMapping("/shares")
  public ResponseEntity<PublicApiResponse<ShareView>> share(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody(required = false) Map<String, Object> body) {
    StoredUser user = current(authorization);
    StoredShare card =
        users
            .service()
            .share(
                user.id(),
                text(body, "page"),
                text(body, "contentId"),
                text(body, "parentCardId"),
                integer(body, "scene"),
                clock.instant());
    return ok(new ShareView(card.id()));
  }

  private StoredUser current(String authorization) {
    return users.service().profile(users.tokens().userId(bearer(authorization), clock.instant()));
  }

  private ProfileView profile(StoredUser user) {
    boolean named = user.displayName() != null && !user.displayName().isBlank();
    return new ProfileView(
        user.id(),
        user.displayName(),
        AppUserRules.displayLabel(user.displayName(), user.fingerprint()),
        AppUserRules.shortId(user.fingerprint()),
        named,
        users.service().hasAvatar(user.id()),
        user.version(),
        user.lastSeenAt().toString());
  }

  private static ReservationView reservation(StoredReservation reservation) {
    SlotView slot =
        reservation.localDate() == null
            ? null
            : new SlotView(
                reservation.localDate(), reservation.localStart(), reservation.localEnd());
    return new ReservationView(
        reservation.reservationId(),
        reservation.displayStatus(),
        reservation.expiresAt() == null ? null : reservation.expiresAt().toString(),
        slot);
  }

  private <T> ResponseEntity<PublicApiResponse<T>> ok(T data) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(envelope(data));
  }

  private <T> PublicApiResponse<T> envelope(T data) {
    return PublicApiResponse.success(
        "req_" + UUID.randomUUID().toString().replace("-", ""),
        data,
        new PublicDtos.Meta(clock.instant(), CONTENT_VERSION, null, users.dataMode()));
  }

  private static String bearer(String authorization) {
    if (authorization == null) throw AppUserException.sessionRequired();
    String value = authorization.strip();
    if (!value.regionMatches(true, 0, "Bearer ", 0, 7)) throw AppUserException.sessionRequired();
    String token = value.substring(7).strip();
    if (token.isEmpty()) throw AppUserException.sessionRequired();
    return token;
  }

  private static String text(Map<String, Object> body, String name) {
    if (body == null || !body.containsKey(name) || body.get(name) == null) return null;
    Object value = body.get(name);
    if (!(value instanceof String text)) throw AppUserException.invalid(name.toUpperCase());
    return text;
  }

  private static Integer integer(Map<String, Object> body, String name) {
    if (body == null || !body.containsKey(name) || body.get(name) == null) return null;
    Object value = body.get(name);
    if (value instanceof Integer number) return number;
    if (value instanceof Long number) return Math.toIntExact(number);
    throw AppUserException.invalid("SCENE");
  }

  public record SessionView(String token, String expiresAt, ProfileView profile) {}

  public record ProfileView(
      String id,
      String displayName,
      String displayLabel,
      String shortId,
      boolean named,
      boolean hasAvatar,
      int version,
      String lastSeenAt) {}

  public record ReservationList(List<ReservationView> items) {}

  public record ReservationView(
      String reservationId, String displayStatus, String expiresAt, SlotView requestedSlot) {}

  public record SlotView(String localDate, String localStart, String localEnd) {}

  public record VisitView(String id) {}

  public record ShareView(String id) {}
}
