const { getHome } = require("../../api/public.js");
const { commit } = require("../../presentation/page-state.js");
const { homeView } = require("../../presentation/views.js");
function present(data) {
  return {
    ...data,
    home: homeView(data.home),
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
    dataMode: "",
  }),
  update(patch) {
    commit(this, present, patch);
  },
  onHide() {
    this._hidden = true;
    this._generation++;
    this.update({
      home: {
        brand: {},
        heroItems: [],
        businesses: [],
        featuredWorks: [],
        packages: [],
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
      await this.loadHome();
    } finally {
      wx.stopPullDownRefresh();
    }
  },
  onShareAppMessage() {
    return {
      title: this.data.home.brand.shareTitle || "摄影工作室",
      path: "/pages/index/index",
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
    this.update({
      status: "loading",
    });
    this.update({
      home: {
        brand: {},
        heroItems: [],
        businesses: [],
        featuredWorks: [],
        packages: [],
        contact: {},
      },
    });
    this.update({
      failedMedia: {},
    });
    try {
      const home = await getHome();
      if (version !== this._generation) return;
      this.update({
        home: home,
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
    if (item.target === "package" && item.targetId)
      this.openPackage(item.targetId);
    else if (item.targetId) this.openWork(item.targetId);
    else this.goWorks();
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
});
