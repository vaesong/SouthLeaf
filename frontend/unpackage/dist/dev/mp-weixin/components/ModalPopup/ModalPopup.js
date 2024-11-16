"use strict";
const common_vendor = require("../../common/vendor.js");
const _sfc_main = {
  props: {
    isVisible: Boolean,
    title: String,
    imageSrc: String,
    description: String
  },
  methods: {
    close() {
      this.$emit("close");
    }
  }
};
function _sfc_render(_ctx, _cache, $props, $setup, $data, $options) {
  return common_vendor.e({
    a: $props.isVisible
  }, $props.isVisible ? {
    b: common_vendor.t($props.title),
    c: common_vendor.o((...args) => $options.close && $options.close(...args)),
    d: common_vendor.t($props.description),
    e: $props.imageSrc,
    f: common_vendor.o(() => {
    }),
    g: common_vendor.o((...args) => $options.close && $options.close(...args))
  } : {});
}
const Component = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["render", _sfc_render], ["__scopeId", "data-v-269056e3"]]);
wx.createComponent(Component);
