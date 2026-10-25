const { getPackage } = require("../../api/public.js");
const { priceLabel: formatPriceLabel } = require("../../api/public-display.js");
const { commit } = require("../../presentation/page-state.js");
const { packageView } = require("../../presentation/views.js");
function present(data) {
  return {
    ...data,
    priceLabel: formatPriceLabel(data.pkg),
    pkg: packageView(data.pkg),
    dataMode: ["success", "empty"].includes(data.status)
      ? (data.pkg.meta?.dataMode ?? "")
      : "",
  };
}
Page({
  data: present({
    id: "",
    status: "loading",
    pkg: {
      serviceItems: [],
      tips: [],
      notices: [],
      contact: {},
    },
    mediaFailed: false,
    dataMode: "",
  }),
  update(patch) {
    commit(this, present, patch);
  },
  onHide() {
    this._hidden = true;
    this._generation++;
    this.update({
      pkg: {
        serviceItems: [],
        tips: [],
        notices: [],
        contact: {},
      },
      status: "loading",
    });
  },
  onUnload() {
    this._unloaded = true;
    this._generation++;
  },
  async onPullDownRefresh() {
    try {
      await this.loadPackage();
    } finally {
      wx.stopPullDownRefresh();
    }
  },
  onShareAppMessage() {
    return {
      title: this.data.pkg.name || "公开套餐",
      path: `/pages/package/pkgdetail?packageId=${encodeURIComponent(this.data.id)}`,
    };
  },
  onLoad(options) {
    this._generation = 0;
    this._unloaded = false;
    this._hidden = false;
    const id = options?.packageId;
    this.update({
      id: typeof id === "string" && id.trim().length <= 128 ? id.trim() : "",
    });
    this.loadPackage();
  },
  async loadPackage() {
    if (!this.data.id) {
      this.update({
        status: "unavailable",
      });
      return;
    }
    const version = ++this._generation;
    this.update({
      pkg: {
        serviceItems: [],
        tips: [],
        notices: [],
        contact: {},
      },
    });
    this.update({
      status: "loading",
    });
    this.update({
      mediaFailed: false,
    });
    try {
      const pkg = await getPackage(this.data.id);
      if (version !== this._generation) return;
      this.update({
        pkg: pkg,
      });
      this.update({
        status: "success",
      });
    } catch (error) {
      if (version !== this._generation) return;
      this.update({
        status:
          error.code === "PUBLIC_RESOURCE_UNAVAILABLE"
            ? "unavailable"
            : "error",
      });
    }
  },
  goBack() {
    wx.navigateBack({
      fail: () =>
        wx.switchTab({
          url: "/pages/package/package",
        }),
    });
  },
  onShow() {
    if (this._hidden) {
      this._hidden = false;
      this.loadPackage();
    }
  },
  handleGoBackTap(_event) {
    this.goBack();
  },
  handleGoBackTap2(_event) {
    this.goBack();
  },
  handleLoadPackageTap(_event) {
    this.loadPackage();
  },
  handleBookingTap(_event) {
    wx.navigateTo({
      url: "/pages/booking/booking",
    });
  },
  handleMediaFailedError(_event) {
    this.update({
      mediaFailed: true,
    });
  },
});
