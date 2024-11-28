"use strict";
const common_vendor = require("../../common/vendor.js");
const common_assets = require("../../common/assets.js");
const FooterInfo = () => "../../components/FooterInfo/FooterInfo.js";
const ModalPopup = () => "../../components/ModalPopup/ModalPopup.js";
const UniIcons = () => "../../uni_modules/uni-icons/components/uni-icons/uni-icons.js";
const _sfc_main = {
  components: {
    FooterInfo,
    ModalPopup,
    UniIcons
  },
  data() {
    return {};
  },
  methods: {
    onItemClick() {
      common_vendor.index.showToast({
        title: "开发中，敬请期待( •̀ .̫ •́ )✧",
        icon: "none",
        duration: 1e3
      });
    }
  }
};
if (!Array) {
  const _component_UniIcons = common_vendor.resolveComponent("UniIcons");
  _component_UniIcons();
}
function _sfc_render(_ctx, _cache, $props, $setup, $data, $options) {
  return {
    a: common_assets._imports_0$3,
    b: common_assets._imports_1$2,
    c: common_vendor.o(($event) => $options.onItemClick()),
    d: common_assets._imports_2,
    e: common_vendor.o(($event) => $options.onItemClick()),
    f: common_assets._imports_3$1,
    g: common_vendor.o(($event) => $options.onItemClick()),
    h: common_assets._imports_4$1,
    i: common_vendor.o(($event) => $options.onItemClick()),
    j: common_vendor.p({
      type: "arrowright",
      size: "20",
      color: "#888"
    }),
    k: common_vendor.o(($event) => $options.onItemClick()),
    l: common_vendor.p({
      type: "arrowright",
      size: "20",
      color: "#888"
    }),
    m: common_vendor.o(($event) => $options.onItemClick()),
    n: common_vendor.p({
      type: "arrowright",
      size: "20",
      color: "#888"
    }),
    o: common_vendor.o(($event) => $options.onItemClick()),
    p: common_vendor.p({
      type: "arrowright",
      size: "20",
      color: "#888"
    }),
    q: common_vendor.o(($event) => $options.onItemClick()),
    r: common_vendor.p({
      type: "arrowright",
      size: "20",
      color: "#888"
    }),
    s: common_vendor.o(($event) => $options.onItemClick())
  };
}
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["render", _sfc_render], ["__scopeId", "data-v-2f1ef635"]]);
wx.createPage(MiniProgramPage);
