// Real miniapp automation for the booking intake: it drives the built mp-weixin bundle inside the
// WeChat developer tools, so every assertion below comes from the shipped pages talking to a real
// HTTP API. Nothing here stubs the network or the page state.
import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);

const loadAutomator = () => {
  const candidates = [
    process.env.SOUTHLEAF_AUTOMATOR_PATH,
    path.resolve(
      here,
      "../../.trellis/.runtime/wechat-automation/node_modules/miniprogram-automator",
    ),
    path.resolve(
      here,
      "../../.trellis/.runtime/wechat-automation/node_modules/miniprogram-automator/out/index.js",
    ),
  ].filter(Boolean);
  for (const candidate of candidates) {
    try {
      return require(candidate);
    } catch {
      /* try the next candidate */
    }
  }
  throw new Error("MINIAPP_AUTOMATOR_MISSING");
};

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const FORBIDDEN = [
  "支付",
  "付款",
  "已支付",
  "在线支付",
  "立即购买",
  "定金",
  "退款",
];

const scanForPayment = (value, trail = "data") => {
  if (typeof value === "string") {
    for (const word of FORBIDDEN) {
      if (value.includes(word)) return `${trail} contains ${word}`;
    }
    return "";
  }
  if (Array.isArray(value)) {
    for (let index = 0; index < value.length; index += 1) {
      const hit = scanForPayment(value[index], `${trail}[${index}]`);
      if (hit) return hit;
    }
    return "";
  }
  if (value && typeof value === "object") {
    for (const key of Object.keys(value)) {
      const hit = scanForPayment(value[key], `${trail}.${key}`);
      if (hit) return hit;
    }
  }
  return "";
};

let lastProbe = "none";

const waitFor = async (label, probe, timeout = 30000) => {
  const deadline = Date.now() + timeout;
  let last = null;
  while (Date.now() < deadline) {
    try {
      const value = await probe();
      if (value) return value;
      last = lastProbe;
    } catch (error) {
      last = error.message;
    }
    await sleep(250);
  }
  throw new Error(`${label}_TIMEOUT:${last}`);
};

const settle = async (page) => {
  const data = await page.data();
  return data && data.status && !["loading"].includes(data.status)
    ? data
    : null;
};

const storage = async (mini, token) => {
  try {
    await mini.callWxMethod(
      "setStorageSync",
      "southleaf.booking.session",
      token,
    );
    const stored = await mini.callWxMethod(
      "getStorageSync",
      "southleaf.booking.session",
    );
    if (stored === token) return "callWxMethod";
  } catch {
    /* fall through to the app context */
  }
  await mini.evaluate((value) => {
    wx.setStorageSync("southleaf.booking.session", value);
  }, token);
  const confirmed = await mini.evaluate(() =>
    wx.getStorageSync("southleaf.booking.session"),
  );
  if (confirmed !== token) throw new Error("MINIAPP_SESSION_NOT_STORED");
  return "evaluate";
};

const shoot = async (mini, name) => {
  const directory = process.env.MINIAPP_SHOT_DIR;
  if (!directory) return "";
  fs.mkdirSync(directory, { recursive: true });
  const file = path.join(directory, `${name}.png`);
  await mini.screenshot({ path: file });
  return file;
};

