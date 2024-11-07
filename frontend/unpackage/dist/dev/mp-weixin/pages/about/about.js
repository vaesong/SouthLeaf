"use strict";
const common_vendor = require("../../common/vendor.js");
const common_assets = require("../../common/assets.js");
const _sfc_main = {
  methods: {
    openWebsite() {
      common_vendor.index.navigateTo({
        url: "http://www.cangvision.com"
      });
    },
    makePhoneCall() {
      common_vendor.index.makePhoneCall({
        phoneNumber: "18365519973"
      });
    },
    openWeChat() {
      common_vendor.index.showModal({
        title: "添加微信",
        content: "请添加微信号：your_wechat_id",
        showCancel: true,
        // 显示取消按钮
        confirmText: "复制微信号",
        success: (res) => {
          if (res.confirm) {
            common_vendor.index.setClipboardData({
              data: "your_wechat_id",
              success: () => {
                common_vendor.index.showToast({
                  title: "微信号已复制",
                  icon: "success"
                });
              }
            });
          }
        }
      });
    },
    openCalendar() {
      common_vendor.index.showDatePicker({
        success: (res) => {
          common_vendor.index.showToast({
            title: `选择的日期是：${res.date}`,
            icon: "none"
          });
        },
        fail: () => {
          common_vendor.index.showToast({
            title: "未选择日期",
            icon: "none"
          });
        }
      });
    },
    openCalendar() {
      console.log("日历按钮被点击");
    },
    makeReservation() {
      common_vendor.index.navigateTo({
        url: "/pages/reservation/reservation"
      });
    }
  }
};
function _sfc_render(_ctx, _cache, $props, $setup, $data, $options) {
  return {
    a: common_assets._imports_0,
    b: common_vendor.o((...args) => $options.openWebsite && $options.openWebsite(...args)),
    c: common_vendor.o((...args) => $options.makePhoneCall && $options.makePhoneCall(...args)),
    d: common_vendor.o((...args) => $options.openWeChat && $options.openWeChat(...args)),
    e: common_vendor.o((...args) => _ctx.openLocation && _ctx.openLocation(...args)),
    f: common_vendor.o((...args) => $options.openCalendar && $options.openCalendar(...args)),
    g: common_vendor.o((...args) => $options.makeReservation && $options.makeReservation(...args))
  };
}
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["render", _sfc_render], ["__scopeId", "data-v-13a78ac6"]]);
wx.createPage(MiniProgramPage);
