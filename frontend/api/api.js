const timeout = 10000;
const PUBLIC_ERROR_MESSAGES = {
  PUBLIC_QUERY_INVALID: "请求内容有误，请返回重试",
  PUBLIC_CURSOR_EXPIRED: "内容已更新，请重新加载",
  PUBLIC_RESOURCE_UNAVAILABLE: "内容暂不可用",
  PUBLIC_SERVICE_UNAVAILABLE: "服务暂时繁忙，请稍后重试",
  PUBLIC_MEDIA_UNAVAILABLE: "部分图片暂时无法显示",
  PUBLIC_RESPONSE_INVALID: "页面加载异常，请重试",
  NETWORK_ERROR: "网络异常，请检查网络连接",
  REQUEST_TIMEOUT: "请求超时，请稍后重试",
  BOOKING_REQUEST_INVALID: "预约信息有误，请检查后重试",
  BOOKING_SESSION_REQUIRED: "请先完成微信登录",
  BOOKING_IDENTITY_REJECTED: "微信登录失败，请重试",
  BOOKING_IDENTITY_PROVIDER_UNCONFIGURED: "预约尚未开放",
  BOOKING_PACKAGE_CATALOG_UNCONFIGURED: "套餐暂不可预约",
  BOOKING_SLOT_UNAVAILABLE: "该时段已被占用，请换一个时段",
  BOOKING_REQUEST_ALREADY_OPEN: "你已有一笔待确认的申请",
  BOOKING_RESOURCE_UNAVAILABLE: "预约记录不存在",
  BOOKING_SERVICE_UNAVAILABLE: "预约服务暂不可用",
};
const statusByCode = {
  OK: 200,
  PUBLIC_QUERY_INVALID: 400,
  PUBLIC_CURSOR_EXPIRED: 409,
  PUBLIC_RESOURCE_UNAVAILABLE: 404,
  PUBLIC_SERVICE_UNAVAILABLE: 503,
  PUBLIC_MEDIA_UNAVAILABLE: 503,
  PUBLIC_RESPONSE_INVALID: 500,
  BOOKING_REQUEST_INVALID: 400,
  BOOKING_SESSION_REQUIRED: 401,
  BOOKING_IDENTITY_REJECTED: 401,
  BOOKING_RESOURCE_UNAVAILABLE: 404,
  BOOKING_SLOT_UNAVAILABLE: 409,
  BOOKING_REQUEST_ALREADY_OPEN: 409,
  BOOKING_IDENTITY_PROVIDER_UNCONFIGURED: 503,
  BOOKING_PACKAGE_CATALOG_UNCONFIGURED: 503,
  BOOKING_SERVICE_UNAVAILABLE: 503,
};
const showToast = (message, icon = "none") =>
  wx.showToast({ title: message, icon, duration: 1800 });
const getBaseURL = () => getApp().runtime.apiOrigin;
const createError = (code, details = {}) =>
  Object.assign(new Error(PUBLIC_ERROR_MESSAGES[code]), { code, ...details });

const send = (method, path, { params = {}, body = null, headers = {} } = {}) =>
  new Promise((resolve, reject) => {
    const base = getBaseURL();
    if (!base) {
      reject(createError("NETWORK_ERROR"));
      return;
    }
    const query = Object.entries(params)
      .filter(
        ([, value]) => value !== undefined && value !== null && value !== "",
      )
      .map(
        ([key, value]) =>
          `${encodeURIComponent(key)}=${encodeURIComponent(value)}`,
      )
      .join("&");
    wx.request({
      url: `${base}${path}${query ? `?${query}` : ""}`,
      method,
      data: method === "GET" ? {} : body,
      header: { "Content-Type": "application/json", ...headers },
      timeout,
      success: (response) => {
        const payload = response.data;
        if (
          !payload ||
          typeof payload !== "object" ||
          typeof payload.requestId !== "string" ||
          !payload.meta ||
          statusByCode[payload.code] !== response.statusCode
        ) {
          reject(createError("PUBLIC_RESPONSE_INVALID"));
          return;
        }
        if (payload.code !== "OK") {
          reject(
            createError(payload.code, {
              requestId: payload.requestId,
              httpStatus: response.statusCode,
            }),
          );
          return;
        }
        if (
          payload.data === null ||
          payload.data === undefined ||
          typeof payload.meta.contentVersion !== "string" ||
          !payload.meta.contentVersion ||
          !["demo", "production"].includes(payload.meta.dataMode)
        ) {
          reject(createError("PUBLIC_RESPONSE_INVALID"));
          return;
        }
        if (
          getApp().runtime.release &&
          payload.meta.dataMode !== "production"
        ) {
          reject(
            createError("PUBLIC_SERVICE_UNAVAILABLE", {
              requestId: payload.requestId,
            }),
          );
          return;
        }

        resolve({
          data: payload.data,
          meta: payload.meta,
          requestId: payload.requestId,
        });
      },
      fail: (cause) =>
        reject(
          createError(
            cause.errMsg?.toLowerCase().includes("timeout")
              ? "REQUEST_TIMEOUT"
              : "NETWORK_ERROR",
            { cause },
          ),
        ),
    });
  });

const get = (path, params = {}, headers = {}) =>
  send("GET", path, { params, headers });
const post = (path, body = null, headers = {}) =>
  send("POST", path, { body, headers });

module.exports = {
  get,
  post,
  PUBLIC_ERROR_MESSAGES,
  showToast,
  createError,
  getBaseURL,
};
