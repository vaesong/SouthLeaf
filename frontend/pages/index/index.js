const { getHome } = require("../../api/public.js");
const {
  applyTabs,
  clearTabs,
  ready,
  showing,
} = require("../../presentation/copy.js");
const { commit } = require("../../presentation/page-state.js");
const { homeView } = require("../../presentation/views.js");
const emptyHome = () => ({
  brand: {},
  heroItems: [],
  businesses: [],
  featuredWorks: [],
  packages: [],
  contact: {},
});
function present(data) {
  return {
    ...data,
    home: homeView(data.home, data.copy.packageDetail),
    copy: ready(data.copy),
    dataMode: ["success", "empty"].includes(data.status)
      ? (data.home.meta?.dataMode ?? "")
      : "",
  };
}
Page({
  data: present({
    status: "loading",
    home: {
      brand: {},
      heroItems: [],
      businesses: [],
      featuredWorks: [],
      packages: [],
      contact: {},
    },
    heroIndex: 0,
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
      await this.loadHome();
    } finally {
      wx.stopPullDownRefresh();
    }
  },
  onShareAppMessage() {
    return {
      title: this.data.home.brand.shareTitle || "",
      path: "/pages/index/index",
      imageUrl: this.data.home?.heroItems?.[0]?.media?.src || "",
    };
  },
  onLoad() {
    this._generation = 0;
    this._unloaded = false;
    this._hidden = false;
    this.loadHome();
  },
  async loadHome() {
    const version = ++this._generation;
    if (!showing(this.data.status)) this.update({ status: "loading" });
    try {
      const home = await getHome();
      if (version !== this._generation) return;
      this.update({
        home,
        copy: home.copy || {},
        status: "success",
        failedMedia: {},
      });
      applyTabs(this, home.copy);
    } catch (error) {
      if (version !== this._generation) return;
      this.update({
        home: emptyHome(),
        copy: {},
        status:
          error.code === "PUBLIC_RESOURCE_UNAVAILABLE"
            ? "unavailable"
            : "error",
      });
      clearTabs(this);
    }
  },
  onHeroChange(event) {
    this.update({
      heroIndex: event.detail.current,
    });
  },
  markMediaFailed(key) {
    this.update({
      failedMedia: {
        ...this.data.failedMedia,
        [key]: true,
      },
    });
  },
  openHero(item) {
    if (!item || typeof item.target !== "string") return;
    if (item.target === "work" && item.targetId) this.openWork(item.targetId);
    else if (item.target === "package" && item.targetId)
      this.openPackage(item.targetId);
    else if (item.target === "business" && item.targetId)
      this.goServices(item.targetId);
    else if (item.target === "about") this.goAbout();
  },
  openWork(id) {
    if (id)
      wx.navigateTo({
        url: `/pages/works/workdetail?workId=${encodeURIComponent(id)}`,
      });
  },
  openPackage(id) {
    if (id)
      wx.navigateTo({
        url: `/pages/package/pkgdetail?packageId=${encodeURIComponent(id)}`,
      });
  },
  goWorks() {
    wx.switchTab({
      url: "/pages/works/works",
    });
  },
  goServices(id = "") {
    getApp().globalData.serviceBusinessId = typeof id === "string" ? id : "";
    wx.switchTab({
      url: "/pages/package/package",
    });
  },
  goAbout() {
    wx.switchTab({
      url: "/pages/about/about",
    });
  },
  onShow() {
    if (typeof this.getTabBar === "function" && this.getTabBar()) {
      this.getTabBar().setData({ selected: 0 });
    }
    applyTabs(this, this.data.copy);
    if (this._hidden) {
      this._hidden = false;
      this.loadHome();
    }
  },
  handleLoadHomeTap(_event) {
    this.loadHome();
  },
  handleLoadHomeTap2(_event) {
    this.loadHome();
  },
  handleOnHeroChangeChange(event) {
    this.onHeroChange(event);
  },
  handleOpenHeroTap(event) {
    this.openHero(event.currentTarget.dataset.arg0);
  },
  handleMarkMediaFailedError(event) {
    this.markMediaFailed(event.currentTarget.dataset.arg0);
  },
  handleGoServicesTap(_event) {
    this.goServices();
  },
  handleGoServicesTap2(event) {
    this.goServices(event.currentTarget.dataset.arg0);
  },
  handleMarkMediaFailedError2(event) {
    this.markMediaFailed(event.currentTarget.dataset.arg0);
  },
  handleGoWorksTap(_event) {
    this.goWorks();
  },
  handleOpenWorkTap(event) {
    this.openWork(event.currentTarget.dataset.arg0);
  },
  handleMarkMediaFailedError3(event) {
    this.markMediaFailed(event.currentTarget.dataset.arg0);
  },
  handleOpenWorkTap2(event) {
    this.openWork(event.currentTarget.dataset.arg0);
  },
  handleMarkMediaFailedError4(event) {
    this.markMediaFailed(event.currentTarget.dataset.arg0);
  },
  handleGoAboutTap(_event) {
    this.goAbout();
  },
  handleOpenPackageTap(event) {
    this.openPackage(event.currentTarget.dataset.arg0);
  },
});
