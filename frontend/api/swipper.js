// swipper.js
import http from './api';

const baseURL = '/api/swipper';

// 获取所有类别
export const getSwipperImages = () => {
  return http.get(`${baseURL}/getSwipperImages`);
};