package cn.zhinan.adminruntime;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhinan.adminbooking.application.BookingApplication;
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
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AdminBookingControllerTest {
  private static final String TOKEN = "T".repeat(43);
  private static final String RESERVATION = "00000000-0000-0000-0000-000000000001";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneOffset.UTC);

  @Test
  void requiresSessionAndExactReadCapabilityBeforeCallingBookingApplication() {
    var noSession = new Fixture(Set.of(AdminPermission.RESERVATION_READ));
    assertThat(
            noSession
                .controller
                .pendingReservations(new MockHttpServletRequest("GET", "/bookings"))
                .getStatusCode()
                .value())
        .isEqualTo(401);

    var wrongPermission = new Fixture(Set.of(AdminPermission.ORDER_READ));
    var response = wrongPermission.controller.pendingReservations(request("GET", "/bookings"));
    assertThat(response.getStatusCode().value()).isEqualTo(403);
    assertThat(((AdminBookingController.ErrorEnvelope) response.getBody()).code())
        .isEqualTo("CAPABILITY_REQUIRED");
  }

  @Test
  void requiresIfMatchBeforeAttemptingAnyReservationWrite() {
    var fixture = new Fixture(Set.of(AdminPermission.RESERVATION_CANCEL));
    var request = request("POST", "/bookings/reservations/" + RESERVATION + "/cancel");
    request.setContentType("application/json");
    request.setContent(
        "{\"reasonCode\":\"STAFF_REQUESTED\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    request.addHeader("Idempotency-Key", "k".repeat(32));

    var response = fixture.controller.cancel(request, RESERVATION);
    assertThat(response.getStatusCode().value()).isEqualTo(428);
    assertThat(((AdminBookingController.ErrorEnvelope) response.getBody()).code())
        .isEqualTo("IF_MATCH_REQUIRED");
  }

  @Test
  void rejectsUnexpectedAndDuplicateJsonFieldsBeforeCallingBookingApplication() {
    var fixture = new Fixture(Set.of(AdminPermission.RESERVATION_RESCHEDULE));
    for (String body :
        java.util.List.of(
            "{\"reasonCode\":\"STAFF_RESCHEDULED\",\"slotId\":\"00000000-0000-0000-0000-000000000002\",\"extra\":1}",
            "{\"reasonCode\":\"STAFF_RESCHEDULED\",\"reasonCode\":\"OTHER\",\"slotId\":\"00000000-0000-0000-0000-000000000002\"}")) {
      var request = request("POST", "/bookings/reservations/" + RESERVATION + "/reschedule");
      request.setContentType("application/json; charset=UTF-8");
      request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      request.addHeader("If-Match", "\"r0\"");
      request.addHeader("Idempotency-Key", "k".repeat(32));
      var response = fixture.controller.reschedule(request, RESERVATION);
      assertThat(response.getStatusCode().value()).isEqualTo(400);
      assertThat(((AdminBookingController.ErrorEnvelope) response.getBody()).code())
          .isIn("REQUEST_INVALID", "JSON_MALFORMED");
    }
  }

  @Test
  void duplicateSessionCookiesFailClosed() {
    var fixture = new Fixture(Set.of(AdminPermission.RESERVATION_READ));
    var request = request("GET", "/bookings");
    request.setCookies(
        new jakarta.servlet.http.Cookie(AdminAuthController.SESSION_COOKIE, TOKEN),
        new jakarta.servlet.http.Cookie(AdminAuthController.SESSION_COOKIE, TOKEN));
    assertThat(fixture.controller.pendingReservations(request).getStatusCode().value())
        .isEqualTo(401);
  }

  @Test
  void confirmRequiresConfirmCapabilityAndAnExplicitReviewedSlot() {
    var wrongPermission = new Fixture(Set.of(AdminPermission.RESERVATION_READ));
    var denied =
        wrongPermission.controller.confirm(
            request("POST", "/bookings/reservations/" + RESERVATION + "/confirm"), RESERVATION);
    assertThat(denied.getStatusCode().value()).isEqualTo(403);
    assertThat(((AdminBookingController.ErrorEnvelope) denied.getBody()).code())
        .isEqualTo("CAPABILITY_REQUIRED");

    var fixture = new Fixture(Set.of(AdminPermission.RESERVATION_CONFIRM));
    var noMatch = confirmRequest("{\"slotId\":\"00000000-0000-0000-0000-000000000002\"}");
    var missingRevision = fixture.controller.confirm(noMatch, RESERVATION);
    assertThat(missingRevision.getStatusCode().value()).isEqualTo(428);
    assertThat(((AdminBookingController.ErrorEnvelope) missingRevision.getBody()).code())
        .isEqualTo("IF_MATCH_REQUIRED");

    // Q02: the operator must name the slot being held; an unreviewed request is not accepted.
    var missingSlot = confirmRequest("{\"reasonCode\":\"REVIEWED\"}");
    missingSlot.addHeader("If-Match", "\"r0\"");
    var invalid = fixture.controller.confirm(missingSlot, RESERVATION);
    assertThat(invalid.getStatusCode().value()).isEqualTo(400);
    assertThat(((AdminBookingController.ErrorEnvelope) invalid.getBody()).code())
        .isEqualTo("REQUEST_INVALID");
  }

  @Test
  void rejectRequiresRejectCapabilityRevisionAndAReasonButNeverASlot() {
    var wrongPermission = new Fixture(Set.of(AdminPermission.RESERVATION_CONFIRM));
    var denied =
        wrongPermission.controller.reject(
            request("POST", "/bookings/reservations/" + RESERVATION + "/reject"), RESERVATION);
    assertThat(denied.getStatusCode().value()).isEqualTo(403);
    assertThat(((AdminBookingController.ErrorEnvelope) denied.getBody()).code())
        .isEqualTo("CAPABILITY_REQUIRED");

    var fixture = new Fixture(Set.of(AdminPermission.RESERVATION_REJECT));
    var missingRevision =
        fixture.controller.reject(rejectRequest("{\"reasonCode\":\"DECLINED\"}"), RESERVATION);
    assertThat(missingRevision.getStatusCode().value()).isEqualTo(428);

    // Refusing a request must not be able to name a slot, so a slot in the body is rejected.
    var withSlot = rejectRequest("{\"slotId\":\"00000000-0000-0000-0000-000000000002\"}");
    withSlot.addHeader("If-Match", "\"r1\"");
    var invalid = fixture.controller.reject(withSlot, RESERVATION);
    assertThat(invalid.getStatusCode().value()).isEqualTo(400);
    assertThat(((AdminBookingController.ErrorEnvelope) invalid.getBody()).code())
        .isEqualTo("REQUEST_INVALID");

    var notJson = request("POST", "/bookings/reservations/" + RESERVATION + "/reject");
    notJson.addHeader("If-Match", "\"r1\"");
    notJson.addHeader("Idempotency-Key", "k".repeat(32));
    assertThat(fixture.controller.reject(notJson, RESERVATION).getStatusCode().value())
        .isEqualTo(415);
  }

  private static MockHttpServletRequest rejectRequest(String body) {
    var request = request("POST", "/bookings/reservations/" + RESERVATION + "/reject");
    request.setContentType("application/json");
    request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    request.addHeader("Idempotency-Key", "k".repeat(32));
    return request;
  }

  private static MockHttpServletRequest confirmRequest(String body) {
    var request = request("POST", "/bookings/reservations/" + RESERVATION + "/confirm");
    request.setContentType("application/json");
    request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    request.addHeader("Idempotency-Key", "k".repeat(32));
    return request;
  }

  private static MockHttpServletRequest request(String method, String path) {
    var request = new MockHttpServletRequest(method, path);
    request.setSecure(true);
    request.setCookies(new jakarta.servlet.http.Cookie(AdminAuthController.SESSION_COOKIE, TOKEN));
    return request;
  }

  private static final class Fixture {
    private final AdminBookingController controller;

    private Fixture(Set<AdminPermission> permissions) {
      var sessions =
          new AdminSessionService(
              new Sessions(permissions), new FixedTokenCodec(), CLOCK, Duration.ofHours(2));
      controller = new AdminBookingController((BookingApplication) null, sessions);
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
}
