"use strict";

const GROUPS = [
  "home",
  "about",
  "works",
  "workDetail",
  "services",
  "packageDetail",
  "booking",
  "mine",
  "requestDetail",
  "settlement",
  "hero",
];
// 没网时不画作品和套餐。这些句子在没发布时用内置默认。
// 发布的空字符串表示这句不显示；缺这个键才用默认。
const DEFAULTS = Object.freeze(require("./copy-defaults.js"));
const nest = (flat) => {
  const root = {};
  for (const group of GROUPS) root[group] = {};
  const merged = { ...DEFAULTS };
  const hidden = new Set();
  if (flat && typeof flat === "object") {
    for (const [key, value] of Object.entries(flat)) {
      if (typeof value !== "string") continue;
      if (value) merged[key] = value;
      else hidden.add(key);
    }
  }
  for (const [key, value] of Object.entries(merged)) {
    const dot = key.indexOf(".");
    if (dot <= 0 || dot !== key.lastIndexOf(".")) continue;
    const group = key.slice(0, dot);
    const name = key.slice(dot + 1);
    if (!root[group] || !name) continue;
    root[group][name] = hidden.has(key) ? "" : value;
  }
  return root;
};

const ready = (copy) => (copy && copy.home ? copy : nest(null));

const showing = (status) => status === "success" || status === "empty";

module.exports = { nest, ready, showing, DEFAULTS };
