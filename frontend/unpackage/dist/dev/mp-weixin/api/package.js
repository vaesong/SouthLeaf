"use strict";
const api_api = require("./api.js");
const baseURL = "/api/packages";
const getAllPackages = () => {
  return api_api.http.get(`${baseURL}/getAllPackages`);
};
exports.getAllPackages = getAllPackages;
