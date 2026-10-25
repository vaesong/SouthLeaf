import { isIP } from "node:net";
export const routes = [
  "pages/index/index",
  "pages/works/works",
  "pages/works/workdetail",
  "pages/package/package",
  "pages/package/pkgdetail",
  "pages/about/about",
  "pages/booking/booking",
  "pages/booking/requestdetail",
];
// Syntax checks are not ownership, filing, DNS, certificate or deployment approval.
export function resolveBuildConfig({ mode, apiOrigin, deployment = {} }) {
  if (!["demo", "release"].includes(mode))
    throw new Error("Explicit demo or release mode is required");
  const release = mode === "release";
  const value = release ? deployment.apiOrigin : apiOrigin;
  let target;
  try {
    target = new URL(value);
  } catch {
    throw new Error("Explicit API origin is required");
  }
  if (
    typeof value !== "string" ||
    value.trim() !== value ||
    !["http:", "https:"].includes(target.protocol) ||
    target.username ||
    target.password ||
    target.search ||
    target.hash ||
    target.pathname !== "/"
  )
    throw new Error("API configuration must be a plain HTTP(S) origin");
  let appId = "touristappid";
  if (release) {
    const host = target.hostname.replace(/^\[|\]$/g, "");
    if (
      target.protocol !== "https:" ||
      isIP(host) ||
      !host.includes(".") ||
      /(?:^|\.)(?:localhost|local|internal|invalid|test|example|onion)$/.test(
        host,
      )
    )
      throw new Error("Release requires a public HTTPS DNS origin");
    appId = deployment.appId;
    if (typeof appId !== "string" || !/^wx[a-fA-F0-9]{16}$/.test(appId))
      throw new Error("Release requires an explicitly configured WeChat AppID");
    if (
      Object.keys(deployment).some(
        (key) => !["apiOrigin", "appId"].includes(key),
      )
    )
      throw new Error("Unknown release configuration key");
  }
  return { runtime: { apiOrigin: target.origin, release }, appId };
}
export function approvedTabAssets(config) {
  if (JSON.stringify(config.pages) !== JSON.stringify(routes))
    throw new Error("Exactly eight approved routes required");
  const tabs = config.tabBar?.list;
  if (!Array.isArray(tabs) || tabs.length !== 4)
    throw new Error("Exactly four approved tabs required");
  const files = tabs.flatMap((tab) => [tab.iconPath, tab.selectedIconPath]);
  if (
    new Set(files).size !== 8 ||
    files.some(
      (f) =>
        typeof f !== "string" ||
        !/^static\/icons\/tab-(?:home|works|services|about)(?:-active)?\.png$/.test(
          f,
        ),
    )
  )
    throw new Error("Exactly eight approved relative tab assets required");
  if (
    JSON.stringify(tabs.map((t) => t.pagePath)) !==
    JSON.stringify([routes[0], routes[1], routes[3], routes[5]])
  )
    throw new Error("Tab routes do not match approved order");
  return new Set(files);
}
