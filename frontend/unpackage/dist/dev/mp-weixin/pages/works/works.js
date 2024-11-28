"use strict";
const common_vendor = require("../../common/vendor.js");
const common_assets = require("../../common/assets.js");
const FooterInfo = () => "../../components/FooterInfo/FooterInfo.js";
const ToggleLayoutButton = () => "../../components/ToggleLayoutButton/ToggleLayoutButton.js";
const _sfc_main = {
  components: { FooterInfo, ToggleLayoutButton },
  data() {
    return {
      //后期接口，获取所有的目录类型
      categories: [
        { id: 0, name: "全部", desc: "ALL", icons: ["/static/works/han_2.jpg"] },
        { id: 1, name: "创始人档", desc: "Founder Portfolio", icons: ["/static/works/han_2.jpg"] },
        { id: 2, name: "儿童", desc: "Child Portrait", icons: ["/static/works/han_2.jpg"] },
        { id: 3, name: "亲子全家福", desc: "Family Portrait", icons: ["/static/works/han_2.jpg"] },
        { id: 4, name: "婚纱", desc: "Bridal Photography", icons: ["/static/works/han_2.jpg"] },
        { id: 5, name: "写真", desc: "Portrait Photography", icons: ["/static/works/han_2.jpg"] },
        { id: 6, name: "孕照", desc: "Maternity Photography", icons: ["/static/works/han_2.jpg"] },
        { id: 7, name: "证件照", desc: "Passport Photo", icons: ["/static/works/han_2.jpg"] }
      ],
      selectedCategory: "全部",
      layoutClass: "grid-layout",
      // 控制图片排列方式
      layoutIcon: "/static/icons/grid.png",
      // 初始为网格图标
      scrollLeft: 0,
      allWorks: [
        {
          id: 1,
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
        },
        {
          id: 7,
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
          id: 8,
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
      ]
    };
  },
  onShow() {
    const app = getApp();
    if (app && app.globalData && app.globalData.selectedCategory) {
      this.selectedCategory = app.globalData.selectedCategory;
      app.globalData.selectedCategory = null;
    }
  },
  computed: {
    //后期接口，根据目录从服务器返回数据
    filteredWorks() {
      return this.allWorks.filter((work) => work.category === this.selectedCategory);
    },
    // 根据 layoutClass 返回布尔值，控制 ToggleLayoutButton 的初始布局
    isGridLayout() {
      return this.layoutClass === "grid-layout";
    },
    // 获取每个分类的一个预览图片
    previewImages() {
      const previews = {};
      this.allWorks.forEach((work) => {
        if (!previews[work.category]) {
          previews[work.category] = work.imageSrc[0];
        }
      });
      return previews;
    }
  },
  methods: {
    updateLayoutClass(newClass) {
      this.layoutClass = newClass;
    },
    scrollToLastVisibleCategory(index) {
      const additionalOffset = 25;
      const query = common_vendor.index.createSelectorQuery().in(this);
      query.select(`#category-item-${index}`).boundingClientRect();
      query.select(".category-bar").boundingClientRect();
      query.exec((res) => {
        if (res[0] && res[1]) {
          const itemRect = res[0];
          const scrollViewRect = res[1];
          if (itemRect.right + additionalOffset > scrollViewRect.left + scrollViewRect.width) {
            this.scrollLeft += itemRect.width + additionalOffset;
          } else if (itemRect.left - additionalOffset < scrollViewRect.left) {
            this.scrollLeft -= itemRect.width + additionalOffset;
          }
        }
      });
    },
    selectCategory(category, index) {
      this.selectedCategory = category.name;
      this.scrollToLastVisibleCategory(index);
    },
    navigateToSearchPage() {
      common_vendor.index.showToast({
        title: "开发中，敬请期待( •̀ .̫ •́ )✧",
        // 显示的提示内容
        icon: "none",
        // 不使用图标
        duration: 1e3
        // 显示时长，单位为毫秒（2秒后消失）
      });
    },
    // 点击作品查看详情
    viewDetails(work) {
      common_vendor.index.navigateTo({
        url: `/pages/works/workdetail?id=${work.id}`
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
  return common_vendor.e({
    a: common_vendor.f($data.categories, (category, index, i0) => {
      return {
        a: common_vendor.t(category.name),
        b: category.id,
        c: "category-item-" + index,
        d: $data.selectedCategory === category.name ? 1 : "",
        e: common_vendor.o(($event) => $options.selectCategory(category, index), category.id)
      };
    }),
    b: $data.scrollLeft,
    c: common_assets._imports_0$1,
    d: common_vendor.o((...args) => $options.navigateToSearchPage && $options.navigateToSearchPage(...args)),
    e: common_vendor.o($options.updateLayoutClass),
    f: common_vendor.p({
      initialLayout: $options.isGridLayout
    }),
    g: $data.selectedCategory === "全部"
  }, $data.selectedCategory === "全部" ? {
    h: common_vendor.f($data.categories.filter((cat) => cat.name !== "全部"), (category, k0, i0) => {
      return {
        a: category.icons[0],
        b: common_vendor.t(category.name),
        c: common_vendor.t(category.desc),
        d: category.id,
        e: common_vendor.o(($event) => $options.selectCategory(category, _ctx.index), category.id)
      };
    })
  } : {
    i: common_vendor.f($options.filteredWorks, (work, k0, i0) => {
      return common_vendor.e({
        a: work.imageSrc[0],
        b: common_vendor.t(work.title),
        c: common_vendor.t(work.category)
      }, $data.layoutClass === "list-layout" ? {
        d: common_vendor.t(work.description)
      } : {}, {
        e: work.id,
        f: common_vendor.o(($event) => $options.viewDetails(work), work.id)
      });
    }),
    j: $data.layoutClass === "list-layout",
    k: common_vendor.n($data.layoutClass),
    l: common_vendor.n($data.layoutClass)
  });
}
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["render", _sfc_render], ["__scopeId", "data-v-2475a018"]]);
wx.createPage(MiniProgramPage);
