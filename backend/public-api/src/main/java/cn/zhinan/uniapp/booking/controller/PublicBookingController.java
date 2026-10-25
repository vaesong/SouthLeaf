package cn.zhinan.uniapp.booking.controller;

import cn.zhinan.uniapp.booking.PublicBookingException;
import cn.zhinan.uniapp.booking.application.PublicBookingService;
import cn.zhinan.uniapp.booking.domain.PublicBookingRules;
import cn.zhinan.uniapp.booking.domain.PublicBookingViews.PackageList;
import cn.zhinan.uniapp.booking.domain.PublicBookingViews.RequestList;
import cn.zhinan.uniapp.booking.domain.PublicBookingViews.RequestView;
import cn.zhinan.uniapp.booking.domain.PublicBookingViews.SessionView;
import cn.zhinan.uniapp.booking.domain.PublicBookingViews.SlotPage;
import cn.zhinan.uniapp.dto.PublicDtos;
import cn.zhinan.uniapp.publicquery.contract.PublicApiResponse;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer booking entry. Every response uses the shared public envelope and is never cacheable.
 */
@RestController
@RequestMapping("/api/v1/public/booking")
public class PublicBookingController {
  static final String CONTENT_VERSION = "booking-1.0";
  private static final Set<String> SESSION_BODY = Set.of("loginCode");
  private static final Set<String> REQUEST_BODY = Set.of("slotId", "packageVersionRef");

  private final PublicBookingService booking;
  private final Clock clock;

  public PublicBookingController(PublicBookingService booking, Clock clock) {
    this.booking = booking;
    this.clock = clock;
  }

  @PostMapping("/session")
  public ResponseEntity<PublicApiResponse<SessionView>> session(
      @RequestBody(required = false) Map<String, Object> body) {
    only(body, SESSION_BODY);
    return ok(booking.startSession(field(body, "loginCode")));
  }

  @GetMapping("/packages")
  public ResponseEntity<PublicApiResponse<PackageList>> packages() {
    return ok(booking.packages());
  }

  @GetMapping("/slots")
  public ResponseEntity<PublicApiResponse<SlotPage>> slots(
      @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
    return ok(booking.openSlots(from, to));
  }

  @PostMapping("/requests")
  public ResponseEntity<PublicApiResponse<RequestView>> create(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) Map<String, Object> body) {
    only(body, REQUEST_BODY);
    RequestView created =
        booking.createRequest(
            bearer(authorization),
            idempotencyKey,
            field(body, "slotId"),
            field(body, "packageVersionRef"));
    return ResponseEntity.status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .body(envelope(created));
  }

  @GetMapping("/requests")
  public ResponseEntity<PublicApiResponse<RequestList>> requests(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return ok(booking.requests(bearer(authorization)));
  }

  @GetMapping("/requests/{reservationId}")
  public ResponseEntity<PublicApiResponse<RequestView>> request(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String reservationId) {
    return ok(booking.request(bearer(authorization), reservationId));
  }

  private <T> ResponseEntity<PublicApiResponse<T>> ok(T data) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(envelope(data));
  }

  private <T> PublicApiResponse<T> envelope(T data) {
    return PublicApiResponse.success(
        "req_" + UUID.randomUUID().toString().replace("-", ""),
        data,
        new PublicDtos.Meta(clock.instant(), CONTENT_VERSION, null, booking.dataMode()));
  }

  private static void only(Map<String, Object> body, Set<String> allowed) {
    if (body == null) throw PublicBookingRules.invalid("BODY");
    if (body.size() != allowed.size() || !allowed.containsAll(body.keySet()))
      throw PublicBookingRules.invalid("UNEXPECTED_FIELD");
  }

  private static String field(Map<String, Object> body, String name) {
    Object value = body.get(name);
    if (!(value instanceof String text)) throw PublicBookingRules.invalid(name);
    return text;
  }

  private static String bearer(String authorization) {
    if (authorization == null) throw sessionRequired();
    String value = authorization.strip();
    if (!value.regionMatches(true, 0, "Bearer ", 0, 7)) throw sessionRequired();
    String token = value.substring(7).strip();
    if (token.isEmpty()) throw sessionRequired();
    return token;
  }

  private static PublicBookingException sessionRequired() {
    return new PublicBookingException(
        PublicBookingException.ErrorCode.SESSION_REQUIRED, "BEARER_REQUIRED");
  }
}
