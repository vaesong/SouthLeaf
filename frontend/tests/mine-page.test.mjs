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
let modals = [];
const wx = {
  getStorageSync: (key) => (storage.has(key) ? storage.get(key) : ""),
  setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: (key) => storage.delete(key),
  stopPullDownRefresh() {},
  showToast() {},
  showModal: (options) => modals.push(options),
  navigateTo: (options) => navigations.push(options.url),
  navigateBack() {},
  switchTab: (options) => navigations.push(options.url),
  login: (options) => options.success({ code: "test-booking-code" }),
  request: (options) => {
    wx.requests.push(options);
    options.success({
      statusCode: 200,
      data: {
        requestId: "req_1",
        code: "OK",
        message: "",
        data: { items: [] },
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
    _unloaded: false,
    setData(patch) {
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
const profile = {
  token: "signed-token-0000",
  customerRef: "cust_0001",
  displayName: "微信用户 3F2A19",
  expiresAt: "2026-10-25T02:00:00Z",
  identity: "WECHAT",
};
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
const confirmed = {
  ...pending,
  reservationId: "66666666-7777-4888-8999-000000000002",
  status: "CONFIRMED",
  displayStatus: "CONFIRMED",
  order: {
    orderId: "order-1",
    packageDisplayName: "虚构演示套餐",
    currency: "JPY",
    amountMinorUnits: 12345,
    amountLabel: "JP¥12,345",
    slotLabel: "2026-10-26 10:00-11:30",
    capturedAt: "2026-10-26T00:00:00Z",
    settlementNotice: "本阶段不收款，金额仅作记录；到店前请与工作室确认。",
  },
};

const ready = (items) => ({
  currentSession: async () => profile,
  getRequests: async () => ok({ items }),
});

test("我的页面列出本人预约，并复用统一的标签与金额口径", async () => {
  const host = page("mine/mine", ready([pending, confirmed]));
  await host.load();
  assert.equal(host.data.status, "success");
  assert.equal(host.data.identityLabel, "微信用户 3F2A19");
  assert.equal(host.data.cards.length, 2);
  assert.equal(host.data.cards[0].statusLabel, "待确认");
  assert.equal(host.data.cards[1].statusLabel, "已确认");
  assert.equal(host.data.cards[1].order.amountLabel, "JP¥12,345");
  assert.equal(host.data.orderCount, 1);
});

test("支付只出现在已生成订单的卡片上，且阶段二一律不可用", async () => {
  const host = page("mine/mine", ready([pending, confirmed]));
  await host.load();
  const [pendingCard, confirmedCard] = host.data.cards;
  assert.equal(pendingCard.payAction.visible, false);
  assert.equal(pendingCard.payAction.label, "");
  assert.equal(confirmedCard.payAction.visible, true);
  assert.equal(confirmedCard.payAction.enabled, false);
  assert.match(confirmedCard.payAction.label, /未开放/);
});

test("点击支付只解释一次，不发请求也不改状态", async () => {
  const host = page("mine/mine", ready([pending, confirmed]));
  await host.load();
  modals = [];
  wx.requests = [];
  const before = JSON.stringify(host.data.cards);
  host.handleSettleTap({
    currentTarget: { dataset: { id: confirmed.reservationId } },
  });
  assert.equal(modals.length, 1);
  assert.match(modals[0].content, /本阶段不收款/);
  assert.equal(modals[0].showCancel, false);
  assert.equal(wx.requests.length, 0);
  assert.equal(JSON.stringify(host.data.cards), before);
});

test("没有订单的申请点击支付什么都不做", async () => {
  const host = page("mine/mine", ready([pending]));
  await host.load();
  modals = [];
  host.handleSettleTap({
    currentTarget: { dataset: { id: pending.reservationId } },
  });
  assert.equal(modals.length, 0);
});

test("拿不到身份时给出可恢复的空态，而不是通用失败", async () => {
  const host = page("mine/mine", {
    currentSession: async () => {
      throw Object.assign(new Error("需要先登录"), {
        code: "BOOKING_IDENTITY_REJECTED",
      });
    },
    getRequests: async () => ok({ items: [] }),
  });
  await host.load();
  assert.equal(host.data.status, "unauthenticated");
  assert.equal(host.data.cards.length, 0);
});

test("列表为空时提示还没有提交过申请", async () => {
  const host = page("mine/mine", ready([]));
  await host.load();
  assert.equal(host.data.status, "empty");
});

test("卡片进入详情走既有的 requestdetail 路由", async () => {
  const host = page("mine/mine", ready([pending]));
  await host.load();
  navigations = [];
  host.handleCardTap({
    currentTarget: { dataset: { id: pending.reservationId } },
  });
  assert.equal(
    navigations[0],
    "/pages/booking/requestdetail?reservationId=" + pending.reservationId,
  );
});

test("我的页面不得自己写支付文案", () => {
  for (const file of [".js", ".wxml"]) {
    const text = fs.readFileSync(
      new URL("pages/mine/mine" + file, root),
      "utf8",
    );
    assert.doesNotMatch(text, /支付|付款|押金|已付|paid|payment/i);
  }
});

test("我的页面已在路由与底栏中登记，且只登记一次", () => {
  const config = JSON.parse(fs.readFileSync(new URL("app.json", root), "utf8"));
  assert.equal(config.pages.filter((p) => p === "pages/mine/mine").length, 1);
  assert.equal(
    config.tabBar.list.filter((i) => i.pagePath === "pages/mine/mine").length,
    1,
  );
  assert.equal(config.tabBar.list.length, 5);
});
