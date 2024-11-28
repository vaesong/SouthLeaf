"use strict";
const common_vendor = require("../../common/vendor.js");
const FooterInfo = () => "../../components/FooterInfo/FooterInfo.js";
const ToggleLayoutButton = () => "../../components/ToggleLayoutButton/ToggleLayoutButton.js";
const _sfc_main = {
  components: {
    FooterInfo,
    ToggleLayoutButton
  },
  data() {
    return {
      work: {
        id: null,
        title: "",
        description: "",
        author: "",
        category: "",
        package: "",
        imageSrc: [],
        time: ""
      },
      layoutClass: "grid-layout"
      // 控制初始布局状态
    };
  },
  computed: {
    // 根据 layoutClass 返回布尔值，控制 ToggleLayoutButton 的初始布局
    isGridLayout() {
      return this.layoutClass === "grid-layout";
    }
  },
  onLoad(options) {
    const id = options.id;
    this.loadWorkDetails(id);
  },
  methods: {
    // 后端接口，根据 id 查询数据
    loadWorkDetails(id) {
      const work = {
        id: 1,
        title: "男孩写真",
        description: "这是一个创始人档的作品，包含丰富的内容和回忆...",
        author: "风言雾语",
        category: "写真",
        package: "写真",
        imageSrc: [
          "/static/works/han_0.jpg",
          "/static/works/han_1.jpg",
          "/static/works/han_2.jpg",
          "/static/works/han_3.jpg",
          "/static/works/han_4.jpg",
          "/static/works/han_5.jpg",
          "/static/works/han_6.jpg",
          "/static/works/han_7.jpg",
          "/static/works/han_8.jpg"
        ],
        time: "2023-08-15"
      };
      if (work && work.id === parseInt(id)) {
        this.work = work;
      }
    },
    // 接收 ToggleLayoutButton 传递的布局类名
    updateLayoutClass(newLayoutClass) {
      this.layoutClass = newLayoutClass === "grid-layout" ? "grid-layout" : newLayoutClass;
    },
    goToWorks() {
      getApp().globalData.selectedCategory = this.work.category;
      common_vendor.index.switchTab({
        url: "/pages/works/works"
      });
    }
  }
};
if (!Array) {
  const _easycom_ToggleLayoutButton2 = common_vendor.resolveComponent("ToggleLayoutButton");
  const _easycom_FooterInfo2 = common_vendor.resolveComponent("FooterInfo");
  (_easycom_ToggleLayoutButton2 + _easycom_FooterInfo2)();
}
const _easycom_ToggleLayoutButton = () => "../../components/ToggleLayoutButton/ToggleLayoutButton.js";
const _easycom_FooterInfo = () => "../../components/FooterInfo/FooterInfo.js";
if (!Math) {
  (_easycom_ToggleLayoutButton + _easycom_FooterInfo)();
}
function _sfc_render(_ctx, _cache, $props, $setup, $data, $options) {
  return {
    a: $data.work.imageSrc[0],
    b: common_vendor.t($data.work.title),
    c: common_vendor.o($options.updateLayoutClass),
    d: common_vendor.p({
      initialLayout: $options.isGridLayout
    }),
    e: common_vendor.t($data.work.category),
    f: common_vendor.o((...args) => $options.goToWorks && $options.goToWorks(...args)),
    g: common_vendor.t($data.work.description),
    h: common_vendor.f($data.work.imageSrc, (image, index, i0) => {
      return {
        a: image,
        b: index
      };
    }),
    i: common_vendor.n($data.layoutClass)
  };
}
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["render", _sfc_render], ["__scopeId", "data-v-33468508"]]);
wx.createPage(MiniProgramPage);
