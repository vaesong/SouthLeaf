"use strict";
const api_api = require("./api.js");
const baseURL = "/api/categories";
const getAllCategories = () => {
  return api_api.http.get(`${baseURL}/getAllCategories`);
};
exports.getAllCategories = getAllCategories;
