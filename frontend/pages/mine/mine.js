const userApi = require("../../api/user.js");
const { getBrand } = require("../../api/public.js");
const { ready, showing } = require("../../presentation/copy.js");
const { commit } = require("../../presentation/page-state.js");
const views = require("../../presentation/booking-views.js");
const settlement = require("../../presentation/settlement.js");

function present(data) {
  const cards = (data.requests || []).map((request) =>
    views.requestCard(request, data.copy),
  );
  const profile = data.profile || null;
  const label = profile && profile.displayLabel ? profile.displayLabel : "";
  return {
    ...data,
    cards,
    copy: ready(data.copy),
    identityLabel: label,
    identityInitial: label ? String(label).slice(0, 1) : "你",
    nickname: profile && profile.displayName ? profile.displayName : "",
    shortId: profile && !profile.named ? profile.shortId : "",
    avatarSrc: data.avatarSrc || "",
    showIdentity: ["success", "empty"].includes(data.status),
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
    require("../../user/track.js").enter(this).then(() => this.load()).catch(() => {});
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
    require("../../user/track.js").enter(this).catch(() => {});
    if (typeof this.getTabBar === "function" && this.getTabBar()) {
      this.getTabBar().setData({ selected: 4 });
    }
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
      return ready(brand.copy);
    } catch (error) {
      return ready(null);
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
      profile = (await userApi.profile()).data;
    } catch (error) {
      if (version !== this._generation) return;
      this.update({
        profile: null,
        requests: [],
        copy,
        status: "error",
        errorMessage: "",
      });
      return;
    }
    if (version !== this._generation) return;
    this.update({ profile, copy });
    if (profile.hasAvatar) this.loadAvatar();
    try {
      const response = await userApi.reservations();
      if (version !== this._generation) return;
      const items = response.data.items || [];
      this.update({
        requests: items,
        copy,
        dataMode: response.meta.dataMode,
        status: items.length ? "success" : "empty",
      });
    } catch (error) {
      if (version !== this._generation) return;
      this.update({
        requests: [],
        copy,
        status: "error",
        errorMessage: "",
      });
    }
  },
  loadAvatar() {
    const value = wx.getStorageSync("southleaf.user.session");
    if (!value) return;
    wx.request({
      url: `${require("../../api/api.js").getBaseURL()}/api/v1/public/users/me/avatar`,
      header: { Authorization: `Bearer ${value}` },
      responseType: "arraybuffer",
      success: (response) => {
        if (response.statusCode !== 200) return;
        const path = `${wx.env.USER_DATA_PATH}/user-avatar.jpg`;
        wx.getFileSystemManager().writeFile({
          filePath: path,
          data: response.data,
          success: () => this.update({ avatarSrc: path }),
        });
      },
    });
  },
  async onChooseAvatar(event) {
    const filePath = event.detail && event.detail.avatarUrl;
    if (!filePath) return;
    try {
      await userApi.uploadAvatar(filePath);
      this.update({ avatarSrc: filePath });
    } catch (error) {
      wx.showToast({ title: "这张头像无法使用", icon: "none" });
    }
  },
  async onNickname(event) {
    const value = event.detail && event.detail.value ? event.detail.value : "";
    try {
      const profile = (await userApi.rename(value)).data;
      this.update({ profile });
    } catch (error) {
      wx.showToast({ title: "昵称没有保存", icon: "none" });
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
