"use strict";
const common_vendor = require("../../common/vendor.js");
const common_assets = require("../../common/assets.js");
const FooterInfo = () => "../../components/FooterInfo/FooterInfo.js";
const ModalPopup = () => "../../components/ModalPopup/ModalPopup.js";
const _sfc_main = {
  components: {
    FooterInfo,
    ModalPopup
  },
  data() {
    return {
      showWeChat: false,
      // 控制弹出层的显示
      teamMembers: [
        {
          name: "风言雾语",
          title: "摄影师",
          description: "枝南影像创始人，从业11年，千余场世界各地婚礼摄影经验，中国婚礼摄影奖项获得者",
          avatar: "/static/about/member1.png"
        },
        {
          name: "一方",
          title: "妆造师",
          description: "擅长捕捉婚礼中的动人瞬间，以其独特的视角和深厚的摄影功底受到客户一致好评",
          avatar: "/static/about/member2.png"
        }
        // 更多成员信息...
      ]
    };
  },
  methods: {
    makePhoneCall() {
      common_vendor.index.makePhoneCall({
        phoneNumber: "18365519973"
      });
    },
    openWeChat() {
      this.showWeChat = true;
    },
    closeWeChat() {
      this.showWeChat = false;
    },
    openMap() {
      common_vendor.index.openLocation({
        latitude: 22.732733,
        // 替换为你的目标纬度
        longitude: 114.137469,
        // 替换为你的目标经度
        name: "枝南影像",
        // 地点名称
        address: "广东省东莞市凤岗镇保利百合花园",
        // 详细地址
        scale: 18,
        // 地图缩放级别，范围5-18，默认18
        success: () => {
          console.log("地图打开成功");
        },
        fail: (err) => {
          console.error("地图打开失败", err);
        }
      });
    },
    openCalendar() {
      common_vendor.index.showToast({
        title: "开发中，敬请期待( •̀ .̫ •́ )✧",
        // 显示的提示内容
        icon: "none",
        // 不使用图标
        duration: 1e3
        // 显示时长，单位为毫秒（2秒后消失）
      });
    },
    makeReservation() {
      common_vendor.index.showToast({
        title: "开发中，敬请期待( •̀ .̫ •́ )✧",
        // 显示的提示内容
        icon: "none",
        // 不使用图标
        duration: 1e3
        // 显示时长，单位为毫秒（2秒后消失）
      });
    }
  }
};
if (!Array) {
  const _easycom_ModalPopup2 = common_vendor.resolveComponent("ModalPopup");
  const _easycom_FooterInfo2 = common_vendor.resolveComponent("FooterInfo");
  (_easycom_ModalPopup2 + _easycom_FooterInfo2)();
}
const _easycom_ModalPopup = () => "../../components/ModalPopup/ModalPopup.js";
const _easycom_FooterInfo = () => "../../components/FooterInfo/FooterInfo.js";
if (!Math) {
  (_easycom_ModalPopup + _easycom_FooterInfo)();
}
function _sfc_render(_ctx, _cache, $props, $setup, $data, $options) {
  return common_vendor.e({
    a: common_assets._imports_0$2,
    b: common_assets._imports_1,
    c: common_vendor.o((...args) => $options.makePhoneCall && $options.makePhoneCall(...args)),
    d: common_assets._imports_1$1,
    e: common_vendor.o((...args) => $options.openWeChat && $options.openWeChat(...args)),
    f: common_assets._imports_3,
    g: common_vendor.o((...args) => $options.openMap && $options.openMap(...args)),
    h: common_assets._imports_4,
    i: common_vendor.o((...args) => $options.openCalendar && $options.openCalendar(...args)),
    j: common_vendor.o((...args) => $options.makeReservation && $options.makeReservation(...args)),
    k: common_vendor.f($data.teamMembers, (member, index, i0) => {
      return {
        a: member.avatar,
        b: common_vendor.t(member.name),
        c: common_vendor.t(member.title),
        d: common_vendor.t(member.description),
        e: index,
        f: common_vendor.n({
          "member-reverse": index % 2 === 1
        })
      };
    }),
    l: $data.showWeChat
  }, $data.showWeChat ? {
    m: common_vendor.o(($event) => $data.showWeChat = false),
    n: common_vendor.p({
      isVisible: $data.showWeChat,
      title: "枝南影像",
      imageSrc: "/static/info/wechat.png",
      description: "长按添加微信"
    })
  } : {});
}
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["render", _sfc_render], ["__scopeId", "data-v-13a78ac6"]]);
wx.createPage(MiniProgramPage);
