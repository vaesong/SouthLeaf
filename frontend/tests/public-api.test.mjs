import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const app = { runtime: { apiOrigin: "http://demo.test", release: false } };
globalThis.getApp = () => app;
const success = (data = {}) => ({
  statusCode: 200,
  data: {
    code: "OK",
    data,
    requestId: "req-test",
    meta: { contentVersion: "v1", dataMode: "demo", nextCursor: null },
  },
});
globalThis.wx = {
  showToast() {},
  request(options) {
    options.success(success());
  },
};
const api = require("../api/api.js");
const mapped = require("../api/public.js");
const display = require("../api/public-display.js");
const media = (id = "a") => ({
  id,
  previewUrl: `/api/v1/public/media/${id}/preview/content`,
  thumbnailUrl: `/api/v1/public/media/${id}/thumbnail/content`,
  width: 960,
  height: 1200,
  altText: "demo art",
  sortOrder: 0,
});
const contact = () => ({
  phone: "",
  address: "address",
  workingHours: "hours",
  channels: [],
});
const channel = (id = "wx") => ({
  id,
  platform: "wechat",
  label: "微信",
  account: "demo",
  qrCodeMedia: media("qr"),
  qrCodeStatus: "PLACEHOLDER",
});
const brand = () => ({
  name: "Studio",
  englishName: "STUDIO",
  discoveryHeading: "FIND STUDIO",
  shareTitle: "Studio share",
  tagline: "tag",
  description: "desc",
  logoMedia: null,
  team: [],
  contact: contact(),
});
const pkg = () => ({
  id: "p",
  businessId: "b",
  businessName: "Business",
  name: "Package",
  summary: "summary",
  priceType: "INQUIRY",
  displayPrice: "inquiry",
  coverMedia: media(),
});
const work = () => ({
  id: "w",
  title: "Work",
  summary: "summary",
  description: "description",
  media: [media()],
  businessLabels: ["Business"],
  packageLabel: "Package",
  contact: contact(),
  publishedAt: "2026-10-01T00:00:00Z",
});

