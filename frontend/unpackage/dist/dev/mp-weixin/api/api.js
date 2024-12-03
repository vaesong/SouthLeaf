"use strict";
const common_vendor = require("../common/vendor.js");
const baseURL = "http://localhost:8090";
const timeout = 1e4;
const showToast = (message, icon = "none") => {
  common_vendor.index.showToast({
    title: message,
    icon,
    duration: 1500
  });
};
const handleBusinessError = (code, message) => {
  switch (code) {
    case 400:
      showToast(message || "请求参数错误");
      break;
    case 401:
      showToast("未授权，请登录后重试");
      break;
    case 403:
      showToast("权限不足");
      break;
    case 500:
      showToast(message || "服务器内部错误");
      break;
    default:
      showToast(message || "未知错误");
  }
};
const handleHttpError = (error) => {
  console.error("HTTP 请求错误:", error);
  if (error.errMsg.includes("timeout")) {
    showToast("请求超时，请稍后重试");
  } else if (error.errMsg.includes("ERR_CONNECTION_REFUSED")) {
    showToast("无法连接到服务器，请告知开发人员");
  } else if (error.errMsg.includes("request:fail")) {
    showToast("网络异常，请检查网络连接");
  } else {
    showToast("未知网络错误，请稍后重试");
  }
};
const baseRequest = (method, url, data = {}, options = {}) => {
  return new Promise((resolve, reject) => {
    const token = common_vendor.index.getStorageSync("token") || "";
    common_vendor.index.request({
      url: `${baseURL}${url}`,
      method,
      data,
      header: {
        "Content-Type": "application/json",
        "Authorization": token ? `Bearer ${token}` : "",
        ...options.headers
      },
      timeout,
      success: (res) => {
        const { code, data: data2, message } = res.data;
        if (code === 200) {
          resolve(data2);
        } else {
          handleBusinessError(code, message);
          reject(new Error(message || "业务逻辑错误"));
        }
      },
      fail: (error) => {
        handleHttpError(error);
        reject(error);
      }
    });
  });
};
const http = {
  get(url, params = {}, options = {}) {
    const queryString = Object.keys(params).map((key) => `${encodeURIComponent(key)}=${encodeURIComponent(params[key])}`).join("&");
    return baseRequest("GET", `${url}?${queryString}`, {}, options);
  },
  post(url, data = {}, options = {}) {
    return baseRequest("POST", url, data, options);
  },
  put(url, data = {}, options = {}) {
    return baseRequest("PUT", url, data, options);
  },
  delete(url, options = {}) {
    return baseRequest("DELETE", url, {}, options);
  }
};
exports.http = http;
