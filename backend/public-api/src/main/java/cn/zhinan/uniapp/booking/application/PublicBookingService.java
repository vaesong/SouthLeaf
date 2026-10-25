package cn.zhinan.uniapp.booking.application;

import cn.zhinan.uniapp.booking.PublicBookingException;
import cn.zhinan.uniapp.booking.config.PublicBookingProperties;
import cn.zhinan.uniapp.booking.domain.PublicBookingRules;
import cn.zhinan.uniapp.booking.domain.PublicBookingViews.OrderView;
import cn.zhinan.uniapp.booking.domain.PublicBookingViews.PackageList;
import cn.zhinan.uniapp.booking.domain.PublicBookingViews.PackageView;
import cn.zhinan.uniapp.booking.domain.PublicBookingViews.RequestList;
import cn.zhinan.uniapp.booking.domain.PublicBookingViews.RequestView;
import cn.zhinan.uniapp.booking.domain.PublicBookingViews.SessionView;
import cn.zhinan.uniapp.booking.domain.PublicBookingViews.SlotPage;
import cn.zhinan.uniapp.booking.domain.PublicBookingViews.SlotView;
import cn.zhinan.uniapp.booking.identity.SubjectExchange;
import cn.zhinan.uniapp.booking.port.PublicBookingPorts.CommandRow;
import cn.zhinan.uniapp.booking.port.PublicBookingPorts.IdentityRow;
import cn.zhinan.uniapp.booking.port.PublicBookingPorts.OrderRow;
import cn.zhinan.uniapp.booking.port.PublicBookingPorts.Repository;
import cn.zhinan.uniapp.booking.port.PublicBookingPorts.RequestDraft;
import cn.zhinan.uniapp.booking.port.PublicBookingPorts.RequestRow;
import cn.zhinan.uniapp.booking.port.PublicBookingPorts.SlotRow;
import cn.zhinan.uniapp.booking.session.PublicSessionTokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Customer intake use cases. A request is a promise to review, never a hold: it occupies no slot,
 * creates no order and carries no payment state.
 */
public final class PublicBookingService {
  public static final String SETTLEMENT_NOTICE = "本阶段不收款，金额仅作记录；到店前请与工作室确认。";
  private static final int SLOT_LIMIT = 120;
  private static final int REQUEST_LIMIT = 20;

  private final PublicBookingProperties properties;
  private final Repository repository;
  private final SubjectExchange exchange;
  private final PublicSessionTokens tokens;
  private final Clock clock;

  public PublicBookingService(
      PublicBookingProperties properties,
      Repository repository,
      SubjectExchange exchange,
      PublicSessionTokens tokens,
      Clock clock) {
    this.properties = properties;
    this.repository = repository;
    this.exchange = exchange;
    this.tokens = tokens;
    this.clock = clock;
  }

  public boolean enabled() {
    return properties.enabled();
  }

  public String dataMode() {
    return properties.publicDataMode();
  }

  public SessionView startSession(String loginCode) {
    ensureEnabled();
    String code = PublicBookingRules.loginCode(loginCode);
    String provider = exchange.provider();
    String subject = exchange.subject(code);
    String fingerprint = PublicBookingRules.fingerprint(provider, properties.appScope(), subject);
    Instant now = clock.instant();
    IdentityRow identity =
        repository.inTransaction(
            () ->
                repository
                    .findIdentity(provider, properties.appScope(), fingerprint)
                    .orElseGet(
                        () ->
                            repository.createCustomer(
                                UUID.randomUUID().toString(),
                                displayName(fingerprint),
                                provider,
                                properties.appScope(),
                                fingerprint,
                                now)));
    PublicSessionTokens.Issued issued = tokens.issue(identity.customerId(), now);
    return new SessionView(
        "cust_" + identity.customerId().substring(0, 8),
        identity.displayName(),
        issued.token(),
        issued.expiresAt().toString(),
        provider);
  }

  /** What a customer may ask for. The list is published configuration, never a guess. */
  public PackageList packages() {
    ensureEnabled();
    return new PackageList(
        properties.packages().stream()
            .map(entry -> new PackageView(entry.getKey(), entry.getValue()))
            .toList());
  }

  public SlotPage openSlots(String from, String to) {
    ensureEnabled();
    Instant now = clock.instant();
    Instant[] window = PublicBookingRules.window(from, to, properties.businessZone(), now);
    List<SlotView> items =
        repository.listAvailableSlots(window[0], window[1], SLOT_LIMIT).stream()
            .map(PublicBookingService::slotView)
            .toList();
    return new SlotPage(items, from, to);
  }

