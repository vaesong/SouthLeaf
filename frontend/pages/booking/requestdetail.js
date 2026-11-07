const booking = require("../../api/booking.js");
const { getBrand } = require("../../api/public.js");
const { ready, showing } = require("../../presentation/copy.js");
const { commit } = require("../../presentation/page-state.js");
const views = require("../../presentation/booking-views.js");
const settlement = require("../../presentation/settlement.js");

function present(data) {
  const request = data.request || {};
  const status = views.statusOf(request.displayStatus, data.copy);
  return {
    ...data,
    statusLabel: status.label,
    statusHint: status.hint,
    slotLabel: views.slotLabel(request.requestedSlot),
    hasOrder: Boolean(request.order),
    order: views.orderView(request.order),
    payAction: settlement.actionFor(request.order),
    settlementLabel: settlement.settlementLabel(),
    expiresAt: views.timeLabel(request.expiresAt),
    copy: ready(data.copy),
  };
}

Page({
  data: present({
    id: "",
    status: "loading",
    request: null,
    statusLabel: "",
    statusHint: "",
    slotLabel: "",
    hasOrder: false,
    order: null,
    payAction: settlement.actionFor(null),
    settlementLabel: "",
    expiresAt: "",
    errorMessage: "",
    copy: {},
    dataMode: "",
  }),
  update(patch) {
    commit(this, present, patch);
  },
  onLoad(options) {
    this._generation = 0;
    const id = options && options.reservationId;
    this.update({
      id: typeof id === "string" && /^[A-Za-z0-9-]{8,64}$/.test(id) ? id : "",
    });
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
    if (!this.data.id) {
      this.update({ status: "unavailable", errorMessage: "" });
      return;
    }
    const version = ++this._generation;
    if (!showing(this.data.status))
      this.update({ status: "loading", errorMessage: "" });
    let copy = {};
    try {
      const brand = await getBrand();
      copy = brand.copy || {};
    } catch (error) {
      copy = {};
    }
    if (version !== this._generation) return;
    try {
      const response = await booking.getRequest(this.data.id);
      if (version !== this._generation) return;
      this.update({
        request: response.data,
        copy,
        dataMode: response.meta.dataMode,
        status: "success",
      });
    } catch (error) {
      if (version !== this._generation) return;
      this.update({
        request: null,
        copy,
        status:
          error.code === "BOOKING_RESOURCE_UNAVAILABLE"
            ? "unavailable"
            : "error",
        errorMessage: "",
      });
    }
  },
  handleRetryTap() {
    this.load();
  },
  handleBackTap() {
    wx.navigateBack({
      fail: () => wx.switchTab({ url: "/pages/package/package" }),
    });
  },
  handleBookAgainTap() {
    wx.navigateTo({ url: "/pages/booking/booking" });
  },
});
