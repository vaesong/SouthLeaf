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
const storage = new Map();
let navigations = [];
const wx = {
  getStorageSync: (key) => (storage.has(key) ? storage.get(key) : ""),
  setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: (key) => storage.delete(key),
  stopPullDownRefresh() {},
  showToast() {},
  navigateTo: (options) => navigations.push(options.url),
  navigateBack: () => {},
  switchTab: () => {},
  login: (options) => options.success({ code: "test-booking-code" }),
  request: (options) => {
    wx.requests.push(options);
    options.success({
      statusCode: 200,
      data: {
        requestId: "req_1",
        code: "OK",
        message: "",
        data: {
          token: "signed-token-0000",
          customerRef: "cust_0001",
          displayName: "微信用户 3F2A19",
          expiresAt: "2026-10-25T02:00:00Z",
          identity: "WECHAT",
        },
        meta: {
          serverTime: "2026-10-25T00:00:00Z",
          contentVersion: "booking-1.0",
          nextCursor: null,
          dataMode: "demo",
        },
      },
    });
  },
  requests: [],
};
globalThis.wx = wx;
globalThis.getApp = () => app;
function page(route, ports = {}) {
  const file = new URL("pages/" + route + ".js", root);
  let definition;
  const context = vm.createContext({
    Page: (value) => {
      definition = value;
    },
    wx,
    getApp: () => app,
    require(id) {
      if (id === "../../api/booking.js") return ports;
      return createRequire(file)(id);
    },
  });
  new vm.Script(fs.readFileSync(file, "utf8"), {
    filename: file.pathname,
  }).runInContext(context);
  return {
    ...definition,
    data: JSON.parse(JSON.stringify(definition.data)),
    _generation: 0,
    commits: 0,
    setData(patch) {
      this.commits++;
      Object.assign(this.data, patch);
    },
  };
}
const slot = {
  slotId: "11111111-2222-4333-8444-555555555555",
  startAt: "2026-10-26T01:00:00Z",
  endAt: "2026-10-26T02:30:00Z",
  localDate: "2026-10-26",
  localStart: "10:00",
  localEnd: "11:30",
  zoneLabel: "+09:00",
};
const ok = (data) => ({
  data,
  meta: {
    serverTime: "2026-10-25T00:00:00Z",
    contentVersion: "booking-1.0",
    nextCursor: null,
    dataMode: "demo",
  },
  requestId: "req_1",
});
const packages = () =>
  ok({
    items: [
      { packageVersionRef: "pkgv-synthetic-1", displayName: "虚构演示套餐" },
    ],
  });
const slotPage = () => ok({ items: [slot] });
const requestList = (items = []) => ok({ items });
const pending = {
  reservationId: "66666666-7777-4888-8999-000000000001",
  status: "PENDING_CONFIRMATION",
  displayStatus: "PENDING_CONFIRMATION",
  createdAt: "2026-10-25T00:00:00Z",
  expiresAt: "2026-10-27T00:00:00Z",
  packageVersionRef: "pkgv-synthetic-1",
  requestedSlot: slot,
  order: null,
};

test("booking entry never offers a payment action or a paid result", () => {
  for (const file of ["wxml", "js"]) {
    const text = fs.readFileSync(
      new URL("pages/booking/booking." + file, root),
      "utf8",
    );
    assert.doesNotMatch(text, /支付|付款|押金|已付|paid|payment/i);
    const detail = fs.readFileSync(
      new URL("pages/booking/requestdetail." + file, root),
      "utf8",
    );
    assert.doesNotMatch(detail, /支付|付款|押金|已付|paid|payment/i);
  }
});

test("booking routes are declared exactly once in the approved route list", () => {
  const pages = JSON.parse(
    fs.readFileSync(new URL("app.json", root), "utf8"),
  ).pages;
  assert.equal(pages.filter((p) => p === "pages/booking/booking").length, 1);
  assert.equal(
    pages.filter((p) => p === "pages/booking/requestdetail").length,
    1,
  );
  assert.equal(new Set(pages).size, pages.length);
});

test("every bound handler exists on the booking page definition", () => {
  for (const route of [
    "pages/booking/booking",
    "pages/booking/requestdetail",
  ]) {
    const host = page(route.replace("pages/", ""));
    const text = fs.readFileSync(new URL(route + ".wxml", root), "utf8");
    for (const match of text.matchAll(
      /bind(?:tap|change|error)="([A-Za-z0-9_]+)"/g,
    ))
      assert.equal(
        typeof host[match[1]],
        "function",
        route + " -> " + match[1],
      );
  }
});

