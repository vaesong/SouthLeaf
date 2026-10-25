const booking = require("../../api/booking.js");
const { commit } = require("../../presentation/page-state.js");
const { slotLabel } = require("../../presentation/booking-views.js");

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
        status: "error",
        errorMessage: error.message || "预约暂时无法加载",
      });
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
        status: "error",
        errorMessage: error.message || "时段暂时无法加载",
      });
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
});
