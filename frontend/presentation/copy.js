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

const ready = (copy) => (copy && copy.home ? copy : nest(null));

const showing = (status) => status === "success" || status === "empty";

module.exports = { nest, ready, showing };
