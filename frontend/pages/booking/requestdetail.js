const booking = require("../../api/booking.js");
const { commit } = require("../../presentation/page-state.js");
const views = require("../../presentation/booking-views.js");
const settlement = require("../../presentation/settlement.js");

function present(data) {
  const request = data.request || {};
  const status = views.statusOf(request.displayStatus);
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
    this.update({ request: null, status: "loading", dataMode: "" });
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
      this.update({ status: "unavailable", errorMessage: "预约记录不存在" });
      return;
    }
    const version = ++this._generation;
    this.update({ status: "loading", errorMessage: "" });
    try {
      const response = await booking.getRequest(this.data.id);
      if (version !== this._generation) return;
      this.update({
        request: response.data,
        dataMode: response.meta.dataMode,
        status: "success",
      });
    } catch (error) {
      if (version !== this._generation) return;
      this.update({
        status:
          error.code === "BOOKING_RESOURCE_UNAVAILABLE"
            ? "unavailable"
            : "error",
        errorMessage: error.message || "预约记录暂时无法加载",
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
  // 阶段二不收款：这里只解释，不发请求、不改状态。
  handleSettleTap() {
    if (!this.data.payAction || !this.data.payAction.visible) return;
    wx.showModal({
      title: settlement.LABELS.action,
      content: settlement.blockedNotice(),
      showCancel: false,
      confirmText: "知道了",
    });
  },
  handleBookAgainTap() {
    wx.navigateTo({ url: "/pages/booking/booking" });
  },
});
