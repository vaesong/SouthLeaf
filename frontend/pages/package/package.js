const { getBusinesses, getPackages } = require("../../api/public.js");
const { createError } = require("../../api/api.js");
const { groupServices } = require("../../api/public-display.js");
const { commit } = require("../../presentation/page-state.js");
const { serviceViews } = require("../../presentation/views.js");
function present(data) {
  return {
    ...data,
    serviceGroups: serviceViews(groupServices(data.businesses, data.packages)),
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
    dataMode: "",
  }),
  update(patch) {
    commit(this, present, patch);
  },
  onHide() {
    this._hidden = true;
    this._generation++;
    this.update({
      businesses: [],
      packages: [],
      status: "loading",
    });
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
      title: "拍摄服务",
      path: "/pages/package/package",
    };
  },
  async loadPackages(reset = true) {
    const version = reset ? ++this._generation : this._generation;
    const cursor = reset ? undefined : this.data.nextCursor;
    if (reset) {
      this.update({
        status: "loading",
      });
      this.update({
        businesses: [],
      });
      this.update({
        packages: [],
      });
      this.update({
        nextCursor: null,
      });
      this.update({
        contentVersion: "",
      });
      this.update({
        failedMedia: {},
      });
      this.update({
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
      });
      this.scrollToBusiness();
    } catch (error) {
      if (version !== this._generation) return;
      if (!reset && error.code === "PUBLIC_CURSOR_EXPIRED")
        return this.loadPackages(true);
      if (reset)
        this.update({
          status:
            error.code === "PUBLIC_RESOURCE_UNAVAILABLE"
              ? "unavailable"
              : "error",
        });
      else
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