test("only qualified same-host media references are accepted", () => {
  assert.equal(
    mapped.mapMedia(media()).src,
    "http://demo.test/api/v1/public/media/a/preview/content",
  );
  assert.throws(
    () =>
      mapped.mapMedia({
        ...media(),
        previewUrl: "https://untrusted.test/a.png",
      }),
    (error) => error.code === "PUBLIC_RESPONSE_INVALID",
  );
  assert.throws(() =>
    mapped.mapMedia({
      ...media(),
      previewUrl: "/api/v1/public/media/other/preview/content",
    }),
  );
});
test("old price enums and malformed records are rejected, not translated", () => {
  assert.equal(mapped.mapPackageSummary(pkg()).priceType, "INQUIRY");
  for (const priceType of ["CONSULT", "FROM_PRICE", "unknown"])
    assert.throws(
      () => mapped.mapPackageSummary({ ...pkg(), priceType }),
      (error) => error.code === "PUBLIC_RESPONSE_INVALID",
    );
  assert.throws(() => mapped.mapPackageSummary({ priceType: "INQUIRY" }));
});
test("detail media is explicit and duplicates or empty galleries are rejected", () => {
  assert.equal(mapped.mapWorkDetail(work()).coverMedia.id, "a");
  assert.throws(() => mapped.mapWorkDetail({ ...work(), media: [] }));
  assert.throws(() =>
    mapped.mapWorkDetail({ ...work(), media: [media(), media()] }),
  );
});
test("channels use the new list contract without account compatibility aliases", () => {
  const c = mapped.mapContact({
    ...contact(),
    channels: [channel("wx"), { ...channel("dy"), platform: "douyin" }],
  });
  assert.deepEqual(
    c.channels.map((v) => v.id),
    ["wx", "dy"],
  );
  assert.throws(() => mapped.mapContact({ wechat: "old-only" }));
  assert.throws(() =>
    mapped.mapContact({ ...contact(), channels: [channel(), channel()] }),
  );
});
test("brand, QR, team and share fields are read from the contract", () => {
  const b = mapped.mapBrand({
    ...brand(),
    team: [
      {
        id: "p",
        name: "Demo",
        role: "摄影师",
        bio: "bio",
        specialties: [],
        portraitMedia: media("person"),
      },
    ],
    contact: { ...contact(), channels: [channel()] },
  });
  assert.equal(b.englishName, "STUDIO");
  assert.equal(b.shareTitle, "Studio share");
  assert.equal(b.team[0].portraitMedia.id, "person");
  assert.equal(b.contact.channels[0].qrCodeMedia.id, "qr");
  assert.throws(() => mapped.mapBrand({}));
});
test("transport preserves metadata only after validating the new envelope", async () => {
  wx.request = (options) => options.success(success());
  const result = await api.get("/api/v1/public/home");
  assert.equal(result.meta.contentVersion, "v1");
  assert.equal(result.meta.dataMode, "demo");
  assert.equal(app.globalData, undefined);
});
test("legacy numeric codes and incomplete success metadata are protocol errors", async () => {
  wx.request = (o) =>
    o.success({ ...success(), data: { ...success().data, code: 200 } });
  await assert.rejects(
    api.get("/x"),
    (e) => e.code === "PUBLIC_RESPONSE_INVALID",
  );
  wx.request = (o) =>
    o.success({
      statusCode: 200,
      data: { code: "OK", data: {}, requestId: "req", meta: {} },
    });
  await assert.rejects(
    api.get("/x"),
    (e) => e.code === "PUBLIC_RESPONSE_INVALID",
  );
});
test("network failures are never successful empty lists", async () => {
  wx.request = (o) => o.fail({ errMsg: "timeout" });
  await assert.rejects(api.get("/x"), (e) => e.code === "REQUEST_TIMEOUT");
});
test("release refuses demo success but preserves valid resource errors", async () => {
  app.runtime.release = true;
  try {
    wx.request = (o) => o.success(success());
    await assert.rejects(
      api.get("/x"),
      (e) => e.code === "PUBLIC_SERVICE_UNAVAILABLE",
    );
    wx.request = (o) =>
      o.success({
        statusCode: 404,
        data: {
          code: "PUBLIC_RESOURCE_UNAVAILABLE",
          data: null,
          requestId: "req",
          meta: {},
        },
      });
    await assert.rejects(
      api.get("/x"),
      (e) => e.code === "PUBLIC_RESOURCE_UNAVAILABLE",
    );
  } finally {
    app.runtime.release = false;
  }
});
test("cursor expiry remains a distinct conflict, not a generic invalid query", async () => {
  wx.request = (o) =>
    o.success({
      statusCode: 409,
      data: {
        code: "PUBLIC_CURSOR_EXPIRED",
        data: null,
        requestId: "req",
        meta: {},
      },
    });
  await assert.rejects(
    api.get("/x"),
    (e) => e.code === "PUBLIC_CURSOR_EXPIRED",
  );
});
test("list payload and metadata must carry the same cursor", async () => {
  api.get = async () => ({
    data: { items: [], nextCursor: "a" },
    meta: { nextCursor: "b", contentVersion: "v1" },
    requestId: "req",
  });
  await assert.rejects(
    mapped.getWorks(),
    (error) => error.code === "PUBLIC_RESPONSE_INVALID",
  );
});
test("gallery uses validated detail media without substituting summary covers", () => {
  const a = { id: "a", src: "a.png" };
  assert.deepEqual(display.galleryMedia({ media: [a] }), [a]);
  assert.deepEqual(display.galleryMedia({ coverMedia: a, media: [] }), []);
});
test("vertical service groups preserve business and package order", () => {
  const groups = display.groupServices(
    [{ id: "family" }, { id: "child" }],
    [
      { id: "a", businessId: "child" },
      { id: "b", businessId: "family" },
    ],
  );
  assert.deepEqual(
    groups.map((g) => g.packages.map((p) => p.id)),
    [["b"], ["a"]],
  );
});
test("layout preferences are non-authoritative UI state and storage failure is harmless", () => {
  wx.getStorageSync = () => "broken";
  assert.equal(display.readLayout("key", ["grid", "list"], "grid"), "grid");
  wx.getStorageSync = () => "list";
  assert.equal(display.readLayout("key", ["grid", "list"], "grid"), "list");
  wx.setStorageSync = () => {
    throw new Error("full");
  };
  assert.doesNotThrow(() => display.rememberLayout("key", "grid"));
});
test("all layout controls cycle directly and wrap", () => {
  assert.equal(
    display.nextLayout("grid", ["grid", "editorial", "list"]),
    "editorial",
  );
  assert.equal(
    display.nextLayout("list", ["grid", "editorial", "list"]),
    "grid",
  );
  assert.equal(display.nextLayout("album", ["album", "overview"]), "overview");
});
