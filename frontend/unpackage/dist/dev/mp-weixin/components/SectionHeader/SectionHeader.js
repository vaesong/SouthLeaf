"use strict";
const common_vendor = require("../../common/vendor.js");
const _sfc_main = {
  name: "SectionHeader",
  props: {
    leftColor: {
      type: String,
      default: "#4CAF50"
      // 默认左边颜色为绿色
    },
    title: {
      type: String,
      required: true
    },
    showMore: {
      type: Boolean,
      default: false
    }
  },
  methods: {
    onMoreClick() {
      this.$emit("more-click");
    }
  }
};
if (!Array) {
  const _easycom_uni_icons2 = common_vendor.resolveComponent("uni-icons");
  _easycom_uni_icons2();
}
const _easycom_uni_icons = () => "../../uni_modules/uni-icons/components/uni-icons/uni-icons.js";
if (!Math) {
  _easycom_uni_icons();
}
function _sfc_render(_ctx, _cache, $props, $setup, $data, $options) {
  return common_vendor.e({
    a: $props.leftColor,
    b: common_vendor.t($props.title),
    c: $props.showMore
  }, $props.showMore ? {
    d: common_vendor.p({
      type: "arrowright",
      size: "16",
      color: "#333"
    }),
    e: common_vendor.o((...args) => $options.onMoreClick && $options.onMoreClick(...args))
  } : {});
}
const Component = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["render", _sfc_render], ["__scopeId", "data-v-af217af4"]]);
wx.createComponent(Component);
