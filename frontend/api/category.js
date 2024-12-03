// category.js
import http from './api';

const baseURL = '/api/categories';

// 获取所有类别
export const getAllCategories = () => {
  return http.get(`${baseURL}/getAllCategories`);
};

// 添加类别
const addCategory = (data) => {
  return http.post(`${baseURL}/add`, data);
};

// 更新类别
const updateCategory = (data) => {
  return http.put(`${baseURL}/update`, data);
};

// 删除类别
const deleteCategory = (id) => {
  return http.delete(`${baseURL}/delete/${id}`);
};