import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
const root = new URL("../", import.meta.url);
const text = (file) => fs.readFileSync(new URL(file, root), "utf8");
const routes = JSON.parse(text("app.json")).pages;
test("runtime is exclusively native App/Page/Component with no parallel framework source", () => {
  assert.match(text("app.js"), /^App\(/);
  assert.match(text("components/demo-notice/index.js"), /^Component\(/);
  for (const r of routes) {
    assert.match(text(r + ".js"), /Page\(\{/);
    assert.doesNotMatch(text(r + ".js"), /\buni\.|Vue|\$vm|mixins/);
    assert.doesNotMatch(text(r + ".wxml"), /v-if|v-for|@click|<DemoNotice/);
    assert.equal(fs.existsSync(new URL(r + ".vue", root)), false);
  }
  for (const f of [
    "main.js",
    "App.vue",
    "manifest.json",
    "pages.json",
    "vite.config.js",
    "scripts/run-uni.mjs",
    "uni_modules",
  ])
    assert.equal(fs.existsSync(new URL(f, root)), false, f);
  const pkg = JSON.parse(text("package.json"));
  assert.equal(Object.keys(pkg.dependencies || {}).length, 0);
  assert.deepEqual(Object.keys(pkg.devDependencies), ["prettier"]);
});
test("detail pages have no contact CTAs; UI retains direct controls and vertical teams", () => {
  for (const r of ["pages/works/workdetail", "pages/package/pkgdetail"])
    assert.doesNotMatch(
      text(r + ".wxml") + text(r + ".js"),
      /微信联系|微信咨询|联系工作室|contact-actions|makePhoneCall/,
    );
  assert.match(
    text("pages/works/works.wxml"),
    /bindtap="handleCycleLayoutTap"/,
  );
  assert.match(text("pages/works/works.wxml"), /sort-popover/);
  assert.doesNotMatch(
    text("pages/package/package.wxml"),
    /<scroll-view|filter-chip|business-grid/,
  );
  assert.match(text("pages/about/about.wxml"), /team-row/);
  assert.doesNotMatch(text("pages/about/about.wxml"), /team-specialties/);
});
test("every page that renders the demo notice declares the component", () => {
  for (const route of routes) {
    const wxml = text(route + ".wxml");
    if (!wxml.includes("<demo-notice")) continue;
    const config = JSON.parse(text(route + ".json"));
    assert.equal(
      config.usingComponents?.["demo-notice"],
      "../../components/demo-notice/index",
      route + " must declare demo-notice",
    );
  }
});

test("active static source declares only the approved PNGs", () => {
  const names = fs.readdirSync(new URL("static/icons/", root)).sort();
  assert.equal(names.length, 10);
  for (const n of names)
    assert.match(n, /^tab-(home|works|services|about|mine)(-active)?\.png$/);
});

test("native WXML expressions do not contain HTML-escaped operators or JS unicode escapes", () => {
  for (const route of routes) {
    for (const match of text(route + ".wxml").matchAll(/{{([\s\S]*?)}}/g)) {
      assert.doesNotMatch(
        match[1],
        /&(?:amp|lt|gt);|\\(?:u[0-9a-f]{4}|x[0-9a-f]{2})/i,
        route,
      );
    }
  }
});

test("native tags never have duplicate data attributes for different event handlers", () => {
  for (const route of routes) {
    for (const tag of text(route + ".wxml").matchAll(
      /<[\w-]+(?:[^"'<>]|"[^"]*"|'[^']*')*>/g,
    )) {
      const names = [...tag[0].matchAll(/\s([\w:-]+)\s*=/g)].map((m) => m[1]);
      assert.equal(new Set(names).size, names.length, route + ": " + tag[0]);
    }
  }
});
