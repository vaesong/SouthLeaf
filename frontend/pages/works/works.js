const { getBusinesses, getWorks } = require("../../api/public.js");
const { createError } = require("../../api/api.js");
const {
  readLayout,
  rememberLayout,
  nextLayout,
} = require("../../api/public-display.js");
const { commit } = require("../../presentation/page-state.js");
const { workView } = require("../../presentation/views.js");
function present(data) {
  return {
    ...data,
    sortLabel: data.sort === "oldest" ? "最早" : "最新",
    layoutLabel: {
      editorial: "画册",
      grid: "双列",
      list: "列表",
    }[data.layout],
    works: data.works.map(workView),
    dataMode: ["success", "empty"].includes(data.status) ? data.dataMode : "",
  };
}
Page({
  data: present({
    status: "loading",
    businesses: [],
    works: [],
    nextCursor: null,
    loadingMore: false,
    loadMoreError: false,
    contentVersion: "",
    failedMedia: {},
    selectedBusinessId: "",
    layout: "grid",
    sort: "newest",
    showSortMenu: false,
    dataMode: "",
  }),
  update(patch) {
    commit(this, present, patch);
  },
  onHide() {
    this._hidden = true;
    this._generation++;
    this.update({
      works: [],
      status: "loading",
      showSortMenu: false,
    });
  },
  onUnload() {
    this._unloaded = true;
    this._generation++;
  },
  async onPullDownRefresh() {
    try {
      await this.loadWorks();
    } finally {
      wx.stopPullDownRefresh();
    }
  },
  onLoad(options) {
    this._generation = 0;
    this._unloaded = false;
    this._hidden = false;
    this.update({
      sort: readLayout("southleaf.works.sort", ["newest", "oldest"], "newest"),
    });
    this.update({
      layout: readLayout(
        "southleaf.works.layout",
        ["editorial", "grid", "list"],
        "grid",
      ),
    });
    this.update({
      selectedBusinessId: options?.businessId || "",
    });
    this.loadWorks();
  },
  onShow() {
    const resume = this._hidden;
    this._hidden = false;
    const id = getApp().globalData.selectedBusinessId;
    if (id) {
      this.update({ selectedBusinessId: id });
      getApp().globalData.selectedBusinessId = "";
      this.loadWorks();
    } else if (resume) this.loadWorks();
  },
  onShareAppMessage() {
    return {
      title: "公开作品",
      path: "/pages/works/works",
    };
  },
  async loadWorks(reset = true) {
    const version = reset ? ++this._generation : this._generation;
    const cursor = reset ? undefined : this.data.nextCursor;
    if (reset) {
      this.update({
        status: "loading",
      });
      this.update({
        nextCursor: null,
      });
      this.update({
        works: [],
      });
      this.update({
        businesses: [],
      });
      this.update({
        contentVersion: "",
      });
      this.update({
        loadMoreError: false,
      });
      this.update({
        failedMedia: {},
      });
    }
    try {
      const [directory, result] = await Promise.all([
        reset ? getBusinesses() : null,
        getWorks({
          businessId: this.data.selectedBusinessId || undefined,
          cursor,
          sort: this.data.sort,
        }),
      ]);
      if (version !== this._generation) return;
      if (reset && directory.contentVersion !== result.contentVersion)
        throw createError("PUBLIC_CURSOR_EXPIRED");
      if (!reset && this.data.contentVersion !== result.contentVersion)
        return this.loadWorks(true);
      if (directory)
        this.update({
          businesses: directory.items,
        });
      this.update({
        works: reset ? result.items : this.data.works.concat(result.items),
      });
      this.update({
        contentVersion: result.contentVersion,
      });
      this.update({
        nextCursor: result.nextCursor,
      });
      this.update({
        status: this.data.works.length ? "success" : "empty",
        dataMode: result.dataMode,
      });
    } catch (error) {
      if (version !== this._generation) return;
      if (!reset && error.code === "PUBLIC_CURSOR_EXPIRED")
        return this.loadWorks(true);
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
  async loadMore() {
    if (!this.data.nextCursor || this.data.loadingMore) return;
    this.update({
      loadingMore: true,
    });
    this.update({
      loadMoreError: false,
    });
    try {
      await this.loadWorks(false);
    } finally {
      this.update({
        loadingMore: false,
      });
    }
  },
  selectFilter(filter) {
    this.update({
      selectedBusinessId: filter,
    });
    this.loadWorks();
  },
  selectSort(sort) {
    this.update({
      showSortMenu: false,
    });
    if (!["newest", "oldest"].includes(sort) || this.data.sort === sort) return;
    this.update({
      sort: sort,
    });
    rememberLayout("southleaf.works.sort", sort);
    return this.loadWorks();
  },
  cycleLayout() {
    this.update({
      showSortMenu: false,
    });
    this.selectLayout(
      nextLayout(this.data.layout, ["grid", "editorial", "list"]),
    );
  },
  selectLayout(layout) {
    if (!["grid", "editorial", "list"].includes(layout)) return;
    this.update({
      layout: layout,
    });
    rememberLayout("southleaf.works.layout", layout);
  },
  markMediaFailed(id) {
    this.update({
      failedMedia: {
        ...this.data.failedMedia,
        [id]: true,
      },
    });
  },
  openWork(id) {
    if (id)
      wx.navigateTo({
        url: `/pages/works/workdetail?workId=${encodeURIComponent(id)}`,
      });
  },
  goHome() {
    wx.switchTab({
      url: "/pages/index/index",
    });
  },
  handleShowSortMenuTap(_event) {
    this.update({
      showSortMenu: !this.data.showSortMenu,
    });
  },
  handleCycleLayoutTap(_event) {
    this.cycleLayout();
  },
  handleSelectSortTap(event) {
    this.selectSort(event.currentTarget.dataset.arg0);
  },
  handleSelectSortTap2(event) {
    this.selectSort(event.currentTarget.dataset.arg0);
  },
  handleShowSortMenuTap2(_event) {
    this.update({
      showSortMenu: false,
    });
  },
  handleSelectFilterTap(event) {
    this.selectFilter(event.currentTarget.dataset.arg0);
  },
  handleSelectFilterTap2(event) {
    this.selectFilter(event.currentTarget.dataset.arg0);
  },
  handleGoHomeTap(_event) {
    this.goHome();
  },
  handleLoadWorksTap(_event) {
    this.loadWorks();
  },
  handleOpenWorkTap(event) {
    this.openWork(event.currentTarget.dataset.arg0);
  },
  handleMarkMediaFailedError(event) {
    this.markMediaFailed(event.currentTarget.dataset.arg0);
  },
  handleSelectFilterTap3(event) {
    this.selectFilter(event.currentTarget.dataset.arg0);
  },
  handleLoadMoreTap(_event) {
    this.loadMore();
  },
});
