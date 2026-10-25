import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
import { createRequire } from "node:module";
const root = new URL("../", import.meta.url);
const require = createRequire(import.meta.url);
const app = {
  runtime: { apiOrigin: "http://demo.test", release: false },
  globalData: {},
};
const wx = {
  getStorageSync() {
    return "";
  },
  setStorageSync() {},
  stopPullDownRefresh() {},
};
globalThis.wx = wx;
globalThis.getApp = () => app;
function page(route, ports = {}) {
  const file = new URL("pages/" + route + ".js", root),
    localRequire = createRequire(file);
  let definition;
  const context = vm.createContext({
    Page(value) {
      definition = value;
    },
    wx,
    getApp: () => app,
    require(id) {
      return id === "../../api/public.js"
        ? { ...localRequire(id), ...ports }
        : localRequire(id);
    },
  });
  new vm.Script(fs.readFileSync(file, "utf8"), {
    filename: file.pathname,
  }).runInContext(context);
  const host = {
    ...definition,
    data: JSON.parse(JSON.stringify(definition.data)),
    _generation: 0,
    commits: 0,
    setData(patch) {
      this.commits++;
      Object.assign(this.data, patch);
    },
  };
  return host;
}
const item = (id) => ({
  id,
  businessLabels: ["家庭"],
  publishedAt: "2026-01-01",
  media: [],
});
const list = (items = [], version = "v1") => ({
  items,
  nextCursor: null,
  contentVersion: version,
  dataMode: "demo",
});
const home = () => ({
  brand: { name: "虚构演示" },
  featuredWorks: [],
  businesses: [],
  heroItems: [],
  packages: [],
  contact: {},
  meta: { dataMode: "demo" },
});
test("native Page state uses explicit setData and preserves list sort/category/layout", async () => {
  const calls = [];
  const p = page("works/works", {
    getBusinesses: async () => list(),
    getWorks: async (q) => {
      calls.push(q);
      return list([item("fresh")]);
    },
  });
  p.update({ selectedBusinessId: "family" });
  await p.selectSort("oldest");
  assert.equal(calls.length, 1);
  assert.equal(calls[0].sort, "oldest");
  assert.equal(calls[0].cursor, undefined);
  assert.equal(p.data.selectedBusinessId, "family");
  assert.equal(p.data.layout, "grid");
  assert.equal(p.data.works[0].id, "fresh");
  assert.equal(p.data.dataMode, "demo");
  assert.ok(p.commits > 0);
});
test("expired cursor causes exactly one first-page requery without mixing old rows", async () => {
  let calls = 0;
  const p = page("works/works", {
    getBusinesses: async () => list([], "v2"),
    getWorks: async () => {
      if (++calls === 1)
        throw Object.assign(new Error("expired"), {
          code: "PUBLIC_CURSOR_EXPIRED",
        });
      return list([item("new")], "v2");
    },
  });
  p.update({
    works: [item("old")],
    contentVersion: "v1",
    nextCursor: "old-cursor",
  });
  await p.loadWorks(false);
  assert.equal(calls, 2);
  assert.deepEqual(
    p.data.works.map((w) => w.id),
    ["new"],
  );
});
test("arbitrary paging failure is visible and is not retried", async () => {
  let calls = 0;
  const p = page("works/works", {
    getWorks: async () => {
      calls++;
      throw Object.assign(new Error("invalid"), {
        code: "PUBLIC_QUERY_INVALID",
      });
    },
  });
  p.update({ works: [item("old")], contentVersion: "v1", nextCursor: "c" });
  await p.loadWorks(false);
  assert.equal(calls, 1);
  assert.equal(p.data.loadMoreError, true);
});
for (const [route, loader, field] of [
  ["index/index", "loadHome", "home"],
  ["works/works", "loadWorks", "works"],
  ["works/workdetail", "loadWork", "work"],
  ["package/package", "loadPackages", "packages"],
  ["package/pkgdetail", "loadPackage", "pkg"],
  ["about/about", "loadBrand", "brand"],
  ["booking/booking", "load", "slots"],
  ["mine/mine", "load", "cards"],
]) {
  test("native hide/resume/refresh/unload lifecycle: " + route, async () => {
    const p = page(route);
    let reloads = 0;
    p[loader] = async () => {
      reloads++;
    };
    p.update({ status: "success", dataMode: "demo" });
    const previous = p._generation;
    p.onHide();
    assert.equal(p._generation, previous + 1);
    assert.equal(p.data.status, "loading");
    assert.equal(p.data.dataMode, "");
    assert.ok(p.data[field]);
    p.onShow();
    assert.equal(reloads, 1);
    await p.onPullDownRefresh();
    assert.equal(reloads, 2);
    p.onUnload();
    const commits = p.commits;
    p.update({ status: "success" });
    assert.equal(p.commits, commits);
  });
}
test("late response after hide cannot republish old content", async () => {
  let finish;
  const p = page("index/index", {
    getHome: () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  });
  const pending = p.loadHome();
  p.onHide();
  finish(home());
  await pending;
  assert.equal(p.data.status, "loading");
  assert.equal(p.data.home.brand.name, undefined);
});
test("newer response wins over an earlier in-flight request", async () => {
  const finishes = [];
  const p = page("index/index", {
    getHome: () => new Promise((resolve) => finishes.push(resolve)),
  });
  const first = p.loadHome(),
    second = p.loadHome();
  const h = home();
  h.brand.name = "第二次";
  finishes[1](h);
  await second;
  finishes[0](home());
  await first;
  assert.equal(p.data.home.brand.name, "第二次");
  assert.equal(p.data.status, "success");
});
test("actual WXML dataset handler does not leak the native event into reset argument", async () => {
  const p = page("works/works");
  let argument = "unset";
  p.loadWorks = (reset = true) => {
    argument = reset;
  };
  p.handleLoadWorksTap({ type: "tap", currentTarget: { dataset: {} } });
  assert.equal(argument, true);
});
test("native present-only view fields are idempotent and preserve raw DTO text arrays", () => {
  const {
    packageView,
    homeView,
    serviceViews,
  } = require("../presentation/views.js");
  const pkg = { serviceItems: ["a"], tips: ["b"], notices: ["c"] };
  assert.deepEqual(packageView(packageView(pkg)), packageView(pkg));
  assert.deepEqual(packageView(pkg).serviceItems, ["a"]);
  assert.deepEqual(homeView(homeView(home())), homeView(home()));
  assert.equal(serviceViews([{ packages: [] }])[0].number, "01");
});
test("placeholder QR is never presented as a confirmed scannable account", () => {
  let previews = 0;
  wx.previewImage = () => {
    previews++;
  };
  const p = page("about/about");
  p.update({ status: "success" });
  p.previewQr({
    id: "qr",
    qrCodeStatus: "PLACEHOLDER",
    qrCodeMedia: { src: "demo" },
  });
  assert.equal(previews, 0);
});