test("booking page presents slots and keeps the selected slot across a reload", async () => {
  const host = page("booking/booking", {
    getPackages: async () => packages(),
    getSlots: async () => slotPage(),
    getRequests: async () => requestList([pending]),
  });
  host.onLoad();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(host.data.status, "success");
  assert.equal(host.data.packageLabels[0], "虚构演示套餐");
  assert.equal(host.data.slotRows[0].label, "2026-10-26 10:00-11:30");
  assert.equal(host.data.requestRows[0].statusLabel, "待确认");
  host.handleSlotTap({ currentTarget: { dataset: { slot: slot.slotId } } });
  assert.equal(host.data.selectedSlotId, slot.slotId);
  assert.equal(host.data.slotRows[0].active, true);
  assert.ok(host._pendingKey.startsWith("mp-"));
});

test("submit sends one idempotent request and opens the receipt page", async () => {
  const calls = [];
  const host = page("booking/booking", {
    getPackages: async () => packages(),
    getSlots: async () => slotPage(),
    getRequests: async () => requestList(),
    createRequest: async (key, slotId, ref) => {
      calls.push({ key, slotId, ref });
      return ok(pending);
    },
  });
  navigations = [];
  host.onLoad();
  await new Promise((resolve) => setImmediate(resolve));
  host.handleSlotTap({ currentTarget: { dataset: { slot: slot.slotId } } });
  const key = host._pendingKey;
  await host.submit();
  assert.equal(calls.length, 1);
  assert.equal(calls[0].key, key);
  assert.equal(calls[0].slotId, slot.slotId);
  assert.equal(calls[0].ref, "pkgv-synthetic-1");
  assert.equal(host.data.selectedSlotId, "");
  assert.equal(
    navigations[0],
    "/pages/booking/requestdetail?reservationId=" + pending.reservationId,
  );
});

test("a refused submission is shown and never navigates away", async () => {
  const host = page("booking/booking", {
    getPackages: async () => packages(),
    getSlots: async () => slotPage(),
    getRequests: async () => requestList(),
    createRequest: async () => {
      throw Object.assign(new Error("该时段已被占用，请换一个时段"), {
        code: "BOOKING_SLOT_UNAVAILABLE",
      });
    },
  });
  navigations = [];
  host.onLoad();
  await new Promise((resolve) => setImmediate(resolve));
  host.handleSlotTap({ currentTarget: { dataset: { slot: slot.slotId } } });
  await host.submit();
  assert.equal(navigations.length, 0);
  assert.equal(host.data.error, "该时段已被占用，请换一个时段");
  assert.equal(host.data.submitting, false);
});

test("confirmed request renders the agreement and the settlement notice", () => {
  const order = {
    orderId: "order-1",
    packageDisplayName: "虚构演示套餐",
    currency: "JPY",
    amountMinorUnits: 12345,
    amountLabel: "JP¥12,345",
    slotLabel: "2026-10-26 10:00-11:30",
    capturedAt: "2026-10-26T00:00:00Z",
    settlementNotice: "本阶段不收款，金额仅作记录；到店前请与工作室确认。",
  };
  const host = page("booking/requestdetail", {
    getRequest: async () =>
      ok({
        ...pending,
        status: "CONFIRMED",
        displayStatus: "CONFIRMED",
        order,
      }),
  });
  host.update({ request: { ...pending, displayStatus: "CONFIRMED", order } });
  assert.equal(host.data.statusLabel, "已确认");
  assert.equal(host.data.hasOrder, true);
  assert.equal(host.data.order.amountLabel, "JP¥12,345");
  assert.match(host.data.order.settlementNotice, /不收款/);
});

test("an unknown request is reported as unavailable, not as a generic failure", async () => {
  const host = page("booking/requestdetail", {
    getRequest: async () => {
      throw Object.assign(new Error("预约记录不存在"), {
        code: "BOOKING_RESOURCE_UNAVAILABLE",
      });
    },
  });
  host.update({ id: "66666666-7777-4888-8999-000000000001" });
  await host.load();
  assert.equal(host.data.status, "unavailable");
});

test("booking api stores the session token and sends it with idempotency keys", async () => {
  storage.clear();
  wx.requests = [];
  const api = require("../api/booking.js");
  assert.equal(api.token(), "");
  await api.ensureSession();
  assert.equal(storage.get("southleaf.booking.session"), "signed-token-0000");
  await api.ensureSession();
  assert.equal(wx.requests.length, 1);
  const session = await api.signIn();
  assert.equal(typeof session.token, "string");
  assert.equal(wx.requests.at(-1).method, "POST");
  assert.equal(
    wx.requests.at(-1).url,
    "http://demo.test/api/v1/public/booking/session",
  );
  assert.equal(wx.requests.at(-1).data.loginCode, "test-booking-code");
});

test("hide clears the booking page so stale slots never republish", async () => {
  const host = page("booking/booking", {
    getPackages: async () => packages(),
    getSlots: async () => slotPage(),
    getRequests: async () => requestList([pending]),
  });
  host.onLoad();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(host.data.dataMode, "demo");
  host.onHide();
  assert.equal(host.data.status, "loading");
  assert.equal(host.data.dataMode, "");
  const commits = host.commits;
  host.onUnload();
  host.update({ status: "success" });
  assert.equal(host.commits, commits);
});
