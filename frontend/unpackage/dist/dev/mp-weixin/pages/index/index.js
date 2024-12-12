"use strict";
const common_vendor = require("../../common/vendor.js");
const api_category = require("../../api/category.js");
const api_swipper = require("../../api/swipper.js");
const api_package = require("../../api/package.js");
const common_assets = require("../../common/assets.js");
const FooterInfo = () => "../../components/FooterInfo/FooterInfo.js";
const ModalPopup = () => "../../components/ModalPopup/ModalPopup.js";
const SectionHeader = () => "../../components/SectionHeader/SectionHeader.js";
const _sfc_main = {
  components: {
    FooterInfo,
    ModalPopup,
    SectionHeader
  },
  data() {
    return {
      // 后端接口，获取轮播区图片
      swiperImages: [],
      //后期接口，获取所有的目录类型
      categories: [],
      // 后端接口，获取作品信息（可选择个数，默认全部）
      works: [
        {
          id: 1,
          title: "男孩写真fdhjisakfhasffashjkfdhaslkfhasklfhslhjkd",
          description: "这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。",
          author: "风言雾语",
          category: "创始人档",
          package: "写真",
          imageSrc: [
            "/static/works/han_0.jpg",
            "/static/works/han_1.jpg",
            "/static/works/han_2.jpg"
          ],
          time: "2023-08-15"
        },
        {
          id: 2,
          title: "圣诞女孩",
          description: "这是一个B档的作品，具有独特的风格和构图。这是一个B档的作品，具有独特的风格和构图。这是一个B档的作品，具有独特的风格和构图。",
          author: "风言雾语",
          category: "创始人档",
          package: "写真",
          imageSrc: [
            "/static/works/kid_0.jpg",
            "/static/works/kid_1.jpg",
            "/static/works/kid_2.jpg",
            "/static/works/kid_3.jpg",
            "/static/works/kid_4.jpg",
            "/static/works/kid_5.jpg"
          ],
          time: "2023-07-10"
        },
        {
          id: 3,
          title: "男孩写真",
          description: "这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。",
          author: "风言雾语",
          category: "创始人档",
          package: "写真",
          imageSrc: [
            "/static/works/han_0.jpg",
            "/static/works/han_1.jpg",
            "/static/works/han_2.jpg"
          ],
          time: "2023-08-15"
        },
        {
          id: 4,
          title: "圣诞女孩",
          description: "这是一个B档的作品，具有独特的风格和构图。这是一个B档的作品，具有独特的风格和构图。这是一个B档的作品，具有独特的风格和构图。",
          author: "风言雾语",
          category: "创始人档",
          package: "写真",
          imageSrc: [
            "/static/works/kid_0.jpg",
            "/static/works/kid_1.jpg"
          ],
          time: "2023-07-10"
        },
        {
          id: 5,
          title: "男孩写真",
          description: "这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。",
          author: "风言雾语",
          category: "创始人档",
          package: "写真",
          imageSrc: [
            "/static/works/han_0.jpg",
            "/static/works/han_1.jpg",
            "/static/works/han_2.jpg"
          ],
          time: "2023-08-15"
        },
        {
          id: 6,
          title: "圣诞女孩",
          description: "这是一个B档的作品，具有独特的风格和构图。这是一个B档的作品，具有独特的风格和构图。这是一个B档的作品，具有独特的风格和构图。",
          author: "风言雾语",
          category: "创始人档",
          package: "写真",
          imageSrc: [
            "/static/works/kid_0.jpg",
            "/static/works/kid_1.jpg"
          ],
          time: "2023-07-10"
        }
      ],
      // 后端接口，获取套系信息（可选择个数，默认全部）
      packages: []
    };
  },
  computed: {
    filteredCategories() {
      return this.categories.filter((category) => category.name !== "全部");
    }
  },
  methods: {
    // 后端接口，获取分类信息
    async fetchCategories() {
      try {
        this.categories = await api_category.getAllCategories();
      } catch (error) {
        console.error("加载类别失败:", error.message);
      }
    },
    // 后端接口，获取轮播区数据
    async fetchSwippers() {
      try {
        this.swiperImages = await api_swipper.getSwipperImages();
      } catch (error) {
        console.error("加载轮播区失败:", error.message);
      }
    },
    // 后端接口，获取套系数据
    async fetchPackages() {
      try {
        this.packages = await api_package.getAllPackages();
      } catch (error) {
        console.error("加载套系失败:", error.message);
      }
    },
    goToWorks(categoryName) {
      getApp().globalData.selectedCategory = categoryName;
      common_vendor.index.switchTab({
        url: "/pages/works/works"
      });
    },
    goToPackagess(categoryName) {
      getApp().globalData.selectedCategory = categoryName;
      common_vendor.index.switchTab({
        url: "/pages/package/package"
      });
    },
    // 点击查看作品详情
    viewWorkDetails(work) {
      common_vendor.index.navigateTo({
        url: `/pages/works/workdetail?id=${work.id}`
      });
    },
    // 点击查看套系详情
    viewPkgDetails(pkg) {
      common_vendor.index.navigateTo({
        url: `/pages/package/pkgdetail?id=${pkg.id}`
      });
    }
  },
  mounted() {
    this.fetchCategories();
    this.fetchSwippers();
    this.fetchPackages();
  }
};
if (!Array) {
  const _easycom_SectionHeader2 = common_vendor.resolveComponent("SectionHeader");
  const _easycom_FooterInfo2 = common_vendor.resolveComponent("FooterInfo");
  (_easycom_SectionHeader2 + _easycom_FooterInfo2)();
}
const _easycom_SectionHeader = () => "../../components/SectionHeader/SectionHeader.js";
const _easycom_FooterInfo = () => "../../components/FooterInfo/FooterInfo.js";
if (!Math) {
  (_easycom_SectionHeader + _easycom_FooterInfo)();
}
function _sfc_render(_ctx, _cache, $props, $setup, $data, $options) {
  return {
    a: common_vendor.f($data.swiperImages, (image, index, i0) => {
      return {
        a: image,
        b: index
      };
    }),
    b: common_vendor.f($options.filteredCategories, (category, index, i0) => {
      return {
        a: encodeURI(category.images[0]),
        b: common_vendor.t(category.name),
        c: category.id,
        d: common_vendor.o(($event) => $options.goToWorks(category.name), category.id)
      };
    }),
    c: common_vendor.o(($event) => $options.goToWorks("全部")),
    d: common_vendor.p({
      title: "作品欣赏",
      leftColor: "#4CAF50",
      showMore: true
    }),
    e: common_vendor.f($data.works, (work, index, i0) => {
      return {
        a: work.imageSrc[0],
        b: common_vendor.t(work.title),
        c: index,
        d: common_vendor.o(($event) => $options.viewWorkDetails(work), index)
      };
    }),
    f: common_vendor.o(($event) => $options.goToPackagess("全部")),
    g: common_vendor.p({
      title: "服务套餐",
      leftColor: "#4CAF50",
      showMore: true
    }),
    h: common_vendor.f($data.packages, (pkg, index, i0) => {
      return {
        a: pkg.images[0],
        b: common_vendor.t(pkg.title),
        c: index,
        d: common_vendor.o(($event) => $options.viewPkgDetails(pkg), index)
      };
    }),
    i: common_vendor.p({
      title: "用户评价",
      leftColor: "#4CAF50",
      showMore: false
    }),
    j: common_assets._imports_0
  };
}
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["render", _sfc_render], ["__scopeId", "data-v-1cf27b2a"]]);
wx.createPage(MiniProgramPage);
