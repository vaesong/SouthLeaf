const booking = require("../../api/booking.js");
const { commit } = require("../../presentation/page-state.js");
const views = require("../../presentation/booking-views.js");
const settlement = require("../../presentation/settlement.js");

const SESSION_ERRORS = [
  "BOOKING_SESSION_REQUIRED",
  "BOOKING_IDENTITY_REJECTED",
];

const isSessionError = (error) =>
  Boolean(error && SESSION_ERRORS.indexOf(error.code) >= 0);

function present(data) {
  const cards = (data.requests || []).map(views.requestCard);
  const profile = data.profile || null;
  return {
    ...data,
    cards,
    identityLabel:
      profile && profile.displayName ? profile.displayName : "微信用户",
    identityExpires: views.timeLabel(profile && profile.expiresAt),
    settlementLabel: settlement.settlementLabel(),
    orderCount: cards.filter((card) => card.hasOrder).length,
  };
}

Page({
  data: present({
    status: "loading",
    profile: null,
    requests: [],
    cards: [],
    identityLabel: "",
    identityExpires: "",
    orderCount: 0,
    settlementLabel: "",
    errorMessage: "",
    dataMode: "",
  }),
  update(patch) {
    commit(this, present, patch);
  },
  onLoad() {
    this._generation = 0;
    this._hidden = false;
    this._unloaded = false;
    this.load();
  },
  onHide() {
    this._hidden = true;
    this._generation++;
    this.update({ status: "loading", dataMode: "" });
  },
  onUnload() {
    this._unloaded = true;
    this._generation++;
  },
  onShow() {
    if (this._hidden) {
      this._hidden = false;
      this.load();
    }
  },
  async onPullDownRefresh() {
    try {
      await this.load();
    } finally {
      wx.stopPullDownRefresh();
    }
  },
  async load() {
    const version = ++this._generation;
    this.update({ status: "loading", errorMessage: "" });
    let profile;
    try {
      profile = await booking.currentSession();
    } catch (error) {
      if (version !== this._generation) return;
      this.update({
        profile: null,
        requests: [],
        status: isSessionError(error) ? "unauthenticated" : "error",
        errorMessage: (error && error.message) || "暂时无法确认身份",
      });
      return;
    }
    if (version !== this._generation) return;
    this.update({ profile });
    try {
      const response = await booking.getRequests();
      if (version !== this._generation) return;
      const items = response.data.items || [];
      this.update({
        requests: items,
        dataMode: response.meta.dataMode,
        status: items.length ? "success" : "empty",
      });
    } catch (error) {
      if (version !== this._generation) return;
      this.update({
        requests: [],
        status: isSessionError(error) ? "unauthenticated" : "error",
        errorMessage: (error && error.message) || "预约列表暂时无法加载",
      });
    }
  },
  handleRetryTap() {
    this.load();
  },
  handleNewBookingTap() {
    wx.navigateTo({ url: "/pages/booking/booking" });
  },
  handleCardTap(event) {
    const reservationId = event.currentTarget.dataset.id;
    if (!reservationId) return;
    wx.navigateTo({
      url:
        "/pages/booking/requestdetail?reservationId=" +
        encodeURIComponent(reservationId),
    });
  },
  // 阶段二不收款：这里只解释，不发请求、不改状态、不留痕迹。
  handleSettleTap(event) {
    const reservationId = event.currentTarget.dataset.id;
    const card = (this.data.cards || []).find(
      (item) => item.reservationId === reservationId,
    );
    if (!card || !card.payAction.visible) return;
    wx.showModal({
      title: settlement.LABELS.action,
      content: settlement.blockedNotice(),
      showCancel: false,
      confirmText: "知道了",
    });
  },
});
