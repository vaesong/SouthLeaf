"use strict";
const common_vendor = require("../../common/vendor.js");
const FooterInfo = () => "../../components/FooterInfo/FooterInfo.js";
const ModalPopup = () => "../../components/ModalPopup/ModalPopup.js";
const _sfc_main = {
  components: {
    FooterInfo,
    ModalPopup
  },
  data() {
    return {};
  },
  methods: {}
};
if (!Array) {
  const _easycom_FooterInfo2 = common_vendor.resolveComponent("FooterInfo");
  _easycom_FooterInfo2();
}
const _easycom_FooterInfo = () => "../../components/FooterInfo/FooterInfo.js";
if (!Math) {
  _easycom_FooterInfo();
}
function _sfc_render(_ctx, _cache, $props, $setup, $data, $options) {
  return {};
}
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["render", _sfc_render], ["__scopeId", "data-v-9a861780"]]);
wx.createPage(MiniProgramPage);
