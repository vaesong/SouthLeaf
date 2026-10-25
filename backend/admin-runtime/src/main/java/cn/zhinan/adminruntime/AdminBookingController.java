package cn.zhinan.adminruntime;

import cn.zhinan.adminbooking.application.BookingApplication;
import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.BookingModels.OrderRevision;
import cn.zhinan.adminbooking.domain.BookingModels.OrderSnapshot;
import cn.zhinan.adminbooking.domain.BookingModels.OrderView;
import cn.zhinan.adminbooking.domain.BookingModels.Reservation;
import cn.zhinan.adminbooking.domain.BookingModels.ReservationView;
import cn.zhinan.adminbooking.domain.BookingModels.ServiceSlot;
import cn.zhinan.adminbooking.domain.BookingModels.SlotAssignment;
import cn.zhinan.adminsecurity.application.AdminSessionService;
import cn.zhinan.adminsecurity.domain.AdminAuthenticationException;
import cn.zhinan.adminsecurity.domain.AdminPermission;
import cn.zhinan.adminsecurity.domain.AdminPrincipal;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/** Same-origin booking operations. DTOs never contain customer IDs or raw contact values. */
@RestController
@ResponseBody
@RequestMapping("/api/admin/v1/bookings")
@ConditionalOnWebApplication(type = Type.SERVLET)
class AdminBookingController {
  private static final int MAX_BODY_BYTES = 1024;
  private static final int MAX_PAGE_SIZE = 100;
  private static final Pattern ETAG = Pattern.compile("\"r(0|[1-9][0-9]{0,8})\"");
  private static final Pattern SAFE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
  private static final Pattern RESCHEDULE_CHANGE =
      Pattern.compile("slotId:[0-9a-fA-F-]{36}->[0-9a-fA-F-]{36}");
  private static final JsonFactory STRICT_JSON =
      JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private final BookingApplication booking;
  private final AdminSessionService sessions;

  AdminBookingController(BookingApplication booking, AdminSessionService sessions) {
    this.booking = booking;
    this.sessions = sessions;
  }

  @GetMapping("/reservations/pending")
  ResponseEntity<?> pendingReservations(HttpServletRequest request) {
    return handle(
        request,
        AdminPermission.RESERVATION_READ,
        (principal, requestId) ->
            ok(
                requestId,
                booking.pendingReservations(MAX_PAGE_SIZE).stream()
                    .map(AdminBookingController::reservationSummary)
                    .toList()));
  }

  @GetMapping("/reservations/{reservationId}")
  ResponseEntity<?> reservation(HttpServletRequest request, @PathVariable String reservationId) {
    return handle(
        request,
        AdminPermission.RESERVATION_READ,
        (principal, requestId) -> {
          ReservationView view = booking.reservationView(reservationId);
          return okBuilder(requestId)
              .eTag(reservationEtag(view.reservation().version()))
              .body(new Envelope<>("OK", requestId, reservationView(view)));
        });
  }

  @GetMapping("/slots")
  ResponseEntity<?> openSlots(HttpServletRequest request) {
    return handle(
        request,
        AdminPermission.RESERVATION_READ,
        (principal, requestId) -> {
          Instant from = queryInstant(request, "from");
          Instant to = queryInstant(request, "to");
          if (!from.isBefore(to))
            throw new RequestRejected(400, "TIME_RANGE_INVALID", "The time range is invalid.");
          List<SlotView> slots =
              booking.openSlots(from, to, MAX_PAGE_SIZE).stream()
                  .map(AdminBookingController::slotView)
                  .toList();
          return ok(requestId, slots);
        });
  }

  @GetMapping("/orders/{orderId}")
  ResponseEntity<?> order(HttpServletRequest request, @PathVariable String orderId) {
    return handle(
        request,
        AdminPermission.ORDER_READ,
        (principal, requestId) -> ok(requestId, orderView(booking.orderView(orderId))));
  }

  @PostMapping("/reservations/{reservationId}/cancel")
  ResponseEntity<?> cancel(HttpServletRequest request, @PathVariable String reservationId) {
    return handle(
        request,
        AdminPermission.RESERVATION_CANCEL,
        (principal, requestId) -> {
          int expectedVersion = ifMatch(request);
          Map<String, String> body = strictObject(request, Set.of("reasonCode"));
          var result =
              booking.cancelReservation(
                  principal.accountId(),
                  reservationId,
                  expectedVersion,
                  body.get("reasonCode"),
                  request.getHeader("Idempotency-Key"));
          ReservationView current = booking.reservationView(reservationId);
          return okBuilder(requestId)
              .eTag(reservationEtag(current.reservation().version()))
              .body(
                  new Envelope<>(
                      "OK",
                      requestId,
                      new ActionView(
                          "CANCELLED",
                          result.commandId(),
                          result.reservationId(),
                          result.orderId(),
                          null,
                          result.orderRevision(),
                          result.replayed())));
        });
  }

