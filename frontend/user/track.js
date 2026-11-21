const user = require("../api/user.js");

const enter = (page) => {
  const app = getApp();
  const ready = typeof app.ensureUser === "function" ? app.ensureUser() : user.ensure();
  return ready
    .then(() => {
      const options = page.options || {};
      if (options.sls) app.globalData.arrivalShareId = String(options.sls);
      if (page.route === "pages/gate/gate") return null;
      return user.recordVisit(page.route, options).catch(() => null);
    })
    .catch((error) => {
      if (page.route !== "pages/gate/gate") wx.reLaunch({ url: "/pages/gate/gate" });
      throw error;
    });
};

const share = (page, message, contentId) => {
  const base = Object.assign({}, message);
  const parent = getApp().globalData.arrivalShareId || "";
  return user
    .createShare(page.route, contentId || "", parent)
    .then((id) => {
      const path = base.path || "";
      const join = path.indexOf("?") >= 0 ? "&" : "?";
      return Object.assign({}, base, {
        path: `${path}${join}sls=${encodeURIComponent(id)}`,
      });
    })
    .catch(() => base);
};

module.exports = { enter, share };
