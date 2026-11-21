const user = require("../../api/user.js");

Page({
  data: { busy: false },
  retry() {
    if (this.data.busy) return;
    this.setData({ busy: true });
    user
      .ensure()
      .then(() => {
        wx.switchTab({ url: "/pages/index/index" });
      })
      .catch(() => {
        this.setData({ busy: false });
      });
  },
});
