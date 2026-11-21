const userApi = require("../../api/user.js");
const { gaps } = require("../../user/profile.js");
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
  const gap = gaps(profile);
  const label = profile && profile.displayLabel ? profile.displayLabel : "";
  return {
    ...data,
    cards,
    copy: ready(data.copy),
    identityLabel: label,
    identityInitial: label ? String(label).slice(0, 1) : "你",
    nickname: gap.nickname,
    hasAvatar: gap.hasAvatar,
    avatarSrc: data.avatarSrc || "",
    showIdentity: ["success", "empty"].includes(data.status),
    sheetOpen: Boolean(data.sheetOpen),
    askName: Boolean(data.askName),
    askAvatar: Boolean(data.askAvatar),
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
    sheetOpen: false,
    askName: false,
    askAvatar: false,
    hasAvatar: false,
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
    this._sheetDismissed = false;
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
        sheetOpen: false,
        errorMessage: "",
      });
      return;
    }
    if (version !== this._generation) return;
    const gap = gaps(profile);
    const sheetOpen = !gap.complete && !this._sheetDismissed;
    this.update({
      profile,
      copy,
      sheetOpen,
      askName: sheetOpen ? gap.askName : false,
      askAvatar: sheetOpen ? gap.askAvatar : false,
    });
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
  async onProfileSave(event) {
    const detail = (event && event.detail) || {};
    const patch = {};
    if (detail.avatarPath) patch.avatarPath = detail.avatarPath;
    if (this.data.askName) patch.displayName = (detail.displayName || "").trim();
    if (this.data.askName && !patch.displayName) return;
    if (this.data.askAvatar && !patch.avatarPath && !this.data.hasAvatar) return;
    try {
      const profile = await userApi.saveProfile(patch);
      const gap = gaps(profile);
      this._sheetDismissed = gap.complete;
      this.update({
        profile,
        sheetOpen: !gap.complete,
        askName: gap.complete ? false : gap.askName,
        askAvatar: gap.complete ? false : gap.askAvatar,
        avatarSrc: patch.avatarPath || this.data.avatarSrc,
      });
    } catch (error) {
      wx.showToast({ title: "没有保存", icon: "none" });
    }
  },
  onProfileDismiss() {
    this._sheetDismissed = true;
    this.update({ sheetOpen: false });
  },
  handleProfileTap() {
    if (!this.data.profile) return;
    const gap = gaps(this.data.profile);
    this.update({
      sheetOpen: true,
      askName: gap.complete || gap.askName,
      askAvatar: gap.complete || gap.askAvatar,
    });
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
