package cn.zhinan.uniapp.booking.domain;

import java.util.List;

/** Customer-facing projections. They never carry a payment state or a settlement instruction. */
public final class PublicBookingViews {
  private PublicBookingViews() {}

  public record SlotView(
      String slotId,
      String startAt,
      String endAt,
      String localDate,
      String localStart,
      String localEnd,
      String zoneLabel) {}

  public record SessionView(
      String customerRef, String displayName, String token, String expiresAt, String identity) {}

  public record OrderView(
      String orderId,
      String packageDisplayName,
      String currency,
      long amountMinorUnits,
      String amountLabel,
      String slotLabel,
      String capturedAt,
      String settlementNotice) {}

  public record RequestView(
      String reservationId,
      String status,
      String displayStatus,
      String createdAt,
      String expiresAt,
      String packageVersionRef,
      SlotView requestedSlot,
      OrderView order) {}

  public record SlotPage(List<SlotView> items, String requestedFrom, String requestedTo) {}

  public record RequestList(List<RequestView> items) {}

  public record PackageView(String packageVersionRef, String displayName) {}

  public record PackageList(List<PackageView> items) {}
}
