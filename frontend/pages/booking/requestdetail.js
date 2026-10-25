const booking = require("../../api/booking.js");
const { commit } = require("../../presentation/page-state.js");

const STATUS_LABELS = {
  PENDING_CONFIRMATION: "待确认",
  CONFIRMED: "已确认",
  REJECTED: "未接受",
  EXPIRED: "已过期",
  CANCELLED: "已取消",
};

const STATUS_HINTS = {
  PENDING_CONFIRMATION: "工作室会人工复核时段，确认前不会占用档期。",
  CONFIRMED: "档期已确认，到店前请留意工作室的联系。",
  REJECTED: "这次申请未通过，可以重新选择时段再提交。",
  EXPIRED: "申请已超过有效期，可以重新提交。",
  CANCELLED: "该预约已取消。",
};

const slotLabel = (slot) =>
  slot
    ? slot.localDate + " " + slot.localStart + "-" + slot.localEnd
    : "未指定时段";

function present(data) {
  const request = data.request || {};
  return {
    ...data,
    statusLabel: STATUS_LABELS[request.displayStatus] || "状态未知",
    statusHint: STATUS_HINTS[request.displayStatus] || "",
    slotLabel: slotLabel(request.requestedSlot),
    hasOrder: Boolean(request.order),
    order: request.order || null,
    expiresAt: request.expiresAt
      ? String(request.expiresAt).slice(0, 16).replace("T", " ")
      : "",
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
  handleBookAgainTap() {
    wx.navigateTo({ url: "/pages/booking/booking" });
  },
});
