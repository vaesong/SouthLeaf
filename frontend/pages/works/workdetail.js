const { getWork } = require("../../api/public.js");
const {
  galleryMedia,
  readLayout,
  rememberLayout,
  nextLayout,
} = require("../../api/public-display.js");
const { commit } = require("../../presentation/page-state.js");
const { workView } = require("../../presentation/views.js");
function present(data) {
  return {
    ...data,
    availableMedia: galleryMedia(data.work),
    work: workView(data.work),
    dataMode: ["success", "empty"].includes(data.status)
      ? (data.work.meta?.dataMode ?? "")
      : "",
  };
}
Page({
  data: present({
    id: "",
    status: "loading",
    work: {
      businessLabels: [],
      media: [],
    },
    failedMedia: {},
    layout: "album",
    dataMode: "",
  }),
  update(patch) {
    commit(this, present, patch);
  },
  onHide() {
    this._hidden = true;
    this._generation++;
    this.update({
      work: {
        businessLabels: [],
        media: [],
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
      await this.loadWork();
    } finally {
      wx.stopPullDownRefresh();
    }
  },
  onShareAppMessage() {
    return {
      title: this.data.work.title || "公开作品",
      path: `/pages/works/workdetail?workId=${encodeURIComponent(this.data.id)}`,
    };
  },
  onLoad(options) {
    this._generation = 0;
    this._unloaded = false;
    this._hidden = false;
    this.update({
      layout: readLayout(
        "southleaf.work.gallery",
        ["album", "overview"],
        "album",
      ),
    });
    const id = options?.workId;
    this.update({
      id: typeof id === "string" && id.trim().length <= 128 ? id.trim() : "",
    });
    this.loadWork();
  },
  async loadWork() {
    if (!this.data.id) {
      this.update({
        status: "unavailable",
      });
      return;
    }
    const version = ++this._generation;
    this.update({
      work: {
        businessLabels: [],
        media: [],
      },
    });
    this.update({
      status: "loading",
    });
    this.update({
      failedMedia: {},
    });
    try {
      const work = await getWork(this.data.id);
      if (version !== this._generation) return;
      this.update({
        work: work,
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
  cycleLayout() {
    this.selectLayout(nextLayout(this.data.layout, ["album", "overview"]));
  },
  selectLayout(layout) {
    if (!["album", "overview"].includes(layout)) return;
    this.update({
      layout: layout,
    });
    rememberLayout("southleaf.work.gallery", layout);
  },
  previewMedia(media) {
    if (
      this.data.status !== "success" ||
      this.data.failedMedia[media.id || media.src]
    )
      return;
    const urls = this.data.availableMedia
      .filter((item) => !this.data.failedMedia[item.id || item.src])
      .map((item) => item.src);
    if (urls.length)
      wx.previewImage({
        current: media.src,
        urls,
      });
  },
  markGalleryMediaFailed(id) {
    this.update({
      failedMedia: {
        ...this.data.failedMedia,
        [id]: true,
      },
    });
  },
  goBack() {
    wx.navigateBack({
      fail: () =>
        wx.switchTab({
          url: "/pages/works/works",
        }),
    });
  },
  onShow() {
    if (this._hidden) {
      this._hidden = false;
      this.loadWork();
    }
  },
  handleGoBackTap(_event) {
    this.goBack();
  },
  handleGoBackTap2(_event) {
    this.goBack();
  },
  handleLoadWorkTap(_event) {
    this.loadWork();
  },
  handleCycleLayoutTap(_event) {
    this.cycleLayout();
  },
  handlePreviewMediaTap(event) {
    this.previewMedia(event.currentTarget.dataset.arg0);
  },
  handleMarkGalleryMediaFailedError(event) {
    this.markGalleryMediaFailed(event.currentTarget.dataset.arg0);
  },
});
