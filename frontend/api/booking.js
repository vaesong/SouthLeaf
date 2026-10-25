const http = require("./api.js");
const { createError, get, post } = http;

const TOKEN_KEY = "southleaf.booking.session";

const token = () => {
  const stored = wx.getStorageSync(TOKEN_KEY);
  return typeof stored === "string" ? stored : "";
};

const auth = (extra = {}) => {
  const value = token();
  if (!value) throw createError("BOOKING_SESSION_REQUIRED");
  return { ...extra, Authorization: "Bearer " + value };
};

const loginCode = () =>
  new Promise((resolve, reject) =>
    wx.login({
      success: (result) =>
        result && typeof result.code === "string" && result.code
          ? resolve(result.code)
          : reject(createError("BOOKING_IDENTITY_REJECTED")),
      fail: () => reject(createError("BOOKING_IDENTITY_REJECTED")),
    }),
  );

const signIn = async () => {
  const code = await loginCode();
  const session = await post("/api/v1/public/booking/session", {
    loginCode: code,
  });
  wx.setStorageSync(TOKEN_KEY, session.data.token);
  return session.data;
};

const ensureSession = async () => (token() ? null : signIn());

const getPackages = () => get("/api/v1/public/booking/packages");

const getSlots = (from, to) =>
  get("/api/v1/public/booking/slots", { from, to });

const createRequest = async (idempotencyKey, slotId, packageVersionRef) => {
  await ensureSession();
  return post(
    "/api/v1/public/booking/requests",
    { slotId, packageVersionRef },
    auth({ "Idempotency-Key": idempotencyKey }),
  );
};

const getRequests = async () => {
  await ensureSession();
  return get("/api/v1/public/booking/requests", {}, auth());
};

const getRequest = async (reservationId) => {
  await ensureSession();
  return get(
    "/api/v1/public/booking/requests/" + encodeURIComponent(reservationId),
    {},
    auth(),
  );
};

const clearSession = () => wx.removeStorageSync(TOKEN_KEY);

module.exports = {
  TOKEN_KEY,
  token,
  signIn,
  ensureSession,
  getPackages,
  getSlots,
  createRequest,
  getRequests,
  getRequest,
  clearSession,
};