const phaseCreate = async (mini) => {
  await mini.navigateTo("/pages/booking/booking");
  const page = await waitFor("BOOKING_PAGE", async () => {
    const current = await mini.currentPage();
    const path = current ? current.path : "none";
    if (!current || path !== "pages/booking/booking") {
      lastProbe = "path=" + path;
      return null;
    }
    const data = await current.data();
    lastProbe =
      "path=" +
      path +
      " status=" +
      (data && data.status) +
      " error=" +
      (data && (data.errorMessage || data.error));
    return data && data.status && data.status !== "loading" ? current : null;
  });
  const data = await page.data();
  if (data.status !== "success")
    throw new Error(`BOOKING_PAGE_STATUS:${data.status}`);
  // The client contract only distinguishes demo from production, so a synthetic dataset arrives as
  // "demo" and must show the demo banner. That is what keeps a test runtime from looking real.
  if (data.dataMode !== "demo")
    throw new Error(`BOOKING_PAGE_MODE:${data.dataMode}`);
  if (!Array.isArray(data.packages) || data.packages.length === 0)
    throw new Error("BOOKING_PAGE_NO_PACKAGES");
  if (!Array.isArray(data.slots) || data.slots.length === 0)
    throw new Error("BOOKING_PAGE_NO_SLOTS");
  const hit = scanForPayment(data);
  if (hit) throw new Error(`BOOKING_PAGE_PAYMENT_WORD:${hit}`);
  await shoot(mini, "booking-intake");

  const slotId = data.slots[0].slotId;
  await page.callMethod("handleSlotTap", {
    currentTarget: { dataset: { slot: slotId } },
  });
  const selected = await page.data();
  if (selected.selectedSlotId !== slotId)
    throw new Error("BOOKING_SLOT_NOT_SELECTED");

  await page.callMethod("submit");
  const detail = await waitFor("REQUEST_DETAIL_PAGE", async () => {
    const current = await mini.currentPage();
    const path = current ? current.path : "none";
    if (!current || path !== "pages/booking/requestdetail") {
      const pending = current ? await current.data() : null;
      lastProbe =
        "path=" +
        path +
        " submitting=" +
        (pending && pending.submitting) +
        " error=" +
        (pending && pending.error) +
        " message=" +
        (pending && pending.message);
      return null;
    }
    const data = await current.data();
    lastProbe =
      "path=" +
      path +
      " status=" +
      (data && data.status) +
      " error=" +
      (data && data.errorMessage);
    return data && data.status && data.status !== "loading" ? current : null;
  });
  const detailData = await detail.data();
  if (detailData.status !== "success")
    throw new Error(`DETAIL_STATUS:${detailData.status}`);
  if (detailData.statusLabel !== "待确认")
    throw new Error(`DETAIL_STATUS_LABEL:${detailData.statusLabel}`);
  if (detailData.hasOrder) throw new Error("DETAIL_UNEXPECTED_ORDER");
  const detailHit = scanForPayment(detailData);
  if (detailHit) throw new Error(`DETAIL_PAYMENT_WORD:${detailHit}`);
  await shoot(mini, "booking-request-pending");

  return {
    reservationId: detailData.id,
    slotId,
    packageVersionRef: detailData.request.packageVersionRef,
    statusLabel: detailData.statusLabel,
    hasOrder: detailData.hasOrder,
    slotLabel: detailData.slotLabel,
    expiresAt: detailData.expiresAt,
    dataMode: detailData.dataMode,
  };
};

const phaseConfirmed = async (mini, reservationId) => {
  await mini.navigateTo(
    `/pages/booking/requestdetail?reservationId=${encodeURIComponent(reservationId)}`,
  );
  const page = await waitFor("CONFIRMED_DETAIL_PAGE", async () => {
    const current = await mini.currentPage();
    const path = current ? current.path : "none";
    if (!current || path !== "pages/booking/requestdetail") {
      lastProbe = "path=" + path;
      return null;
    }
    const data = await current.data();
    lastProbe =
      "path=" +
      path +
      " status=" +
      (data && data.status) +
      " error=" +
      (data && data.errorMessage);
    return data && data.status && data.status !== "loading" ? current : null;
  });
  const data = await page.data();
  if (data.status !== "success")
    throw new Error(`CONFIRMED_STATUS:${data.status}`);
  if (data.statusLabel !== "已确认")
    throw new Error(`CONFIRMED_STATUS_LABEL:${data.statusLabel}`);
  if (!data.hasOrder || !data.order) throw new Error("CONFIRMED_ORDER_MISSING");
  for (const field of [
    "packageDisplayName",
    "slotLabel",
    "amountLabel",
    "settlementNotice",
  ]) {
    if (!data.order[field]) throw new Error(`CONFIRMED_ORDER_FIELD:${field}`);
  }
  const hit = scanForPayment(data);
  if (hit) throw new Error(`CONFIRMED_PAYMENT_WORD:${hit}`);
  await shoot(mini, "booking-request-confirmed");
  return {
    statusLabel: data.statusLabel,
    hasOrder: data.hasOrder,
    packageDisplayName: data.order.packageDisplayName,
    slotLabel: data.order.slotLabel,
    amountLabel: data.order.amountLabel,
    settlementNotice: data.order.settlementNotice,
    dataMode: data.dataMode,
  };
};

(async () => {
  const phase = process.env.MINIAPP_PHASE || "create";
  const endpoint = process.env.MINIAPP_WS || "ws://127.0.0.1:9420";
  const token = process.env.MINIAPP_SESSION_TOKEN || "";
  if (!token) throw new Error("MINIAPP_SESSION_TOKEN_REQUIRED");
  const automator = loadAutomator();
  const mini = await automator.connect({ wsEndpoint: endpoint });
  try {
    const storageMode = await storage(mini, token);
    const result =
      phase === "create"
        ? await phaseCreate(mini)
        : await phaseConfirmed(mini, process.env.MINIAPP_RESERVATION_ID || "");
    const payload = { phase, storageMode, ...result };
    console.log(
      `BOOKING_MINIAPP_${phase.toUpperCase()}:` + JSON.stringify(payload),
    );
  } finally {
    await mini.disconnect();
  }
})().catch((error) => {
  console.log(
    "BOOKING_MINIAPP_FAILURE:" +
      (error && error.message ? error.message : error),
  );
  process.exitCode = 1;
});
