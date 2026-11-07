const { getBrand } = require("../../api/public.js");
const { showToast } = require("../../api/api.js");
const {
  applyTabs,
  clearTabs,
  ready,
  showing,
} = require("../../presentation/copy.js");
const { commit } = require("../../presentation/page-state.js");
const { brandView, teamView } = require("../../presentation/views.js");
function present(data) {
  return {
    ...data,
    channels: data.brand.contact?.channels || [],
    team: teamView(data.brand.team || []),
    brand: brandView(data.brand),
    copy: ready(data.copy),
    dataMode: ["success", "empty"].includes(data.status)
      ? (data.brand.meta?.dataMode ?? "")
      : "",
  };
}
Page({
  data: present({
    status: "loading",
    brand: {},
    brandLogoFailed: false,
    failedMedia: {},
    copy: {},
    dataMode: "",
  }),
  update(patch) {
    commit(this, present, patch);
  },
  onHide() {
    this._hidden = true;
    this._generation++;
  },
  onUnload() {
    this._unloaded = true;
    this._generation++;
  },
  async onPullDownRefresh() {
    try {
      await this.loadBrand();
    } finally {
      wx.stopPullDownRefresh();
    }
  },
  onShareAppMessage() {
    return {
      title: this.data.brand.shareTitle || "",
      path: "/pages/index/index",
      imageUrl: this.data.team?.[0]?.portraitMedia?.src || "",
    };
  },
  onLoad() {
    this._generation = 0;
    this._unloaded = false;
    this._hidden = false;
    this.loadBrand();
  },
  async loadBrand() {
    const version = ++this._generation;
    if (!showing(this.data.status)) this.update({ status: "loading" });
    try {
      const brand = await getBrand();
      if (version !== this._generation) return;
      this.update({
        brand,
        copy: brand.copy || {},
        status: "success",
        brandLogoFailed: false,
        failedMedia: {},
      });
      applyTabs(this, brand.copy);
    } catch (error) {
      if (version !== this._generation) return;
      this.update({
        brand: {},
        copy: {},
        status:
          error.code === "PUBLIC_RESOURCE_UNAVAILABLE"
            ? "unavailable"
            : "error",
      });
      clearTabs(this);
    }
  },
  callPhone() {
    if (this.data.brand.phone)
      wx.makePhoneCall({
        phoneNumber: this.data.brand.phone,
      });
    else return;
  },
  copyChannel(channel) {
    if (channel.account)
      wx.setClipboardData({
        data: channel.account,
        fail: () => {
          const title = this.data.copy.about.copyAccountFailed;
          if (title) showToast(title);
        },
      });
  },
  markMediaFailed(id) {
    this.update({
      failedMedia: {
        ...this.data.failedMedia,
        [id]: true,
      },
    });
  },
  previewQr(channel) {
    if (this.data.status !== "success" || !channel || !channel.qrCodeMedia)
      return;
    if (channel.qrCodeStatus === "PLACEHOLDER") {
      const title = this.data.copy.about && this.data.copy.about.placeholderQr;
      if (!title) return;
      wx.showToast({
        title,
        icon: "none",
        duration: 2000,
      });
      return;
    }
    if (
      channel.qrCodeStatus !== "CONFIRMED" ||
      this.data.failedMedia[`qr-${channel.id}`]
    )
      return;
    wx.previewImage({
      current: channel.qrCodeMedia.src,
      urls: [channel.qrCodeMedia.src],
    });
  },
  copyAddress() {
    if (this.data.brand.address)
      wx.setClipboardData({
        data: this.data.brand.address,
        fail: () => {
          const title = this.data.copy.about.copyAddressFailed;
          if (title) showToast(title);
        },
      });
  },
  goHome() {
    wx.switchTab({
      url: "/pages/index/index",
    });
  },
  onShow() {
    if (typeof this.getTabBar === "function" && this.getTabBar()) {
      this.getTabBar().setData({ selected: 3 });
    }
    applyTabs(this, this.data.copy);
    if (this._hidden) {
      this._hidden = false;
      this.loadBrand();
    }
  },
  handleGoHomeTap(_event) {
    this.goHome();
  },
  handleLoadBrandTap(_event) {
    this.loadBrand();
  },
  handleBrandLogoFailedError(_event) {
    this.update({
      brandLogoFailed: true,
    });
  },
  handleMarkMediaFailedError(event) {
    this.markMediaFailed(event.currentTarget.dataset.arg0);
  },
  handlePreviewQrTap(event) {
    this.previewQr(event.currentTarget.dataset.arg0);
  },
  handleMarkMediaFailedError2(event) {
    this.markMediaFailed(event.currentTarget.dataset.errorKey);
  },
  handleCopyChannelTap(event) {
    this.copyChannel(event.currentTarget.dataset.arg0);
  },
  handleCallPhoneTap(_event) {
    this.callPhone();
  },
  handleCopyAddressTap(_event) {
    this.copyAddress();
  },
});
