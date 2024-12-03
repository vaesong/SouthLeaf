// 全局变量
const baseURL = 'http://localhost:8090'; // 后端接口基础地址
const timeout = 10000; // 超时时间

// 通用错误提示
const showToast = (message, icon = 'none') => {
  uni.showToast({
    title: message,
    icon: icon,
    duration: 1500,
  });
};

// 处理业务逻辑错误
const handleBusinessError = (code, message) => {
  switch (code) {
    case 400:
      showToast(message || '请求参数错误');
      break;
    case 401:
      showToast('未授权，请登录后重试');
      break;
    case 403:
      showToast('权限不足');
      break;
    case 500:
      showToast(message || '服务器内部错误');
      break;
    default:
      showToast(message || '未知错误');
  }
};

// 处理 HTTP 错误
const handleHttpError = (error) => {
  console.error('HTTP 请求错误:', error); // 打印完整错误信息以供调试

  if (error.errMsg.includes('timeout')) {
    // 请求超时
    showToast('请求超时，请稍后重试');
  } else if (error.errMsg.includes('ERR_CONNECTION_REFUSED')) {
    // 服务未启动或无法连接
    showToast('无法连接到服务器，请告知开发人员');
  } else if (error.errMsg.includes('request:fail')) {
    // 小程序请求失败的一般性错误
    showToast('网络异常，请检查网络连接');
  } else {
    // 其他未知错误
    showToast('未知网络错误，请稍后重试');
  }
};



// 封装请求逻辑
const baseRequest = (method, url, data = {}, options = {}) => {
  return new Promise((resolve, reject) => {
    const token = uni.getStorageSync('token') || '';
    uni.request({
      url: `${baseURL}${url}`,
      method,
      data,
      header: {
        'Content-Type': 'application/json',
        'Authorization': token ? `Bearer ${token}` : '',
        ...options.headers,
      },
      timeout,
      success: (res) => {
        const { code, data, message } = res.data;
        if (code === 200) {
          resolve(data);
        } else {
          handleBusinessError(code, message);
          reject(new Error(message || '业务逻辑错误'));
        }
      },
      fail: (error) => {
        handleHttpError(error);
        reject(error);
      },
    });
  });
};

// 封装的 HTTP 方法
const http = {
  get(url, params = {}, options = {}) {
    const queryString = Object.keys(params)
      .map((key) => `${encodeURIComponent(key)}=${encodeURIComponent(params[key])}`)
      .join('&');
    return baseRequest('GET', `${url}?${queryString}`, {}, options);
  },
  post(url, data = {}, options = {}) {
    return baseRequest('POST', url, data, options);
  },
  put(url, data = {}, options = {}) {
    return baseRequest('PUT', url, data, options);
  },
  delete(url, options = {}) {
    return baseRequest('DELETE', url, {}, options);
  },
};

export default http;
