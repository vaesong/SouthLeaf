"use strict";
const common_vendor = require("../../common/vendor.js");
const FooterInfo = () => "../../components/FooterInfo/FooterInfo.js";
const ToggleLayoutButton = () => "../../components/ToggleLayoutButton/ToggleLayoutButton.js";
const UniIcons = () => "../../uni_modules/uni-icons/components/uni-icons/uni-icons.js";
const _sfc_main = {
  components: {
    FooterInfo,
    ToggleLayoutButton,
    UniIcons
  },
  data() {
    return {
      //后期接口，获取所有的目录类型
      categories: [
        { id: 0, name: "全部", desc: "ALL", icons: ["/static/works/han_2.jpg"] },
        { id: 1, name: "儿童", desc: "Child Portrait", icons: ["/static/works/han_2.jpg"] },
        { id: 2, name: "亲子全家福", desc: "Family Portrait", icons: ["/static/works/han_2.jpg"] },
        { id: 3, name: "婚纱", desc: "Bridal Photography", icons: ["/static/works/han_2.jpg"] },
        { id: 4, name: "写真", desc: "Portrait Photography", icons: ["/static/works/han_2.jpg"] },
        { id: 5, name: "孕照", desc: "Maternity Photography", icons: ["/static/works/han_2.jpg"] },
        { id: 6, name: "哈哈哈测试", desc: "Passport Photo", icons: ["/static/works/han_2.jpg"] },
        { id: 7, name: "嘻嘻嘻测试", desc: "Passport Photo", icons: ["/static/works/han_2.jpg"] },
        { id: 8, name: "呜呜呜", desc: "Passport Photo", icons: ["/static/works/han_2.jpg"] }
      ],
      packages: [
        {
          id: 0,
          title: "秋日物语",
          description: "在这温馨璀璨的圣诞季，每一帧写真都定格了冬日里最温暖的笑容与梦幻光影。银铃轻响中，我们捕捉到了节日的魔法，让爱与祝福在镜头下静静绽放，封存成永恒的记忆篇章。",
          category: "儿童",
          imageSrc: [
            "/static/works/han_0.jpg",
            "/static/works/han_1.jpg",
            "/static/works/han_2.jpg"
          ],
          price: 618,
          activityPrice: 328,
          activateStart: "2024-09-01",
          activateEnd: "2024-10-31",
          services: [
            "服务内容：12小时拍摄，全天不少于2000张原片，精修保底100张起，当日预告不少于30张",
            "三机礼盒内容：U盘一个（内含电子版精修及jpg格式的原片）进口微喷工艺相册一本",
            "附加项目：当日如需快修加收RMB2000元，三机位不少于80张增加机位RMB5000/总监机位，RMB3000/普通机位",
            "※其它服务区域，请拨打服务热线4008-767-212，或请联系客服进行具体咨询"
          ],
          reminder: [
            "※ 我们将收取全款费用的50%作为定金，尾款在拍摄结束后5个工作日付清",
            "※ 产品交付时间15个工作日，以收到尾款开始计算",
            "※ 客户如需自行选片必须提前告知",
            "※ 我们不接受除高质量问题外任何原因的相册重做要求"
          ]
        },
        {
          id: 1,
          title: "圣诞主题",
          description: "捕捉秋日美景的每一个瞬间，金黄色的落叶、温暖的光影为婚礼增添浪漫的氛围。让这一季的秋色成为永恒的记忆，记录下新人的幸福时刻，感受岁月静好的秋日情怀。",
          category: "写真",
          imageSrc: [
            "/static/works/kid_0.jpg",
            "/static/works/kid_1.jpg",
            "/static/works/kid_2.jpg",
            "/static/works/kid_3.jpg",
            "/static/works/kid_4.jpg",
            "/static/works/kid_5.jpg"
          ],
          price: 520,
          activityPrice: 450,
          activateStart: "2024-09-01",
          activateEnd: "2024-10-31",
          services: [
            "服务内容：8小时拍摄，全天不少于1500张原片，精修保底80张，当日预告不少于20张",
            "三机礼盒内容：U盘一个（内含电子版精修及jpg格式的原片）高级相册一本，秋季主题定制相框一幅",
            "附加项目：当日如需快修加收RMB1500元，三机位不少于60张增加机位RMB4000/总监机位，RMB2500/普通机位",
            "※其它服务区域，请拨打服务热线4008-767-212，或请联系客服进行具体咨询"
          ],
          reminder: [
            "※ 我们将收取全款费用的50%作为定金，尾款在拍摄结束后5个工作日付清",
            "※ 产品交付时间15个工作日，以收到尾款开始计算",
            "※ 客户如需自行选片必须提前告知",
            "※ 我们不接受除高质量问题外任何原因的相册重做要求"
          ]
        },
        {
          id: 2,
          title: "秋日物语",
          description: "在这温馨璀璨的圣诞季，每一帧写真都定格了冬日里最温暖的笑容与梦幻光影。银铃轻响中，我们捕捉到了节日的魔法，让爱与祝福在镜头下静静绽放，封存成永恒的记忆篇章。",
          category: "儿童",
          imageSrc: [
            "/static/works/han_0.jpg",
            "/static/works/han_1.jpg",
            "/static/works/han_2.jpg"
          ],
          price: 618,
          activityPrice: 328,
          activateStart: "2024-09-01",
          activateEnd: "2024-10-31",
          services: [
            "服务内容：12小时拍摄，全天不少于2000张原片，精修保底100张起，当日预告不少于30张",
            "三机礼盒内容：U盘一个（内含电子版精修及jpg格式的原片）进口微喷工艺相册一本",
            "附加项目：当日如需快修加收RMB2000元，三机位不少于80张增加机位RMB5000/总监机位，RMB3000/普通机位",
            "※其它服务区域，请拨打服务热线4008-767-212，或请联系客服进行具体咨询"
          ],
          reminder: [
            "※ 我们将收取全款费用的50%作为定金，尾款在拍摄结束后5个工作日付清",
            "※ 产品交付时间15个工作日，以收到尾款开始计算",
            "※ 客户如需自行选片必须提前告知",
            "※ 我们不接受除高质量问题外任何原因的相册重做要求"
          ]
        }
      ],
      layoutClass: "grid-layout",
      selectedCategory: "全部",
      activeSortOption: "默认排序",
      priceSortOrder: "",
      // 初始化为升序
      scrollLeft: 0
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
    displayedPackages() {
      let filteredPackages = this.selectedCategory === "全部" ? this.packages : this.packages.filter((pkg) => pkg.category === this.selectedCategory);
      return filteredPackages;
    },
    isGridLayout() {
      return this.layoutClass === "grid-layout";
    }
  },
  methods: {
    selectCategory(category, index) {
      this.selectedCategory = category.name;
      this.scrollToLastVisibleCategory(index);
    },
    togglePriceSortOrder() {
      this.activeSortOption = "价格";
      this.priceSortOrder = this.priceSortOrder === "asc" ? "desc" : "asc";
    },
    selectSortOption(option) {
      this.activeSortOption = option;
      if (option !== "价格") {
        this.priceSortOrder = "";
      }
    },
    onIconClick(order) {
      this.priceSortOrder = order;
      this.activeSortOption = "价格";
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
    updateLayoutClass(newLayoutClass) {
      this.layoutClass = newLayoutClass === "grid-layout" ? "grid-layout" : "list-layout";
    },
    viewDetails(pkg) {
      common_vendor.index.navigateTo({
        url: `/pages/package/pkgdetail?id=${pkg.id}`
        // 假设有一个详情页面
      });
    }
  }
};
if (!Array) {
  const _easycom_ToggleLayoutButton2 = common_vendor.resolveComponent("ToggleLayoutButton");
  const _component_UniIcons = common_vendor.resolveComponent("UniIcons");
  const _easycom_FooterInfo2 = common_vendor.resolveComponent("FooterInfo");
  (_easycom_ToggleLayoutButton2 + _component_UniIcons + _easycom_FooterInfo2)();
}
const _easycom_ToggleLayoutButton = () => "../../components/ToggleLayoutButton/ToggleLayoutButton.js";
const _easycom_FooterInfo = () => "../../components/FooterInfo/FooterInfo.js";
if (!Math) {
  (_easycom_ToggleLayoutButton + _easycom_FooterInfo)();
}
function _sfc_render(_ctx, _cache, $props, $setup, $data, $options) {
  return {
    a: common_vendor.f($data.categories, (category, index, i0) => {
      return {
        a: common_vendor.t(category.name),
        b: category.id,
        c: `category-item-${index}`,
        d: common_vendor.n({
          active: $data.selectedCategory === category.name
        }),
        e: common_vendor.o(($event) => $options.selectCategory(category, index), category.id)
      };
    }),
    b: $data.scrollLeft,
    c: common_vendor.o($options.updateLayoutClass),
    d: common_vendor.p({
      initialLayout: $options.isGridLayout
    }),
    e: common_vendor.n({
      active: $data.activeSortOption === "默认排序"
    }),
    f: common_vendor.o(($event) => $options.selectSortOption("默认排序")),
    g: common_vendor.s($data.priceSortOrder === "asc" ? "font-weight: bold; transform: scale(1.1);" : ""),
    h: common_vendor.o(($event) => $options.onIconClick("asc")),
    i: common_vendor.p({
      type: "up",
      size: "10",
      color: $data.priceSortOrder === "asc" ? "#1e90ff" : "#333"
    }),
    j: common_vendor.s($data.priceSortOrder === "desc" ? "font-weight: bold; transform: scale(1.1);" : ""),
    k: common_vendor.o(($event) => $options.onIconClick("desc")),
    l: common_vendor.p({
      type: "down",
      size: "10",
      color: $data.priceSortOrder === "desc" ? "#1e90ff" : "#333"
    }),
    m: common_vendor.n({
      active: $data.activeSortOption === "价格"
    }),
    n: common_vendor.o((...args) => $options.togglePriceSortOrder && $options.togglePriceSortOrder(...args)),
    o: common_vendor.n({
      active: $data.activeSortOption === "热门"
    }),
    p: common_vendor.o(($event) => $options.selectSortOption("热门")),
    q: common_vendor.f($options.displayedPackages, (pkg, k0, i0) => {
      return {
        a: pkg.imageSrc[0],
        b: common_vendor.t(pkg.title),
        c: pkg.id,
        d: common_vendor.o(($event) => $options.viewDetails(pkg), pkg.id)
      };
    }),
    r: common_vendor.n($data.layoutClass),
    s: common_vendor.n($data.layoutClass)
  };
}
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["render", _sfc_render], ["__scopeId", "data-v-9a861780"]]);
wx.createPage(MiniProgramPage);
