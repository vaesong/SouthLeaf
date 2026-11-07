const { getBusinesses, getPackages } = require("../../api/public.js");
const { createError } = require("../../api/api.js");
const { groupServices } = require("../../api/public-display.js");
const {
  applyTabs,
  clearTabs,
  ready,
  showing,
} = require("../../presentation/copy.js");
const { commit } = require("../../presentation/page-state.js");
const { serviceViews } = require("../../presentation/views.js");
function present(data) {
  return {
    ...data,
    serviceGroups: serviceViews(
      groupServices(data.businesses, data.packages),
      data.copy.packageDetail,
    ),
    copy: ready(data.copy),
    dataMode: ["success", "empty"].includes(data.status) ? data.dataMode : "",
  };
}
Page({
  data: present({
    status: "loading",
    businesses: [],
    packages: [],
    nextCursor: null,
    loadingMore: false,
    loadMoreError: false,
    contentVersion: "",
    failedMedia: {},
    focusBusinessId: "",
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
      await this.loadPackages();
    } finally {
      wx.stopPullDownRefresh();
    }
  },
  onLoad() {
    this._generation = 0;
    this._unloaded = false;
    this._hidden = false;
    this.loadPackages();
  },
  onShow() {
    if (typeof this.getTabBar === "function" && this.getTabBar()) {
      this.getTabBar().setData({ selected: 2 });
    }
    applyTabs(this, this.data.copy);
    if (this._hidden) {
      this._hidden = false;
      this.loadPackages();
    }
    const id = getApp().globalData.serviceBusinessId;
    if (id) {
      this.update({
        focusBusinessId: id,
      });
      getApp().globalData.serviceBusinessId = "";
      if (this.data.status === "success") this.scrollToBusiness();
    }
  },
  onShareAppMessage() {
    return {
      title: (this.data.copy.services && this.data.copy.services.title) || "",
      path: "/pages/package/package",
      imageUrl: this.data.serviceGroups?.[0]?.coverMedia?.src || "",
    };
  },
  async loadPackages(reset = true) {
    const version = reset ? ++this._generation : this._generation;
    const cursor = reset ? undefined : this.data.nextCursor;
    if (reset && !showing(this.data.status)) {
      this.update({
        status: "loading",
        businesses: [],
        packages: [],
        nextCursor: null,
        contentVersion: "",
        failedMedia: {},
        loadMoreError: false,
      });
    }
    try {
      const [businesses, result] = await Promise.all([
        reset ? getBusinesses() : null,
        getPackages({
          limit: 50,
          cursor,
        }),
      ]);
      if (version !== this._generation) return;
      if (reset && businesses.contentVersion !== result.contentVersion)
        throw createError("PUBLIC_CURSOR_EXPIRED");
      if (!reset && this.data.contentVersion !== result.contentVersion)
        return this.loadPackages(true);
      if (businesses)
        this.update({
          businesses: businesses.items,
        });
      this.update({
        contentVersion: result.contentVersion,
      });
      this.update({
        packages: reset
          ? result.items
          : this.data.packages.concat(result.items),
      });
      this.update({
        nextCursor: result.nextCursor,
      });
      this.update({
        status: this.data.businesses.length ? "success" : "empty",
        dataMode: result.dataMode,
        copy: result.copy || {},
      });
      applyTabs(this, result.copy);
      this.scrollToBusiness();
    } catch (error) {
      if (version !== this._generation) return;
      if (!reset && error.code === "PUBLIC_CURSOR_EXPIRED")
        return this.loadPackages(true);
      if (reset) {
        this.update({
          businesses: [],
          packages: [],
          copy: {},
          status:
            error.code === "PUBLIC_RESOURCE_UNAVAILABLE"
              ? "unavailable"
              : "error",
        });
        clearTabs(this);
      } else
        this.update({
          loadMoreError: true,
        });
    }
  },
  scrollToBusiness() {
    if (
      !this.data.focusBusinessId ||
      !this.data.businesses.some((b) => b.id === this.data.focusBusinessId)
    ) {
      this.update({
        focusBusinessId: "",
      });
      return;
    }
    const selector = `#service-${this.data.focusBusinessId}`;
    this.update({
      focusBusinessId: "",
    });
    wx.nextTick(() => {
      wx.pageScrollTo({
        selector,
        duration: 250,
      });
    });
  },
  async loadMore() {
    if (!this.data.nextCursor || this.data.loadingMore) return;
    this.update({
      loadingMore: true,
    });
    this.update({
      loadMoreError: false,
    });
    try {
      await this.loadPackages(false);
    } finally {
      this.update({
        loadingMore: false,
      });
    }
  },
  markMediaFailed(id) {
    this.update({
      failedMedia: {
        ...this.data.failedMedia,
        [id]: true,
      },
    });
  },
  openPackage(id) {
    if (id)
      wx.navigateTo({
        url: `/pages/package/pkgdetail?packageId=${encodeURIComponent(id)}`,
      });
  },
  goHome() {
    wx.switchTab({
      url: "/pages/index/index",
    });
  },
  handleGoHomeTap(_event) {
    this.goHome();
  },
  handleLoadPackagesTap(_event) {
    this.loadPackages();
  },
  handleMarkMediaFailedError(event) {
    this.markMediaFailed(event.currentTarget.dataset.arg0);
  },
  handleOpenPackageTap(event) {
    this.openPackage(event.currentTarget.dataset.arg0);
  },
  handleLoadMoreTap(_event) {
    this.loadMore();
  },
});
