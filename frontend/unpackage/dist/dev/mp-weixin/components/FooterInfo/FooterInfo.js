"use strict";
const common_vendor = require("../../common/vendor.js");
const common_assets = require("../../common/assets.js");
const ModalPopup = () => "../ModalPopup/ModalPopup.js";
const _sfc_main = {
  components: {
    ModalPopup
  },
  data() {
    return {
      showWeChat: false,
      showXiaoHongShu: false,
      showWeibo: false,
      showDouYin: false
    };
  },
  methods: {
    handleLinkClick() {
    },
    openWeChat() {
      this.showWeChat = true;
    },
    closeWeChat() {
      this.showWeChat = false;
    },
    openXiaoHongShu() {
      this.showXiaoHongShu = true;
    },
    closeXiaoHongShu() {
      this.showXiaoHongShu = false;
    },
    openWeibo() {
      this.showWeibo = true;
    },
    closeWeibo() {
      this.showWeibo = false;
    },
    openDouYin() {
      this.showDouYin = true;
    },
    closeDouYin() {
      this.showDouYin = false;
    }
  }
};
if (!Array) {
  const _easycom_ModalPopup2 = common_vendor.resolveComponent("ModalPopup");
  _easycom_ModalPopup2();
}
const _easycom_ModalPopup = () => "../ModalPopup/ModalPopup.js";
if (!Math) {
  _easycom_ModalPopup();
}
function _sfc_render(_ctx, _cache, $props, $setup, $data, $options) {
  return common_vendor.e({
    a: common_assets._imports_0$4,
    b: common_vendor.o((...args) => $options.openWeChat && $options.openWeChat(...args)),
    c: common_assets._imports_1$1,
    d: common_vendor.o((...args) => $options.openWeChat && $options.openWeChat(...args)),
    e: common_assets._imports_2$1,
    f: common_vendor.o((...args) => $options.openXiaoHongShu && $options.openXiaoHongShu(...args)),
    g: common_assets._imports_3$2,
    h: common_vendor.o((...args) => $options.openWeibo && $options.openWeibo(...args)),
    i: common_assets._imports_4$2,
    j: common_vendor.o((...args) => $options.openDouYin && $options.openDouYin(...args)),
    k: $data.showWeChat
  }, $data.showWeChat ? {
    l: common_vendor.o(($event) => $data.showWeChat = false),
    m: common_vendor.p({
      isVisible: $data.showWeChat,
      title: "枝南影像",
      imageSrc: "/static/info/wechat.png",
      description: "长按添加微信"
    })
  } : {}, {
    n: $data.showXiaoHongShu
  }, $data.showXiaoHongShu ? {
    o: common_vendor.o(($event) => $data.showXiaoHongShu = false),
    p: common_vendor.p({
      isVisible: $data.showXiaoHongShu,
      title: "枝南影像",
      imageSrc: "/static/info/weixin.png",
      description: "关注小红书账号"
    })
  } : {}, {
    q: $data.showWeibo
  }, $data.showWeibo ? {
    r: common_vendor.o(($event) => $data.showWeibo = false),
    s: common_vendor.p({
      isVisible: $data.showWeibo,
      title: "枝南影像",
      imageSrc: "/static/info/weixin.png",
      description: "关注微博账号"
    })
  } : {}, {
    t: $data.showDouYin
  }, $data.showDouYin ? {
    v: common_vendor.o(($event) => $data.showDouYin = false),
    w: common_vendor.p({
      isVisible: $data.showDouYin,
      title: "枝南影像",
      imageSrc: "/static/info/weixin.png",
      description: "关注抖音账号"
    })
  } : {});
}
const Component = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["render", _sfc_render], ["__scopeId", "data-v-c361fd8b"]]);
wx.createComponent(Component);
