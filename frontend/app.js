App({
  runtime: require("./config/runtime.js"),
  globalData: { selectedBusinessId: "", serviceBusinessId: "" },
  onLaunch() {
    wx.loadFontFace({
      global: true,
      family: "Libre Bodoni",
      source: 'url("' + require("./pages/index/brand-font.js") + '")',
      scopes: ["webview", "native"],
      fail() {},
    });
  },
});
