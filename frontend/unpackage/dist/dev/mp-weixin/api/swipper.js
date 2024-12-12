"use strict";
const api_api = require("./api.js");
const baseURL = "/api/swipper";
const getSwipperImages = () => {
  return api_api.http.get(`${baseURL}/getSwipperImages`);
};
exports.getSwipperImages = getSwipperImages;
