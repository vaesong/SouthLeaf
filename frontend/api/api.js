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
};
const statusByCode = {
  OK: 200,
  PUBLIC_QUERY_INVALID: 400,
  PUBLIC_CURSOR_EXPIRED: 409,
  PUBLIC_RESOURCE_UNAVAILABLE: 404,
  PUBLIC_SERVICE_UNAVAILABLE: 503,
  PUBLIC_MEDIA_UNAVAILABLE: 503,
  PUBLIC_RESPONSE_INVALID: 500,
};
const showToast = (message, icon = "none") =>
  wx.showToast({ title: message, icon, duration: 1800 });
const getBaseURL = () => getApp().runtime.apiOrigin;
const createError = (code, details = {}) =>
  Object.assign(new Error(PUBLIC_ERROR_MESSAGES[code]), { code, ...details });

const get = (path, params = {}) =>
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
      method: "GET",
      data: {},
      header: { "Content-Type": "application/json" },
      timeout,
      success: (response) => {
        const body = response.data;
        if (
          !body ||
          typeof body !== "object" ||
          typeof body.requestId !== "string" ||
          !body.meta ||
          statusByCode[body.code] !== response.statusCode
        ) {
          reject(createError("PUBLIC_RESPONSE_INVALID"));
          return;
        }
        if (body.code !== "OK") {
          reject(
            createError(body.code, {
              requestId: body.requestId,
              httpStatus: response.statusCode,
            }),
          );
          return;
        }
        if (
          body.data === null ||
          body.data === undefined ||
          typeof body.meta.contentVersion !== "string" ||
          !body.meta.contentVersion ||
          !["demo", "production"].includes(body.meta.dataMode)
        ) {
          reject(createError("PUBLIC_RESPONSE_INVALID"));
          return;
        }
        if (getApp().runtime.release && body.meta.dataMode !== "production") {
          reject(
            createError("PUBLIC_SERVICE_UNAVAILABLE", {
              requestId: body.requestId,
            }),
          );
          return;
        }

        resolve({
          data: body.data,
          meta: body.meta,
          requestId: body.requestId,
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

module.exports = {
  get,
  PUBLIC_ERROR_MESSAGES,
  showToast,
  createError,
  getBaseURL,
};