  @PostMapping("/reservations/{reservationId}/reschedule")
  ResponseEntity<?> reschedule(HttpServletRequest request, @PathVariable String reservationId) {
    return handle(
        request,
        AdminPermission.RESERVATION_RESCHEDULE,
        (principal, requestId) -> {
          int expectedVersion = ifMatch(request);
          Map<String, String> body = strictObject(request, Set.of("reasonCode", "slotId"));
          var result =
              booking.rescheduleReservation(
                  principal.accountId(),
                  reservationId,
                  body.get("slotId"),
                  expectedVersion,
                  body.get("reasonCode"),
                  request.getHeader("Idempotency-Key"));
          ReservationView current = booking.reservationView(reservationId);
          return okBuilder(requestId)
              .eTag(reservationEtag(current.reservation().version()))
              .body(
                  new Envelope<>(
                      "OK",
                      requestId,
                      new ActionView(
                          "CONFIRMED",
                          result.commandId(),
                          result.reservationId(),
                          result.orderId(),
                          result.slotId(),
                          result.orderRevision(),
                          result.replayed())));
        });
  }

  /**
   * Confirms a pending request and creates the order atomically. Q02 requires an explicit human
   * review before any slot is held, so nothing is auto-confirmed: the operator must name the slot
   * to occupy and present the current reservation revision. The transaction itself is unchanged
   * (occupancy -> reservation -> unique order -> immutable snapshot -> audit -> outbox).
   */
  @PostMapping("/reservations/{reservationId}/confirm")
  ResponseEntity<?> confirm(HttpServletRequest request, @PathVariable String reservationId) {
    return handle(
        request,
        AdminPermission.RESERVATION_CONFIRM,
        (principal, requestId) -> {
          int expectedVersion = ifMatch(request);
          Map<String, String> body = strictObject(request, Set.of("slotId"));
          var result =
              booking.confirmReservation(
                  principal.accountId(),
                  reservationId,
                  body.get("slotId"),
                  expectedVersion,
                  request.getHeader("Idempotency-Key"));
          ReservationView current = booking.reservationView(reservationId);
          return okBuilder(requestId)
              .eTag(reservationEtag(current.reservation().version()))
              .body(
                  new Envelope<>(
                      "OK",
                      requestId,
                      new ActionView(
                          "CONFIRMED",
                          result.commandId(),
                          result.reservationId(),
                          result.orderId(),
                          result.slotId(),
                          result.orderRevision(),
                          result.replayed())));
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
          "BOOKING_SERVICE_UNAVAILABLE",
          "The booking service is temporarily unavailable.",
          requestId);
    }
  }

  private static ResponseEntity<?> bookingFailure(BookingException failure, String requestId) {
    int status =
        switch (failure.failure()) {
          case INVALID_REQUEST -> 400;
          case NOT_FOUND -> 404;
          case VERSION_CONFLICT, SLOT_UNAVAILABLE, IDEMPOTENCY_CONFLICT -> 409;
          case STORAGE_UNAVAILABLE -> 503;
          default -> 409;
        };
    String code =
        switch (failure.failure()) {
          case INVALID_REQUEST -> safeCode(failure.reason(), "INVALID_REQUEST");
          case NOT_FOUND -> "RESOURCE_UNAVAILABLE";
          case VERSION_CONFLICT -> "VERSION_CONFLICT";
          case SLOT_UNAVAILABLE -> "SLOT_UNAVAILABLE";
          case IDEMPOTENCY_CONFLICT -> "IDEMPOTENCY_KEY_REUSE";
          case STORAGE_UNAVAILABLE -> "BOOKING_SERVICE_UNAVAILABLE";
          default -> "BOOKING_OPERATION_REJECTED";
        };
    return error(status, code, "The booking request could not be completed.", requestId);
  }

  private static String safeCode(String value, String fallback) {
    return value != null && SAFE_CODE.matcher(value).matches() ? value : fallback;
  }

  private static int ifMatch(HttpServletRequest request) {
    String value = request.getHeader("If-Match");
    var match = value == null ? null : ETAG.matcher(value);
    if (match == null || !match.matches())
      throw new RequestRejected(
          428, "IF_MATCH_REQUIRED", "A current reservation revision is required.");
    return Integer.parseInt(match.group(1));
  }

  private static Instant queryInstant(HttpServletRequest request, String name) {
    String value = request.getParameter(name);
    if (value == null || value.length() > 40)
      throw new RequestRejected(400, "TIME_RANGE_REQUIRED", "A UTC time range is required.");
    try {
      return Instant.parse(value);
    } catch (RuntimeException invalid) {
      throw new RequestRejected(400, "TIME_RANGE_INVALID", "The time range is invalid.");
    }
  }

