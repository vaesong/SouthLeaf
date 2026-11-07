"use strict";

const GROUPS = [
  "tabs",
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
const TAB_KEYS = ["home", "works", "services", "about", "mine"];

const nest = (flat) => {
  const root = {};
  for (const group of GROUPS) root[group] = {};
  if (!flat || typeof flat !== "object") return root;
  for (const [key, value] of Object.entries(flat)) {
    if (typeof value !== "string" || !value) continue;
    const dot = key.indexOf(".");
    if (dot <= 0 || dot !== key.lastIndexOf(".")) continue;
    const group = key.slice(0, dot);
    const name = key.slice(dot + 1);
    if (!root[group] || !name) continue;
    root[group][name] = value;
  }
  return root;
};

const ready = (copy) => (copy && copy.tabs && copy.home ? copy : nest(null));

const showing = (status) => status === "success" || status === "empty";

const applyTabs = (page, copy) => {
  if (!page || typeof page.getTabBar !== "function") return;
  const bar = page.getTabBar();
  if (!bar || !bar.data || !Array.isArray(bar.data.list)) return;
  const tabs = (copy && copy.tabs) || {};
  bar.setData({
    list: bar.data.list.map((item, index) => ({
      ...item,
      text:
        typeof tabs[TAB_KEYS[index]] === "string" ? tabs[TAB_KEYS[index]] : "",
    })),
  });
};

const clearTabs = (page) => applyTabs(page, nest(null));

module.exports = { nest, ready, showing, applyTabs, clearTabs };
