"use strict";
const common_vendor = require("../../common/vendor.js");
const api_category = require("../../api/category.js");
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
      swiperImages: [
        "/static/works/han_0.jpg",
        "/static/works/han_1.jpg",
        "/static/works/han_2.jpg",
        "/static/works/han_3.jpg",
        "/static/works/han_4.jpg"
      ],
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
            "※ 我们将收取全款费用的30%作为定金，尾款在拍摄结束后5个工作日付清",
            "※ 产品交付时间15个工作日，以收到尾款开始计算",
            "※ 客户如需自行选片必须提前告知",
            "※ 我们不接受除高质量问题外任何原因的相册重做要求"
          ]
        },
        {
          id: 3,
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
        }
      ]
    };
  },
  computed: {
    filteredCategories() {
      return this.categories.filter((category) => category.name !== "全部");
    }
  },
  methods: {
    async fetchCategories() {
      try {
        this.categories = await api_category.getAllCategories();
      } catch (error) {
        console.error("加载类别失败:", error.message);
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
        a: pkg.imageSrc[0],
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
