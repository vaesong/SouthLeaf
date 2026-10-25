import assert from "node:assert/strict";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { chromium } from "playwright-core";

const baseUrl = process.env.S6_E2E_BASE_URL;
const accountId = process.env.S6_E2E_ACCOUNT;
const password = process.env.S6_E2E_PASSWORD;
const fixtureRoot = process.env.S6_E2E_FIXTURE_ROOT;
const profileDir = process.env.S6_E2E_PROFILE_DIR;
const rescheduleId = process.env.S6_E2E_RESCHEDULE_ID;
const cancelId = process.env.S6_E2E_CANCEL_ID;
const targetSlotId = process.env.S6_E2E_TARGET_SLOT_ID;
const deadMessageId = process.env.S6_E2E_DEAD_MESSAGE_ID;

assert.ok(baseUrl && /^https:\/\/localhost:[0-9]{5}$/.test(baseUrl));
assert.ok(accountId && /^s1d\.[a-z0-9]{8}$/.test(accountId));
assert.ok(password && password.length >= 20);
assert.ok(fixtureRoot && profileDir);
assert.equal(path.dirname(path.resolve(profileDir)), path.resolve(fixtureRoot));
assert.match(rescheduleId ?? "", /^[0-9a-f-]{36}$/i);
assert.match(cancelId ?? "", /^[0-9a-f-]{36}$/i);
assert.match(targetSlotId ?? "", /^[0-9a-f-]{36}$/i);
assert.match(deadMessageId ?? "", /^[0-9a-f-]{36}$/i);

await mkdir(profileDir);
console.log("S6_BROWSER_STAGE:launch");
const context = await chromium.launchPersistentContext(profileDir, {
  channel: "msedge",
  headless: true,
  viewport: { width: 1280, height: 900 },
  ignoreHTTPSErrors: true,
  args: [
    "--no-first-run",
    "--no-default-browser-check",
    "--disable-extensions",
    "--disable-sync",
    "--disable-background-networking",
    "--disable-component-update",
    "--disable-default-apps",
    "--host-resolver-rules=MAP localhost 127.0.0.1",
  ],
});

try {
  const page = await context.newPage();
  const externalOrigins = new Set();
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (url.protocol !== "data:" && url.origin !== baseUrl) externalOrigins.add(url.origin);
  });

  console.log("S6_BROWSER_STAGE:login-page");
  await page.goto(`${baseUrl}/login`, { waitUntil: "domcontentloaded", timeout: 20000 });
  await page.getByRole("heading", { name: "管理入口" }).waitFor({ timeout: 15000 });
  await page.locator('input[name="username"]').fill(accountId);
  await page.locator('input[name="password"]').fill(password);
  await page.waitForFunction(
    () => {
      const submit = document.querySelector('button[type="submit"]');
      return submit && !submit.disabled;
    },
    null,
    { timeout: 15000 },
  );
  await page.locator('button[type="submit"]').click();
  await page.waitForFunction(() => location.pathname === "/", null, { timeout: 20000 });
  console.log("S6_BROWSER_STAGE:authenticated");
  await page.goto(`${baseUrl}/bookings`, { waitUntil: "domcontentloaded", timeout: 20000 });
  await page.getByRole("heading", { name: "預約與訂單快照" }).waitFor({ timeout: 15000 });
  console.log("S6_BROWSER_STAGE:bookings-page");

  async function lookup(reservationId) {
    await page.locator("#reservation-id").fill(reservationId);
    await page.getByRole("button", { name: "查閱預約" }).click();
    await page.getByRole("heading", { name: "預約詳情" }).waitFor({ timeout: 15000 });
    await page.locator(".detail-section").getByText(reservationId, { exact: true }).waitFor();
  }

  await lookup(rescheduleId);
  console.log("S6_BROWSER_STAGE:reschedule-loaded");
  assert.match(await page.locator(".detail-section").innerText(), /CONFIRMED/);
  assert.match(await page.locator(".order-section").innerText(), /S6 Synthetic Test Package/);
  assert.doesNotMatch(await page.locator(".booking-card").innerText(), /S6 Synthetic Customer/);

  await page.getByRole("button", { name: "查詢可用改期時段" }).click();
  await page.locator("#new-slot").waitFor({ timeout: 10000 });
  const slotIds = await page
    .locator("#new-slot option")
    .evaluateAll((options) => options.map((option) => option.value));
  console.log(`S6_BROWSER_SLOTS:${slotIds.join(",")}`);
  assert.deepEqual(slotIds, [targetSlotId]);
  await page.locator("#new-slot").selectOption(targetSlotId);
  await Promise.all([
    page.waitForEvent("dialog").then(async (dialog) => {
      assert.match(dialog.message(), /確認改期/);
      await dialog.accept();
    }),
    page.getByRole("button", { name: "確認改期" }).click(),
  ]);
  await page
    .getByRole("alert")
    .getByText("改期已記錄", { exact: false })
    .waitFor({ timeout: 15000 });
  console.log("S6_BROWSER_STAGE:rescheduled");
  assert.match(await page.locator(".detail-section").innerText(), new RegExp(targetSlotId));
  assert.match(await page.locator(".revision-list").innerText(), /修訂 2/);

  await lookup(cancelId);
  console.log("S6_BROWSER_STAGE:cancel-loaded");
  assert.match(await page.locator(".detail-section").innerText(), /CONFIRMED/);
  await Promise.all([
    page.waitForEvent("dialog").then(async (dialog) => {
      assert.match(dialog.message(), /確認取消此預約/);
      await dialog.accept();
    }),
    page.getByRole("button", { name: "取消預約" }).click(),
  ]);
  await page
    .getByRole("alert")
    .getByText("取消已記錄", { exact: false })
    .waitFor({ timeout: 15000 });
  console.log("S6_BROWSER_STAGE:cancelled");
  assert.match(await page.locator(".detail-section").innerText(), /CANCELLED/);
  assert.match(await page.locator(".order-section").innerText(), /CANCELLED/);

  await page.goto(`${baseUrl}/outbox`, { waitUntil: "domcontentloaded", timeout: 20000 });
  await page.getByRole("heading", { name: "通知失败待办" }).waitFor({ timeout: 15000 });
  await page.getByText("测试接收器 TEST_SINK", { exact: false }).waitFor({ timeout: 15000 });
  const deadLetterRow = page.getByRole("row").filter({ hasText: deadMessageId });
  await deadLetterRow.waitFor({ timeout: 15000 });
  console.log("S6_BROWSER_STAGE:outbox-failure-loaded");
  await Promise.all([
    page.waitForEvent("dialog").then(async (dialog) => {
      assert.match(dialog.message(), /TEST_SINK/);
      await dialog.accept();
    }),
    deadLetterRow.getByRole("button", { name: `人工恢复通知 ${deadMessageId}` }).click(),
  ]);
  await page
    .getByRole("alert")
    .getByText("恢复命令已记账并重新排队", { exact: false })
    .waitFor({ timeout: 15000 });
  console.log("S6_BROWSER_STAGE:outbox-recovery-recorded");

  assert.equal(await page.evaluate(() => localStorage.length + sessionStorage.length), 0);
  assert.deepEqual([...externalOrigins], []);
  console.log("S6_BROWSER_PASS:booking_lifecycle_outbox_recovery_same_origin_no_browser_storage");
} catch (error) {
  const failure = error instanceof Error ? `${error.name}:${error.message}` : "UNKNOWN";
  console.log(`S6_BROWSER_FAIL:${failure.slice(0, 240)}`);
  throw error;
} finally {
  await context.close();
}
