package cn.zhinan.uniapp.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.uniapp.booking.application.PublicBookingService;
import cn.zhinan.uniapp.booking.config.PublicBookingProperties;
import cn.zhinan.uniapp.booking.identity.TestSinkSubjectExchange;
import cn.zhinan.uniapp.booking.port.PublicBookingPorts.SlotRow;
import cn.zhinan.uniapp.booking.session.PublicSessionTokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PublicBookingServiceTest {
  private static final String REF = "pkgv-synthetic-1";
  private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private static PublicBookingProperties properties(String dataMode, String sink) {
    Map<String, String> env = new HashMap<>();
    env.put("SOUTHLEAF_PUBLIC_BOOKING_MODE", "enabled");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_DB_URL", "jdbc:mysql://127.0.0.1:3306/southleaf_admin");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_DB_USERNAME", "southleaf_public_booking_writer");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_DB_PASSWORD", "fixture-password");
    env.put(
        "SOUTHLEAF_PUBLIC_BOOKING_DB_EXPECTED_USER", "southleaf_public_booking_writer@localhost");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_DATA_MODE", dataMode);
    env.put("SOUTHLEAF_PUBLIC_BOOKING_IDENTITY_SINK", sink);
    env.put("SOUTHLEAF_PUBLIC_BOOKING_APP_SCOPE", "southleaf-mp");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_SESSION_SECRET", SECRET);
    env.put("SOUTHLEAF_PUBLIC_BOOKING_SESSION_MINUTES", "120");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_REQUEST_HOURS", "48");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_BUSINESS_ZONE", "Asia/Tokyo");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_PACKAGE_REFS", REF + "=虚构演示套餐");
    return PublicBookingProperties.load(env, "southleaf_public");
  }

  private static PublicBookingService service(FakeRepository repository) {
    PublicBookingProperties properties = properties("SYNTHETIC", "TEST");
    return new PublicBookingService(
        properties,
        repository,
        new TestSinkSubjectExchange(),
        new PublicSessionTokens(SECRET, properties.sessionLifetime()),
        CLOCK);
  }

  private static FakeRepository seeded() {
    FakeRepository repository = new FakeRepository();
    String slot = "11111111-2222-4333-8444-555555555555";
    repository.slots.put(
        slot,
        new SlotRow(
            slot,
            Instant.parse("2026-10-26T01:00:00Z"),
            Instant.parse("2026-10-26T02:30:00Z"),
            "Asia/Tokyo",
            "+09:00",
            "OPEN"));
    return repository;
  }

  @Test
  void firstLoginCreatesOneCustomerAndTheSecondReusesIt() {
    FakeRepository repository = seeded();
    PublicBookingService service = service(repository);
    var first = service.startSession("test-alice-0001");
    var second = service.startSession("test-alice-0001");
    assertThat(repository.identities).hasSize(1);
    assertThat(first.customerRef()).isEqualTo(second.customerRef());
    assertThat(first.identity()).isEqualTo("WECHAT");
    assertThat(first.displayName()).startsWith("微信用户 ");
    assertThat(first.token()).isNotBlank();
  }

  @Test
  void publishedPackagesAreReadFromConfigurationAndNeverInvented() {
    PublicBookingService service = service(seeded());
    var packages = service.packages();
    assertThat(packages.items()).hasSize(1);
    assertThat(packages.items().getFirst().packageVersionRef()).isEqualTo(REF);
    assertThat(packages.items().getFirst().displayName()).isEqualTo("虚构演示套餐");
    assertThatThrownBy(
            () ->
                service.createRequest(
                    service.startSession("test-alice-0002").token(),
                    "mp-order-0000",
                    "11111111-2222-4333-8444-555555555555",
                    "pkgv-not-published"))
        .isInstanceOf(PublicBookingException.class);
  }

  @Test
  void requestCreatesAPendingIntakeWithoutAnyOccupancyOrOrder() {
    FakeRepository repository = seeded();
    PublicBookingService service = service(repository);
    String token = service.startSession("test-alice-0001").token();
    var created =
        service.createRequest(token, "mp-order-0001", "11111111-2222-4333-8444-555555555555", REF);
    assertThat(created.status()).isEqualTo("PENDING_CONFIRMATION");
    assertThat(created.displayStatus()).isEqualTo("PENDING_CONFIRMATION");
    assertThat(created.order()).isNull();
    assertThat(created.requestedSlot().localDate()).isEqualTo("2026-10-26");
    assertThat(created.requestedSlot().localStart()).isEqualTo("10:00");
    assertThat(repository.audits).containsExactly("RESERVATION:CREATE:" + created.reservationId());
    assertThat(repository.outbox)
        .containsExactly("RESERVATION_PENDING_CONFIRMATION:" + created.reservationId());
    assertThat(repository.transactions).isEqualTo(2);
  }

  @Test
  void replayWithTheSameKeyAndDigestReturnsTheSameRequest() {
    FakeRepository repository = seeded();
    PublicBookingService service = service(repository);
    String token = service.startSession("test-alice-0001").token();
    String slot = "11111111-2222-4333-8444-555555555555";
    var first = service.createRequest(token, "mp-order-0001", slot, REF);
    var replay = service.createRequest(token, "mp-order-0001", slot, REF);
    assertThat(replay.reservationId()).isEqualTo(first.reservationId());
    assertThat(repository.requests).hasSize(1);
  }

  @Test
  void reusedKeyWithADifferentBodyIsRefused() {
    FakeRepository repository = seeded();
    String other = "99999999-2222-4333-8444-555555555555";
    repository.slots.put(
        other,
        new SlotRow(
            other,
            Instant.parse("2026-10-27T01:00:00Z"),
            Instant.parse("2026-10-27T02:30:00Z"),
            "Asia/Tokyo",
            "+09:00",
            "OPEN"));
    PublicBookingService service = service(repository);
    String token = service.startSession("test-alice-0001").token();
    service.createRequest(token, "mp-order-0001", "11111111-2222-4333-8444-555555555555", REF);
    assertThatThrownBy(() -> service.createRequest(token, "mp-order-0001", other, REF))
        .isInstanceOf(PublicBookingException.class);
  }

  @Test
  void secondOpenRequestIsRefusedAndAnUnpublishedPackageIsRefused() {
    FakeRepository repository = seeded();
    PublicBookingService service = service(repository);
    String token = service.startSession("test-alice-0001").token();
    assertThatThrownBy(
            () ->
                service.createRequest(
                    token, "mp-order-0002", "11111111-2222-4333-8444-555555555555", "other-ref"))
        .isInstanceOf(PublicBookingException.class)
        .extracting(error -> ((PublicBookingException) error).getErrorCode())
        .isEqualTo(PublicBookingException.ErrorCode.REQUEST_INVALID);
    service.createRequest(token, "mp-order-0003", "11111111-2222-4333-8444-555555555555", REF);
    assertThatThrownBy(
            () ->
                service.createRequest(
                    token, "mp-order-0004", "11111111-2222-4333-8444-555555555555", REF))
        .isInstanceOf(PublicBookingException.class)
        .extracting(error -> ((PublicBookingException) error).getErrorCode())
        .isEqualTo(PublicBookingException.ErrorCode.REQUEST_ALREADY_OPEN);
  }

  @Test
  void occupiedSlotIsRefusedAndUnknownRequestIsNotFound() {
    FakeRepository repository = seeded();
    PublicBookingService service = service(repository);
    String token = service.startSession("test-alice-0001").token();
    repository.slotAvailable = false;
    assertThatThrownBy(
            () ->
                service.createRequest(
                    token, "mp-order-0005", "11111111-2222-4333-8444-555555555555", REF))
        .isInstanceOf(PublicBookingException.class)
        .extracting(error -> ((PublicBookingException) error).getErrorCode())
        .isEqualTo(PublicBookingException.ErrorCode.SLOT_UNAVAILABLE);
    assertThatThrownBy(() -> service.request(token, "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"))
        .isInstanceOf(PublicBookingException.class)
        .extracting(error -> ((PublicBookingException) error).getErrorCode())
        .isEqualTo(PublicBookingException.ErrorCode.RESOURCE_UNAVAILABLE);
  }

  @Test
  void confirmedRequestCarriesTheAgreementAndNeverAPaymentState() {
    FakeRepository repository = seeded();
    PublicBookingService service = service(repository);
    String token = service.startSession("test-alice-0001").token();
    var created =
        service.createRequest(token, "mp-order-0006", "11111111-2222-4333-8444-555555555555", REF);
    repository.orders.put(
        created.reservationId(),
        new cn.zhinan.uniapp.booking.port.PublicBookingPorts.OrderRow(
            "order-1",
            "虚构演示套餐",
            "JPY",
            12345,
            Instant.parse("2026-10-26T01:00:00Z"),
            Instant.parse("2026-10-26T02:30:00Z"),
            "Asia/Tokyo",
            "+09:00",
            NOW));
    repository.requests.put(
        created.reservationId(),
        new cn.zhinan.uniapp.booking.port.PublicBookingPorts.RequestRow(
            created.reservationId(),
            "CONFIRMED",
            NOW,
            NOW.plus(Duration.ofHours(48)),
            REF,
            2,
            repository.slots.get("11111111-2222-4333-8444-555555555555"),
            repository.orders.get(created.reservationId())));
    var detail = service.request(token, created.reservationId());
    assertThat(detail.displayStatus()).isEqualTo("CONFIRMED");
    assertThat(detail.order().amountLabel()).isEqualTo("JP\u00a512,345");
    assertThat(detail.order().slotLabel()).isEqualTo("2026-10-26 10:00-11:30");
    assertThat(detail.order().settlementNotice()).contains("本阶段不收款");
  }

  @Test
  void disabledEntryRefusesEveryCall() {
    PublicBookingService service =
        new PublicBookingService(
            PublicBookingProperties.load(Map.of(), "southleaf_public"), null, null, null, CLOCK);
    assertThatThrownBy(() -> service.startSession("test-alice-0001"))
        .isInstanceOf(PublicBookingException.class)
        .extracting(error -> ((PublicBookingException) error).getErrorCode())
        .isEqualTo(PublicBookingException.ErrorCode.SERVICE_UNAVAILABLE);
    assertThat(service.enabled()).isFalse();
  }
}
