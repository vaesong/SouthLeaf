package cn.zhinan.admincontent.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Customer-visible mini-program lines. An empty value is omitted and renders as nothing. */
public final class CatalogPresentation {
  public static final int MAX_LINE = 400;
  public static final List<String> KEYS =
      List.of(
          "tabs.home",
          "tabs.works",
          "tabs.services",
          "tabs.about",
          "tabs.mine",
          "home.themesTitle",
          "home.storiesTitle",
          "home.allWorks",
          "home.packagesTitle",
          "home.allServices",
          "home.signOff",
          "home.aboutLink",
          "home.story",
          "about.title",
          "about.summary",
          "about.signOff",
          "about.boundary",
          "about.teamTitle",
          "about.teamSubtitle",
          "about.channelsTitle",
          "about.placeholderQr",
          "about.channelsEmpty",
          "about.phoneLabel",
          "about.call",
          "about.addressLabel",
          "about.copy",
          "about.hoursLabel",
          "about.qrHint",
          "about.copyAccountFailed",
          "about.copyAddressFailed",
          "works.title",
          "works.summary",
          "works.all",
          "works.empty",
          "works.sortNewest",
          "works.sortOldest",
          "works.loadMore",
          "works.layoutEditorial",
          "works.layoutGrid",
          "workDetail.dockTitle",
          "workDetail.dockSubtitle",
          "workDetail.book",
          "workDetail.share",
          "workDetail.photoSuffix",
          "workDetail.layoutAlbum",
          "workDetail.layoutOverview",
          "services.title",
          "services.summary",
          "services.view",
          "services.empty",
          "services.loadMore",
          "packageDetail.book",
          "packageDetail.share",
          "packageDetail.durationLabel",
          "packageDetail.includesMetric",
          "packageDetail.noticesMetric",
          "packageDetail.audiencePrefix",
          "packageDetail.includesTitle",
          "packageDetail.tipsTitle",
          "packageDetail.noticesTitle",
          "packageDetail.priceLabel",
          "packageDetail.priceInquiry",
          "packageDetail.priceFromMissing",
          "packageDetail.priceFixedMissing",
          "packageDetail.priceUnpublished",
          "booking.title",
          "booking.summary",
          "booking.closedTitle",
          "booking.closedBody",
          "booking.submitHint",
          "booking.pendingHint",
          "booking.confirmedHint",
          "booking.rejectedHint",
          "booking.expiredHint",
          "booking.cancelledHint",
          "booking.wechatLabel",
          "booking.copyWechat",
          "booking.copyWechatFailed",
          "booking.phoneUnavailable",
          "booking.wechatUnavailable",
          "booking.phoneLabel",
          "booking.call",
          "booking.backToWork",
          "booking.packagesTitle",
          "booking.dateTitle",
          "booking.rangeStart",
          "booking.rangeEnd",
          "booking.slotsTitle",
          "booking.submit",
          "booking.mineEntry",
          "booking.statusPending",
          "booking.statusConfirmed",
          "booking.statusRejected",
          "booking.statusExpired",
          "booking.statusCancelled",
          "mine.title",
          "mine.summary",
          "mine.welcome",
          "mine.signedOutTitle",
          "mine.signedOutHint",
          "mine.closedTitle",
          "mine.closedBody",
          "mine.empty",
          "mine.book",
          "mine.upcomingTitle",
          "mine.newBooking",
          "mine.holdPrefix",
          "mine.menuBooking",
          "mine.menuServices",
          "mine.menuAbout",
          "mine.scopeHint",
          "requestDetail.title",
          "requestDetail.holdPrefix",
          "requestDetail.requestedTitle",
          "requestDetail.confirmedTitle",
          "requestDetail.slotPrefix",
          "requestDetail.amountPrefix",
          "requestDetail.anotherSlot",
          "settlement.notice",
          "hero.fallbackSubtitle",
          "hero.fallbackAction");
  private static final Set<String> ALLOWED = Set.copyOf(KEYS);

  private CatalogPresentation() {}

  public static Map<String, String> sanitize(Map<String, String> raw) {
    if (raw == null) return Map.of();
    var kept = new LinkedHashMap<String, String>();
    for (var entry : raw.entrySet()) {
      String key = entry.getKey();
      if (key == null || !ALLOWED.contains(key)) {
        throw new ContentException(ContentFailure.INVALID_REQUEST, "PRESENTATION_KEY_UNKNOWN");
      }
      String value = entry.getValue() == null ? "" : entry.getValue();
      if (value.length() > MAX_LINE) {
        throw new ContentException(ContentFailure.INVALID_REQUEST, "PRESENTATION_LINE_TOO_LONG");
      }
      if (!value.isEmpty()) kept.put(key, value);
    }
    return Map.copyOf(kept);
  }
}
