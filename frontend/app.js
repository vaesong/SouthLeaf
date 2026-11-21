const userApi = require("./api/user.js");

App({
  runtime: require("./config/runtime.js"),
  globalData: { selectedBusinessId: "", serviceBusinessId: "", arrivalShareId: "" },
  ensureUser() {
    if (!this._userFlight) {
      this._userFlight = userApi.ensure().finally(() => {
        this._userFlight = null;
      });
    }
    return this._userFlight;
  },
  onLaunch() {
    this.ensureUser().catch(() => {
      wx.reLaunch({ url: "/pages/gate/gate" });
    });
    wx.loadFontFace({
      global: true,
      family: "Libre Bodoni",
      source: 'url("' + require("./pages/index/brand-font.js") + '")',
      scopes: ["webview", "native"],
      fail() {},
    });
  },
});
