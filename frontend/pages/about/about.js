const { getBrand } = require("../../api/public.js");
const { showToast } = require("../../api/api.js");
const { commit } = require("../../presentation/page-state.js");
const { brandView, teamView } = require("../../presentation/views.js");
function present(data) {
  return {
    ...data,
    channels: data.brand.contact?.channels || [],
    team: teamView(data.brand.team || []),
    brand: brandView(data.brand),
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
    dataMode: "",
  }),
  update(patch) {
    commit(this, present, patch);
  },
  onHide() {
    this._hidden = true;
    this._generation++;
    this.update({
      brand: {},
      status: "loading",
    });
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
      title: this.data.brand.shareTitle || "摄影工作室",
      path: "/pages/index/index",
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
    this.update({
      status: "loading",
    });
    this.update({
      brand: {},
    });
    this.update({
      brandLogoFailed: false,
    });
    this.update({
      failedMedia: {},
    });
    try {
      const brand = await getBrand();
      if (version !== this._generation) return;
      this.update({
        brand: brand,
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
  callPhone() {
    if (this.data.brand.phone)
      wx.makePhoneCall({
        phoneNumber: this.data.brand.phone,
      });
    else showToast("联系电话暂未公开");
  },
  copyChannel(channel) {
    if (channel.account)
      wx.setClipboardData({
        data: channel.account,
        fail: () => showToast("复制失败，请手动记录账号"),
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
    if (
      this.data.status !== "success" ||
      channel.qrCodeStatus !== "CONFIRMED" ||
      !channel.qrCodeMedia ||
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
        fail: () => showToast("复制失败，请手动记录地址"),
      });
  },
  goHome() {
    wx.switchTab({
      url: "/pages/index/index",
    });
  },
  onShow() {
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
