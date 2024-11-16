"use strict";
Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });
const common_vendor = require("./common/vendor.js");
if (!Math) {
  "./pages/index/index.js";
  "./pages/works/works.js";
  "./pages/works/workdetail.js";
  "./pages/package/package.js";
  "./pages/about/about.js";
  "./pages/my/my.js";
}
const _sfc_main = {
  data() {
    return {
      selectedCategory: null
      // 用于存储传递的 category 参数
    };
  },
  onLaunch: function() {
    console.log("App Launch");
  },
  onShow: function() {
    console.log("App Show");
  },
  onHide: function() {
    console.log("App Hide");
  }
};
function createApp() {
  const app = common_vendor.createSSRApp(_sfc_main);
  return {
    app
  };
}
createApp().app.mount("#app");
exports.createApp = createApp;