  private static Map<String, String> strictObject(
      HttpServletRequest request, Set<String> requiredFields) {
    String contentType = request.getContentType();
    if (contentType == null
        || !"application/json"
            .equals(contentType.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT)))
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
    var fields = new HashMap<String, String>();
    try (JsonParser parser = STRICT_JSON.createParser(bytes)) {
      if (parser.nextToken() != JsonToken.START_OBJECT) invalidJson();
      JsonToken token;
      while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
        if (token != JsonToken.PROPERTY_NAME) invalidJson();
        String name = parser.currentName();
        if (!requiredFields.contains(name) || parser.nextToken() != JsonToken.VALUE_STRING)
          invalidJson();
        fields.put(name, parser.getText());
      }
      if (parser.nextToken() != null || !fields.keySet().equals(requiredFields)) invalidJson();
      return Map.copyOf(fields);
    } catch (JacksonException malformed) {
      throw new RequestRejected(400, "JSON_MALFORMED", "The request is invalid.");
    }
  }

  private static void invalidJson() {
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

  private static String reservationEtag(int version) {
    return "\"r" + version + "\"";
  }

  private static ReservationSummary reservationSummary(Reservation reservation) {
    return new ReservationSummary(
        reservation.reservationId(),
        reservation.status().name(),
        safeCode(reservation.source(), "UNKNOWN"),
        reservation.expiresAt().toString(),
        reservation.createdAt().toString(),
        reservation.version(),
        reservation.slotId());
  }

  private static ReservationDetail reservationView(ReservationView view) {
    return new ReservationDetail(
        reservationSummary(view.reservation()),
        view.orderId(),
        view.assignments().stream().map(AdminBookingController::assignmentView).toList());
  }

  private static AssignmentView assignmentView(SlotAssignment assignment) {
    return new AssignmentView(
        assignment.slotId(),
        assignment.previousSlotId(),
        assignment.assignedAt().toString(),
        safeCode(assignment.reasonCode(), "UNKNOWN"),
        assignment.revision());
  }

  private static SlotView slotView(ServiceSlot slot) {
    return new SlotView(
        slot.slotId(),
        slot.startAt().toString(),
        slot.endAt().toString(),
        slot.businessZone(),
        slot.displayOffset(),
        slot.status(),
        slot.capacity(),
        slot.ruleVersion(),
        slot.revision());
  }

  private static OrderDetail orderView(OrderView view) {
    var order = view.order();
    OrderSnapshot snapshot = view.snapshot();
    return new OrderDetail(
        order.orderId(),
        order.sourceReservationId(),
        order.lifecycle().name(),
        order.fulfilment().name(),
        order.currentRevision(),
        order.createdAt().toString(),
        view.reservationStatus(),
        new SnapshotView(
            snapshot.packageVersionRef(),
            snapshot.packageDisplayName(),
            snapshot.currencyCode(),
            snapshot.amountMinorUnits(),
            snapshot.priceVersion(),
            snapshot.slotId(),
            snapshot.startAt().toString(),
            snapshot.endAt().toString(),
            snapshot.businessZone(),
            snapshot.displayOffset(),
            snapshot.scheduleRevision(),
            safeCode(snapshot.source(), "UNKNOWN"),
            snapshot.capturedAt().toString()),
        view.revisions().stream().map(AdminBookingController::revisionView).toList());
  }

  private static RevisionView revisionView(OrderRevision revision) {
    return new RevisionView(
        revision.revisionNo(),
        revision.actorRef(),
        safeChangedFields(revision.changedFields()),
        safeCode(revision.reasonCode(), "UNKNOWN"),
        revision.createdAt().toString());
  }

  private static String safeChangedFields(String changedFields) {
    if ("lifecycle:CANCELLED".equals(changedFields)) return changedFields;
    return changedFields != null && RESCHEDULE_CHANGE.matcher(changedFields).matches()
        ? "slotId:CHANGED"
        : "UNKNOWN";
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

  record ReservationSummary(
      String id,
      String status,
      String source,
      String expiresAt,
      String createdAt,
      int version,
      String slotId) {}

  record ReservationDetail(
      ReservationSummary reservation, String orderId, List<AssignmentView> assignments) {}

  record AssignmentView(
      String slotId, String previousSlotId, String assignedAt, String reasonCode, int revision) {}

  record SlotView(
      String id,
      String startAt,
      String endAt,
      String businessZone,
      String displayOffset,
      String status,
      int capacity,
      String ruleVersion,
      int revision) {}

  record SnapshotView(
      String packageVersionRef,
      String packageDisplayName,
      String currencyCode,
      long amountMinorUnits,
      String priceVersion,
      String slotId,
      String startAt,
      String endAt,
      String businessZone,
      String displayOffset,
      int scheduleRevision,
      String source,
      String capturedAt) {}

  record RevisionView(
      int revision, String actorRef, String changedFields, String reasonCode, String createdAt) {}

  record OrderDetail(
      String id,
      String reservationId,
      String lifecycle,
      String fulfilment,
      int currentRevision,
      String createdAt,
      String reservationStatus,
      SnapshotView snapshot,
      List<RevisionView> revisions) {}

  record ActionView(
      String status,
      String commandId,
      String reservationId,
      String orderId,
      String slotId,
      int orderRevision,
      boolean replayed) {}
}
