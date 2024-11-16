"use strict";
const common_vendor = require("../../common/vendor.js");
const _sfc_main = {
  props: {
    initialLayout: {
      type: Boolean,
      default: true
      // 默认网格布局
    }
  },
  data() {
    return {
      isGridLayout: this.initialLayout,
      // 内部管理布局状态
      gridIcon: "/static/icons/grid.png",
      listIcon: "/static/icons/list.png"
    };
  },
  computed: {
    currentIcon() {
      return this.isGridLayout ? this.gridIcon : this.listIcon;
    }
  },
  methods: {
    handleToggle() {
      this.isGridLayout = !this.isGridLayout;
      const layoutClass = this.isGridLayout ? "grid-layout" : "list-layout";
      this.$emit("toggle-layout", layoutClass);
    }
  }
};
function _sfc_render(_ctx, _cache, $props, $setup, $data, $options) {
  return {
    a: $options.currentIcon,
    b: common_vendor.o((...args) => $options.handleToggle && $options.handleToggle(...args))
  };
}
const Component = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["render", _sfc_render]]);
wx.createComponent(Component);
