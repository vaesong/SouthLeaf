const { get, post, put } = require("./api.js");

const KEY = "southleaf.user.session";

const token = () => {
  try {
    return wx.getStorageSync(KEY) || "";
  } catch (error) {
    return "";
  }
};

const authHeaders = () => {
  const value = token();
  return value ? { Authorization: `Bearer ${value}` } : {};
};

const uuid = () => {
  const bytes = new Uint8Array(16);
  for (let index = 0; index < bytes.length; index += 1) {
    bytes[index] = Math.floor(Math.random() * 256);
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
};

const scene = () => {
  try {
    const value = wx.getLaunchOptionsSync().scene;
    return Number.isInteger(value) ? value : null;
  } catch (error) {
    return null;
  }
};

const loginCode = () =>
  new Promise((resolve, reject) => {
    wx.login({
      success(result) {
        if (result && result.code) resolve(result.code);
        else reject(Object.assign(new Error("微信登录没有完成"), { code: "USER_IDENTITY_REJECTED" }));
      },
      fail() {
        reject(Object.assign(new Error("微信登录没有完成"), { code: "USER_IDENTITY_REJECTED" }));
      },
    });
  });

const remember = (session) => {
  if (!session || typeof session.token !== "string" || !session.token) {
    throw Object.assign(new Error("登录没有完成"), { code: "USER_SESSION_REQUIRED" });
  }
  wx.setStorageSync(KEY, session.token);
  return session;
};

const ensure = async () => {
  if (token()) {
    try {
      return remember((await post("/api/v1/public/users/session", {}, authHeaders())).data);
    } catch (error) {
      if (!error || error.code !== "USER_SESSION_REQUIRED") throw error;
      wx.removeStorageSync(KEY);
    }
  }
  const code = await loginCode();
  return remember((await post("/api/v1/public/users/session", { loginCode: code })).data);
};

const profile = () => get("/api/v1/public/users/me", {}, authHeaders());
const reservations = () => get("/api/v1/public/users/me/reservations", {}, authHeaders());
const rename = (displayName) => put("/api/v1/public/users/me", { displayName }, authHeaders());

const uploadAvatar = (filePath) =>
  new Promise((resolve, reject) => {
    wx.getFileSystemManager().readFile({
      filePath,
      success(file) {
        put("/api/v1/public/users/me/avatar", file.data, {
          ...authHeaders(),
          "Content-Type": "image/jpeg",
        }).then(resolve, reject);
      },
      fail: reject,
    });
  });

const saveProfile = async (patch = {}) => {
  if (patch.avatarPath) await uploadAvatar(patch.avatarPath);
  if (typeof patch.displayName === "string") return (await rename(patch.displayName)).data;
  return (await profile()).data;
};

const recordVisit = (page, options = {}) =>
  post(
    "/api/v1/public/users/visits",
    {
      clientEventId: uuid(),
      page,
      contentId: options.workId || options.packageId || options.reservationId || "",
      shareCardId: options.sls || "",
      scene: scene(),
    },
    authHeaders(),
  );

const createShare = (page, contentId, parentCardId) =>
  post(
    "/api/v1/public/users/shares",
    {
      page,
      contentId: contentId || "",
      parentCardId: parentCardId || "",
      scene: scene(),
    },
    authHeaders(),
  ).then((response) => response.data.id);

module.exports = {
  ensure,
  profile,
  reservations,
  rename,
  saveProfile,
  uploadAvatar,
  recordVisit,
  createShare,
};
