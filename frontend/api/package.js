// package.js
import http from './api';

const baseURL = '/api/packages';

// 获取所有类别
export const getAllPackages = () => {
  return http.get(`${baseURL}/getAllPackages`);
};