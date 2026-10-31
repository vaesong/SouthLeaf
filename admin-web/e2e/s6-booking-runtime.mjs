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
const confirmId = process.env.S6_E2E_CONFIRM_ID;
const confirmSlotId = process.env.S6_E2E_CONFIRM_SLOT_ID;
const rejectId = process.env.S6_E2E_REJECT_ID;
const conflictSlotId = process.env.S6_E2E_CONFLICT_SLOT_ID;

assert.ok(baseUrl && /^https:\/\/localhost:[0-9]{5}$/.test(baseUrl));
assert.ok(accountId && /^s1d\.[a-z0-9]{8}$/.test(accountId));
assert.ok(password && password.length >= 20);
assert.ok(fixtureRoot && profileDir);
assert.equal(path.dirname(path.resolve(profileDir)), path.resolve(fixtureRoot));
assert.match(rescheduleId ?? "", /^[0-9a-f-]{36}$/i);
assert.match(cancelId ?? "", /^[0-9a-f-]{36}$/i);
assert.match(targetSlotId ?? "", /^[0-9a-f-]{36}$/i);
assert.match(deadMessageId ?? "", /^[0-9a-f-]{36}$/i);
assert.match(confirmId ?? "", /^[0-9a-f-]{36}$/i);
assert.match(confirmSlotId ?? "", /^[0-9a-f-]{36}$/i);
assert.match(rejectId ?? "", /^[0-9a-f-]{36}$/i);
assert.match(conflictSlotId ?? "", /^[0-9a-f-]{36}$/i);

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
  await page.getByRole("heading", { name: "预约与订单快照" }).waitFor({ timeout: 15000 });
  console.log("S6_BROWSER_STAGE:bookings-page");

  async function lookup(reservationId) {
    await page.locator("#reservation-id").fill(reservationId);
    await page.getByRole("button", { name: "查阅预约" }).click();
    await page.getByRole("heading", { name: "预约详情" }).waitFor({ timeout: 15000 });
    await page.locator(".detail-section").getByText(reservationId, { exact: true }).waitFor();
  }

  await lookup(rescheduleId);
  console.log("S6_BROWSER_STAGE:reschedule-loaded");
  assert.match(await page.locator(".detail-section").innerText(), /CONFIRMED/);
  assert.match(await page.locator(".order-section").innerText(), /S6 Synthetic Test Package/);
  assert.doesNotMatch(await page.locator(".booking-card").innerText(), /S6 Synthetic Customer/);

  await page.getByRole("button", { name: "查询可用改期时段" }).click();
  await page.locator("#new-slot").waitFor({ timeout: 10000 });
  const slotIds = await page
    .locator("#new-slot option")
    .evaluateAll((options) => options.map((option) => option.value));
  console.log(`S6_BROWSER_SLOTS:${slotIds.join(",")}`);
  // Two slots are free in this fixture: the reschedule target and the untouched confirm slot.
  assert.ok(slotIds.includes(targetSlotId));
  assert.equal(new Set(slotIds).size, slotIds.length);
  await page.locator("#new-slot").selectOption(targetSlotId);
  await Promise.all([
    page.waitForEvent("dialog").then(async (dialog) => {
      assert.match(dialog.message(), /确认改期/);
      await dialog.accept();
    }),
    page.getByRole("button", { name: "确认改期" }).click(),
  ]);
  await page
    .getByRole("alert")
    .getByText("改期已记录", { exact: false })
    .waitFor({ timeout: 15000 });
  console.log("S6_BROWSER_STAGE:rescheduled");
  assert.match(await page.locator(".detail-section").innerText(), new RegExp(targetSlotId));
  assert.match(await page.locator(".revision-list").innerText(), /修订 2/);

  // A refused reschedule must keep the original assignment: the target slot is already held by
  // another confirmed reservation, so the runtime answers 409 and the detail stays unchanged.
  const refused = await page.evaluate(
    async ({ id, slot }) => {
      const detail = await fetch(
        `/api/admin/v1/bookings/reservations/${encodeURIComponent(id)}`,
        { credentials: "same-origin" },
      );
      const etag = detail.headers.get("ETag") ?? "";
      const csrfEnvelope = await (
        await fetch("/api/admin/v1/auth/csrf", { credentials: "same-origin" })
      ).json();
      const token = csrfEnvelope?.data?.csrfToken ?? "";
      const response = await fetch(
        `/api/admin/v1/bookings/reservations/${encodeURIComponent(id)}/reschedule`,
        {
          method: "POST",
          credentials: "same-origin",
          headers: {
            "Content-Type": "application/json",
            "X-CSRF-TOKEN": token,
            "If-Match": etag,
            "Idempotency-Key": "s6-refused-reschedule-0001",
          },
          body: JSON.stringify({ slotId: slot, reasonCode: "S6_REFUSED_RESCHEDULE" }),
        },
      );
      const envelope = await response.json();
      return { status: response.status, code: envelope?.code, etag };
    },
    { id: rescheduleId, slot: conflictSlotId },
  );
  console.log("S6_BROWSER_REFUSED:" + refused.status + ":" + String(refused.code));
  assert.equal(refused.status, 409);
  assert.equal(refused.code, "SLOT_UNAVAILABLE");
  console.log("S6_BROWSER_STAGE:reschedule-refused");
  await lookup(rescheduleId);
  assert.match(await page.locator(".detail-section").innerText(), new RegExp(targetSlotId));
  assert.match(await page.locator(".revision-list").innerText(), /修订 2/);
  assert.doesNotMatch(await page.locator(".revision-list").innerText(), /修订 3/);
  console.log("S6_BROWSER_REFUSED_RESCHEDULE:PASS");

  await lookup(cancelId);
  console.log("S6_BROWSER_STAGE:cancel-loaded");
  assert.match(await page.locator(".detail-section").innerText(), /CONFIRMED/);
  await Promise.all([
    page.waitForEvent("dialog").then(async (dialog) => {
      assert.match(dialog.message(), /确认取消此预约/);
      await dialog.accept();
    }),
    page.getByRole("button", { name: "取消预约" }).click(),
  ]);
  await page
    .getByRole("alert")
    .getByText("取消已记录", { exact: false })
    .waitFor({ timeout: 15000 });
  console.log("S6_BROWSER_STAGE:cancelled");
  assert.match(await page.locator(".detail-section").innerText(), /CANCELLED/);
  assert.match(await page.locator(".order-section").innerText(), /CANCELLED/);

  await lookup(confirmId);
  console.log("S6_BROWSER_STAGE:confirm-loaded");
  assert.match(await page.locator(".detail-section").innerText(), /PENDING_CONFIRMATION/);
  const pendingOrderText =
    (await page.locator(".order-section").count()) === 0
      ? ""
      : await page.locator(".order-section").innerText();
  assert.doesNotMatch(pendingOrderText, /S6 Synthetic Test Package/);

  await page.getByRole("button", { name: "查询可占用时段" }).click();
  await page.locator("#confirm-slot").waitFor({ timeout: 15000 });
  await page.locator("#confirm-slot").selectOption(confirmSlotId);
  await Promise.all([
    page.waitForEvent("dialog").then(async (dialog) => {
      assert.match(dialog.message(), /请核对后确认/);
      await dialog.accept();
    }),
    page.getByRole("button", { name: "核对并确认成单" }).click(),
  ]);
  await page
    .getByRole("alert")
    .getByText("确认已记录", { exact: false })
    .waitFor({ timeout: 15000 });
  console.log("S6_BROWSER_STAGE:confirmed");
  assert.match(await page.locator(".detail-section").innerText(), /CONFIRMED/);
  assert.match(await page.locator(".order-section").innerText(), /S6 Synthetic Test Package/);
  assert.match(await page.locator(".order-section").innerText(), /JPY/);

  await lookup(rejectId);
  console.log("S6_BROWSER_STAGE:reject-loaded");
  assert.match(await page.locator(".detail-section").innerText(), /PENDING_CONFIRMATION/);
  await Promise.all([
    page.waitForEvent("dialog").then(async (dialog) => {
      assert.match(dialog.message(), /请核对后拒绝/);
      await dialog.accept();
    }),
    page.getByRole("button", { name: "拒绝申请" }).click(),
  ]);
  await page.getByRole("alert").first().waitFor({ timeout: 15000 });
  console.log(
    "S6_BROWSER_ALERT:" +
      (await page.getByRole("alert").first().innerText()).replace(/\s+/g, " ").slice(0, 200),
  );
  await page
    .getByRole("alert")
    .getByText("拒绝已记录", { exact: false })
    .waitFor({ timeout: 15000 });
  console.log("S6_BROWSER_STAGE:rejected");
  assert.match(await page.locator(".detail-section").innerText(), /REJECTED/);
  const refusedOrderText =
    (await page.locator(".order-section").count()) === 0
      ? ""
      : await page.locator(".order-section").innerText();
  assert.doesNotMatch(refusedOrderText, /S6 Synthetic Test Package/);
  assert.equal(await page.locator(".confirm-block").count(), 0);

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