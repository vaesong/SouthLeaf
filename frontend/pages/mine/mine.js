const booking = require("../../api/booking.js");
const { getBrand } = require("../../api/public.js");
const {
  applyTabs,
  clearTabs,
  ready,
  showing,
} = require("../../presentation/copy.js");
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
  const cards = (data.requests || []).map((request) =>
    views.requestCard(request, data.copy),
  );
  const profile = data.profile || null;
  return {
    ...data,
    cards,
    copy: ready(data.copy),
    identityLabel: profile && profile.displayName ? profile.displayName : "",
    identityInitial:
      profile && profile.displayName
        ? String(profile.displayName).slice(0, 1)
        : "",
    showIdentity: ["success", "empty"].includes(data.status),
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
    copy: {},
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
  },
  onUnload() {
    this._unloaded = true;
    this._generation++;
  },
  onShow() {
    if (typeof this.getTabBar === "function" && this.getTabBar()) {
      this.getTabBar().setData({ selected: 4 });
    }
    applyTabs(this, this.data.copy);
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
  async publishedCopy() {
    try {
      const brand = await getBrand();
      return brand.copy || {};
    } catch (error) {
      clearTabs(this);
      return {};
    }
  },
  async load() {
    const version = ++this._generation;
    if (!showing(this.data.status))
      this.update({ status: "loading", errorMessage: "" });
    const copy = await this.publishedCopy();
    if (version !== this._generation) return;
    let profile;
    try {
      profile = await booking.currentSession();
    } catch (error) {
      if (version !== this._generation) return;
      this.update({
        profile: null,
        requests: [],
        copy,
        status: isSessionError(error) ? "unauthenticated" : "error",
        errorMessage: "",
      });
      if (copy.tabs) applyTabs(this, copy);
      return;
    }
    if (version !== this._generation) return;
    this.update({ profile, copy });
    try {
      const response = await booking.getRequests();
      if (version !== this._generation) return;
      const items = response.data.items || [];
      this.update({
        requests: items,
        copy,
        dataMode: response.meta.dataMode,
        status: items.length ? "success" : "empty",
      });
      applyTabs(this, copy);
    } catch (error) {
      if (version !== this._generation) return;
      this.update({
        requests: [],
        copy,
        status: isSessionError(error) ? "unauthenticated" : "error",
        errorMessage: "",
      });
      applyTabs(this, copy);
    }
  },
  handleRetryTap() {
    this.load();
  },
  handleNewBookingTap() {
    wx.navigateTo({ url: "/pages/booking/booking" });
  },
  handleServicesTap() {
    wx.switchTab({ url: "/pages/package/package" });
  },
  handleAboutTap() {
    wx.switchTab({ url: "/pages/about/about" });
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
});