  public RequestView createRequest(
      String bearer, String idempotencyKey, String slotId, String packageVersionRef) {
    ensureEnabled();
    String customerId = customerId(bearer);
    String key = PublicBookingRules.idempotencyKey(idempotencyKey);
    String slot = PublicBookingRules.slotId(slotId);
    String packageRef = PublicBookingRules.packageVersionRef(packageVersionRef);
    if (!properties.allowsPackage(packageRef))
      throw PublicBookingRules.invalid("PACKAGE_REF_UNKNOWN");
    String actor = "public:" + customerId;
    String digest =
        PublicBookingRules.digest(customerId, packageRef, slot, PublicBookingRules.SOURCE);
    Instant now = clock.instant();
    try {
      return repository.inTransaction(
          () -> {
            var stored = repository.findCommand(actor, PublicBookingRules.OPERATION_CREATE, key);
            if (stored.isPresent()) return replay(customerId, stored.get(), digest, now);
            repository
                .findOpenReservationId(customerId, now)
                .ifPresent(
                    open -> {
                      throw new PublicBookingException(
                          PublicBookingException.ErrorCode.REQUEST_ALREADY_OPEN, open);
                    });
            if (!repository.slotAvailable(slot))
              throw new PublicBookingException(
                  PublicBookingException.ErrorCode.SLOT_UNAVAILABLE, "SLOT_UNAVAILABLE");
            String reservationId = UUID.randomUUID().toString();
            repository.insertRequest(
                new RequestDraft(
                    reservationId,
                    UUID.randomUUID().toString(),
                    customerId,
                    packageRef,
                    PublicBookingRules.SOURCE,
                    now.plus(properties.requestLifetime()),
                    slot,
                    now,
                    actor,
                    PublicBookingRules.OPERATION_CREATE,
                    key,
                    digest));
            return repository
                .findRequest(customerId, reservationId)
                .map(row -> view(row, now))
                .orElseThrow(PublicBookingService::unavailable);
          });
    } catch (PublicBookingException conflict) {
      if ("CONCURRENT_INTAKE".equals(conflict.getMessage()))
        return repository
            .findCommand(actor, PublicBookingRules.OPERATION_CREATE, key)
            .map(stored -> replay(customerId, stored, digest, now))
            .orElseThrow(() -> conflict);
      throw conflict;
    }
  }

  public RequestList requests(String bearer) {
    ensureEnabled();
    String customerId = customerId(bearer);
    Instant now = clock.instant();
    List<RequestView> items =
        repository.listRequests(customerId, REQUEST_LIMIT).stream()
            .map(row -> view(row, now))
            .toList();
    return new RequestList(items);
  }

  public RequestView request(String bearer, String reservationId) {
    ensureEnabled();
    String customerId = customerId(bearer);
    String id = PublicBookingRules.reservationId(reservationId);
    return repository
        .findRequest(customerId, id)
        .map(row -> view(row, clock.instant()))
        .orElseThrow(
            () ->
                new PublicBookingException(
                    PublicBookingException.ErrorCode.RESOURCE_UNAVAILABLE, "REQUEST_NOT_FOUND"));
  }

  private RequestView replay(String customerId, CommandRow stored, String digest, Instant now) {
    if (!stored.requestSha256().equals(digest))
      throw PublicBookingRules.invalid("IDEMPOTENCY_KEY_REUSE");
    return repository
        .findRequest(customerId, stored.resultRef())
        .map(row -> view(row, now))
        .orElseThrow(PublicBookingService::unavailable);
  }

  private RequestView view(RequestRow row, Instant now) {
    return new RequestView(
        row.reservationId(),
        row.status(),
        PublicBookingRules.displayStatus(row.status(), row.expiresAt(), now),
        PublicBookingRules.instant(row.createdAt()),
        PublicBookingRules.instant(row.expiresAt()),
        row.packageVersionRef(),
        row.requestedSlot() == null ? null : slotView(row.requestedSlot()),
        orderView(row.order()));
  }

  private static SlotView slotView(SlotRow slot) {
    var zone = PublicBookingRules.zone(slot.businessZone(), slot.displayOffset());
    return new SlotView(
        slot.slotId(),
        PublicBookingRules.instant(slot.startAt()),
        PublicBookingRules.instant(slot.endAt()),
        PublicBookingRules.localDate(slot.startAt(), zone),
        PublicBookingRules.localTime(slot.startAt(), zone),
        PublicBookingRules.localTime(slot.endAt(), zone),
        slot.displayOffset());
  }

  private static OrderView orderView(OrderRow order) {
    if (order == null) return null;
    var zone = PublicBookingRules.zone(order.businessZone(), order.displayOffset());
    return new OrderView(
        order.orderId(),
        order.packageDisplayName(),
        order.currencyCode(),
        order.amountMinorUnits(),
        PublicBookingRules.amountLabel(order.currencyCode(), order.amountMinorUnits()),
        PublicBookingRules.slotLabel(order.startAt(), order.endAt(), zone),
        PublicBookingRules.instant(order.capturedAt()),
        SETTLEMENT_NOTICE);
  }

  private String customerId(String bearer) {
    Instant now = clock.instant();
    return tokens.customerId(bearer, now);
  }

  private static String displayName(String fingerprint) {
    return "微信用户 " + fingerprint.substring(0, 6).toUpperCase();
  }

  private void ensureEnabled() {
    if (!properties.enabled() || repository == null)
      throw new PublicBookingException(
          PublicBookingException.ErrorCode.SERVICE_UNAVAILABLE, "DISABLED");
  }

  private static PublicBookingException unavailable() {
    return new PublicBookingException(
        PublicBookingException.ErrorCode.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
  }

  /** Exposed for the isolated fixture only: the window the runtime advertises. */
  public Duration requestLifetime() {
    return properties.requestLifetime();
  }
}
