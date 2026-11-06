const booking = require("../../api/booking.js");
const { getBrand } = require("../../api/public.js");
const { commit } = require("../../presentation/page-state.js");
const { slotLabel } = require("../../presentation/booking-views.js");

const CARE_CODES = [
  "PUBLIC_SERVICE_UNAVAILABLE",
  "BOOKING_SERVICE_UNAVAILABLE",
  "BOOKING_RULES_UNCONFIGURED",
  "BOOKING_IDENTITY_PROVIDER_UNCONFIGURED",
  "BOOKING_PACKAGE_CATALOG_UNCONFIGURED",
];
const careStatus = (error) =>
  CARE_CODES.indexOf(error && error.code) >= 0 ? "unavailable" : "error";

const two = (value) => (value < 10 ? "0" + value : "" + value);
const dayOffset = (days) => {
  const date = new Date();
  date.setDate(date.getDate() + days);
  return (
    date.getFullYear() +
    "-" +
    two(date.getMonth() + 1) +
    "-" +
    two(date.getDate())
  );
};
const newKey = () =>
  "mp-" +
  Date.now().toString(36) +
  "-" +
  Math.random().toString(36).slice(2, 10);

function present(data) {
  return {
    ...data,
    slotRows: (data.slots || []).map((slot) => ({
      slotId: slot.slotId,
      label: slotLabel(slot),
      active: slot.slotId === data.selectedSlotId,
    })),
    packageLabels: (data.packages || []).map((item) => item.displayName),
  };
}

Page({
  data: present({
    status: "loading",
    errorMessage: "",
    error: "",
    message: "",
    packages: [],
    packageIndex: 0,
    packageLabels: [],
    from: "",
    to: "",
    slots: [],
    slotRows: [],
    selectedSlotId: "",
    submitting: false,
    dataMode: "",
    studioPhone: "",
    studioWechat: "",
  }),
  update(patch) {
    commit(this, present, patch);
  },
  onLoad() {
    this._generation = 0;
    this._pendingKey = "";
    this.update({ from: dayOffset(0), to: dayOffset(6) });
    this.load();
  },
  onHide() {
    this._hidden = true;
    this._generation++;
    this.update({
      status: "loading",
      selectedSlotId: "",
      error: "",
      message: "",
      dataMode: "",
    });
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
    try {
      const [packages, slots] = await Promise.all([
        booking.getPackages(),
        booking.getSlots(this.data.from, this.data.to),
      ]);
      if (version !== this._generation) return;
      this.update({
        packages: packages.data.items,
        slots: slots.data.items,
        dataMode: slots.meta.dataMode,
        status: packages.data.items.length ? "success" : "empty",
      });
    } catch (error) {
      if (version !== this._generation) return;
      this.update({
        status: careStatus(error),
        errorMessage: "",
      });
      this.loadStudioContact();
    }
  },
  async loadStudioContact() {
    const version = this._generation;
    try {
      const brand = await getBrand();
      if (version !== this._generation || this._unloaded) return;
      const channels =
        brand.contact && Array.isArray(brand.contact.channels)
          ? brand.contact.channels
          : [];
      const wechat = channels.find((item) => item.platform === "wechat");
      this.update({
        studioPhone: brand.phone || "",
        studioWechat: wechat && wechat.account ? wechat.account : "",
      });
    } catch (_error) {
      /* 联系方式暂时读不到时，关怀卡仍保留拨打和返回作品。 */
    }
  },
  async reloadSlots() {
    const version = ++this._generation;
    this.update({ status: "loading" });
    try {
      const response = await booking.getSlots(this.data.from, this.data.to);
      if (version !== this._generation) return;
      this.update({
        slots: response.data.items,
        dataMode: response.meta.dataMode,
        status: "success",
      });
    } catch (error) {
      if (version !== this._generation) return;
      this.update({
        status: careStatus(error),
        errorMessage: "",
      });
      this.loadStudioContact();
    }
  },
  async submit() {
    if (!this.data.selectedSlotId || this.data.submitting) return;
    const pkg = this.data.packages[this.data.packageIndex];
    if (!pkg) {
      this.update({ error: "请先选择套餐" });
      return;
    }
    const version = ++this._generation;
    this._pendingKey = this._pendingKey || newKey();
    this.update({ submitting: true, error: "", message: "" });
    try {
      const response = await booking.createRequest(
        this._pendingKey,
        this.data.selectedSlotId,
        pkg.packageVersionRef,
      );
      if (version !== this._generation) return;
      this._pendingKey = "";
      this.update({
        submitting: false,
        selectedSlotId: "",
        message: "申请已提交，等待工作室确认",
      });
      wx.navigateTo({
        url:
          "/pages/booking/requestdetail?reservationId=" +
          encodeURIComponent(response.data.reservationId),
      });
    } catch (error) {
      if (version !== this._generation) return;
      this.update({
        submitting: false,
        error: error.message || "提交失败，请稍后重试",
      });
    }
  },
  handlePackageChange(event) {
    this.update({ packageIndex: Number(event.detail.value) || 0 });
  },
  handlePackagePillTap(event) {
    const index = Number(event.currentTarget.dataset.index);
    if (!Number.isInteger(index) || index < 0) return;
    this.update({ packageIndex: index });
  },
  handleFromChange(event) {
    this.update({ from: event.detail.value, selectedSlotId: "" });
    this.reloadSlots();
  },
  handleToChange(event) {
    this.update({ to: event.detail.value, selectedSlotId: "" });
    this.reloadSlots();
  },
  handleSlotTap(event) {
    const slotId = event.currentTarget.dataset.slot;
    if (!slotId || slotId === this.data.selectedSlotId) return;
    this._pendingKey = newKey();
    this.update({ selectedSlotId: slotId, error: "" });
  },
  handleSubmitTap() {
    this.submit();
  },
  handleRetryTap() {
    this.load();
  },
  handleMineTap() {
    wx.switchTab({ url: "/pages/mine/mine" });
  },
  handleBackTap() {
    wx.navigateBack({
      fail: () => wx.switchTab({ url: "/pages/package/package" }),
    });
  },
  handleCallPhoneTap() {
    const phone = this.data.studioPhone;
    if (!phone) {
      wx.showToast({
        title: "电话暂未公开，可以先复制微信",
        icon: "none",
      });
      return;
    }
    wx.makePhoneCall({ phoneNumber: phone });
  },
  handleCopyWechatTap() {
    const account = this.data.studioWechat;
    if (!account) {
      wx.showToast({ title: "微信号暂未公开，可以直接来电", icon: "none" });
      return;
    }
    wx.setClipboardData({
      data: account,
      fail: () =>
        wx.showToast({ title: "复制失败，请手动记录微信号", icon: "none" }),
    });
  },
  handleGoWorksTap() {
    wx.switchTab({ url: "/pages/works/works" });
  },
});
