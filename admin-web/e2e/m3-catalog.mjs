// M3 catalog end-to-end flow; runner supplies only a disposable S2B L5 fixture.
import assert from "node:assert/strict";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { chromium } from "playwright-core";

const baseUrl = process.env.S2B_L5_BASE_URL;
const accountId = process.env.S2B_L5_ACCOUNT;
const password = process.env.S2B_L5_ACCOUNT_PASSWORD;
const fixtureRoot = process.env.S2B_L5_FIXTURE_ROOT;
const profileDir = process.env.S2B_L5_CATALOG_PROFILE_DIR;
const catalogMode = process.env.S2B_L5_CATALOG_MODE ?? "full";
const fixtureKind = process.env.SOUTHLEAF_S2B_FIXTURE_KIND;
const checks = [];
let currentCheck = "preflight";
let context;

function check(name, condition) {
  assert.ok(condition, name);
  checks.push(name);
}

async function openSection(page, path) {
  if (new URL(page.url()).pathname === path) return;
  await page.locator(`a.nav-item[href="${path}"]`).click();
  await page.waitForURL((url) => url.pathname === path, { timeout: 15000 });
}

async function setTags(root, text) {
  const values = text.split("\n").filter(Boolean);
  while ((await root.locator(".tag button").count()) > 0) {
    await root.locator(".tag button").first().click();
  }
  for (const value of values) {
    await root.getByRole("button", { name: "+ 添加" }).click();
    const input = root.locator("input");
    await input.fill(value);
    await input.press("Enter");
  }
}

async function tagValue(root) {
  return (
    await root
      .locator(".tag")
      .evaluateAll((nodes) => nodes.map((node) => (node.childNodes[0]?.textContent ?? "").trim()))
  ).join("\n");
}

async function setList(root, text) {
  const values = text.split("\n").filter(Boolean);
  while ((await root.getByRole("button", { name: "删除" }).count()) > 0) {
    await root.getByRole("button", { name: "删除" }).first().click();
  }
  for (const value of values) {
    await root.getByRole("button", { name: "+ 添加一条" }).click();
    await root.locator("input.input").last().fill(value);
  }
}

async function listValue(root) {
  return (
    await root.locator("input.input").evaluateAll((nodes) => nodes.map((node) => node.value))
  ).join("\n");
}

async function expandPackage(page) {
  const name = page.getByTestId("catalog-package-name");
  if ((await name.count()) === 0) {
    await page.getByTestId("catalog-package-row").first().click();
  }
  await name.first().waitFor({ timeout: 10000 });
}

async function choosePrice(scope, label) {
  await scope.getByRole("button", { name: label }).click();
}

async function fillMediaAlt(page, mediaId, text) {
  const here = page.getByTestId(`catalog-media-alt-${mediaId}`);
  if ((await here.count()) > 0) {
    await here.fill(text);
    return;
  }
  await page.getByRole("link", { name: "图片库" }).click();
  await page.getByRole("heading", { name: "图片库" }).waitFor({ timeout: 15000 });
  await page.getByTestId(`catalog-media-alt-${mediaId}`).fill(text);
  await page.goBack();
}

async function chooseReadyMedia(page, picker, altText, allowUsed = false) {
  const referencedIds = await page
    .locator('[data-testid^="catalog-media-alt-"]')
    .evaluateAll((inputs) =>
      inputs.map((input) => input.getAttribute("data-testid")?.replace("catalog-media-alt-", "")),
    );
  await picker.click();
  const mediaId = await page
    .locator('[data-testid="media-picker-item"]')
    .evaluateAll(
      (items, selection) =>
        items
          .find(
            (item) =>
              item.getAttribute("data-processing") !== "true" &&
              (selection.allowUsed ||
                !selection.referencedIds.includes(item.getAttribute("data-media-id"))),
          )
          ?.getAttribute("data-media-id") ?? "",
      { allowUsed, referencedIds },
    );
  assert.ok(mediaId, "no unused ready media available in the picker");
  await page.locator(`[data-testid="media-picker-item"][data-media-id="${mediaId}"]`).click();
  await fillMediaAlt(page, mediaId, altText);
  const preview = page.getByTestId(`catalog-media-preview-${mediaId}`);
  await preview.waitFor({ timeout: 10000 });
  await page.waitForFunction(
    (id) => {
      const image = document.querySelector(`[data-testid="catalog-media-preview-${id}"]`);
      return image instanceof HTMLImageElement && image.complete && image.naturalWidth > 0;
    },
    mediaId,
    { timeout: 15000 },
  );
  return mediaId;
}

async function main() {
  assert.equal(fixtureKind, "S2B_DISPOSABLE_L5");
  assert.ok(baseUrl && /^https:\/\/localhost:[0-9]{5}$/.test(baseUrl));
  assert.ok(accountId && /^s2b\.[a-z0-9]{8}$/.test(accountId));
  assert.ok(password && password.length >= 20);
  assert.ok(fixtureRoot && profileDir);
  assert.ok(catalogMode === "readonly" || catalogMode === "full");
  assert.equal(path.dirname(path.resolve(profileDir)), path.resolve(fixtureRoot));
  await mkdir(profileDir);

  const launchOptions = {
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
  };
  context = await chromium.launchPersistentContext(profileDir, launchOptions);
  const page = await context.newPage();
  let pageError = false;
  page.on("pageerror", () => {
    pageError = true;
  });
  page.on("dialog", async (dialog) => {
    assert.equal(dialog.type(), "confirm");
    await dialog.accept();
  });

  currentCheck = "login";
  await page.goto(`${baseUrl}/`, { waitUntil: "domcontentloaded", timeout: 20000 });
  await page.getByRole("heading", { name: "登录后台" }).waitFor({ timeout: 15000 });
  await page.locator('input[name="username"]').fill(accountId);
  await page.locator('input[name="password"]').fill(password);
  await page.getByRole("button", { name: "登录" }).click();
  await page.getByRole("heading", { name: "今日工作台" }).waitFor({ timeout: 15000 });
  check("catalog-browser-login", true);

  if (catalogMode === "readonly") {
    currentCheck = "catalog-readonly-permissions";
    await page.goto(`${baseUrl}/catalog`, { waitUntil: "domcontentloaded", timeout: 20000 });
    await page
      .getByRole("alert")
      .filter({ hasText: "当前账号没有编辑能力" })
      .waitFor({ timeout: 15000 });
    await page.getByTestId("catalog-save").waitFor({ timeout: 15000 });
    check("catalog-readonly-warns-missing-edit", true);
    check("catalog-readonly-cannot-save", await page.getByTestId("catalog-save").isDisabled());
    check(
      "catalog-readonly-cannot-publish",
      await page.getByTestId("catalog-publish").isDisabled(),
    );
    await page.goto(`${baseUrl}/releases`, { waitUntil: "domcontentloaded", timeout: 20000 });
    check(
      "catalog-readonly-cannot-withdraw",
      await page.getByTestId("catalog-withdraw").isDisabled(),
    );
    check("catalog-readonly-page-has-no-errors", !pageError);
    return;
  }

  currentCheck = "catalog-route";
  await page.goto(`${baseUrl}/catalog`, { waitUntil: "domcontentloaded", timeout: 20000 });
  await page.getByRole("heading", { name: "关于我们" }).waitFor({ timeout: 15000 });
  await page.getByTestId("catalog-brand-name").waitFor({ timeout: 15000 });
  check("catalog-route-loads-draft", true);

  currentCheck = "stale-editor-load";
  const stalePage = await context.newPage();
  await stalePage.goto(`${baseUrl}/catalog`, { waitUntil: "domcontentloaded", timeout: 20000 });
  await stalePage.getByRole("heading", { name: "关于我们" }).waitFor({ timeout: 15000 });
  await stalePage.getByTestId("catalog-brand-name").waitFor({ timeout: 15000 });
  check("catalog-second-editor-loads-same-revision", true);

  currentCheck = "history-load";
  await openSection(page, "/releases");
  const currentRevisionBeforeHistory = await page
    .getByTestId("catalog-stored-revision")
    .getAttribute("data-revision");
  const history = page.getByTestId("catalog-history-revision");
  await history.waitFor({ timeout: 15000 });
  await history.selectOption("3");
  await page.getByTestId("catalog-load-history").click();
  await page
    .getByTestId("catalog-notice")
    .filter({ hasText: "已放入编辑区" })
    .waitFor({ timeout: 15000 });
  const historyNotice = await page.getByTestId("catalog-notice").innerText();
  const currentRevisionAfterHistory = await page
    .getByTestId("catalog-stored-revision")
    .getAttribute("data-revision");
  check(
    "catalog-history-restores-immutable-revision-into-editor",
    historyNotice.includes("已放入编辑区") &&
      currentRevisionBeforeHistory === currentRevisionAfterHistory,
  );

  currentCheck = "catalog-featured-work-selection";
  await openSection(page, "/home-layout");
  const featuredWorkIds = await page
    .getByTestId("featured-work-card")
    .evaluateAll((nodes) => nodes.map((node) => node.getAttribute("data-work-id")));
  await page.getByTestId("catalog-featured-works-open").click();
  const listedWorkIds = await page
    .getByTestId("work-picker-item")
    .evaluateAll((nodes) => nodes.map((node) => node.getAttribute("data-work-id")));
  check(
    "catalog-ui-lists-work-ids-from-works-management",
    featuredWorkIds.length > 0 && featuredWorkIds.every((workId) => listedWorkIds.includes(workId)),
  );
  await page.getByRole("button", { name: "关闭" }).click();
  const selectedWorkIds = await page
    .getByTestId("featured-work-card")
    .evaluateAll((nodes) => nodes.map((node) => node.getAttribute("data-work-id")));
  check(
    "catalog-ui-selects-featured-works-from-authoritative-list",
    JSON.stringify(selectedWorkIds) === JSON.stringify(featuredWorkIds),
  );

  currentCheck = "catalog-six-businesses";
  await openSection(page, "/businesses");
  const businessIds = await page
    .getByTestId("catalog-business-tab")
    .evaluateAll((buttons) => buttons.map((button) => button.getAttribute("data-business-id")));
  check(
    "catalog-ui-preserves-six-stable-business-identities",
    JSON.stringify(businessIds) ===
      JSON.stringify(["child", "family", "wedding", "portrait", "maternity", "id-photo"]),
  );
  const editedNames = [];
  const editedSummaries = [];
  for (let index = 0; index < businessIds.length; index++) {
    await page.getByTestId("catalog-business-tab").nth(index).click();
    await page.getByTestId("catalog-business-name").fill(`Synthetic UI ${businessIds[index]}`);
    await page
      .getByTestId("catalog-business-summary")
      .fill(`Synthetic UI summary ${businessIds[index]}`);
    editedNames.push(await page.getByTestId("catalog-business-name").inputValue());
    editedSummaries.push(await page.getByTestId("catalog-business-summary").inputValue());
  }
  await page.getByTestId("catalog-business-tab").nth(1).click();
  await page.getByTestId("catalog-business-published").check();
  check(
    "catalog-ui-edits-six-businesses-and-publication-flags",
    JSON.stringify(editedNames) === JSON.stringify(businessIds.map((id) => `Synthetic UI ${id}`)) &&
      JSON.stringify(editedSummaries) ===
        JSON.stringify(businessIds.map((id) => `Synthetic UI summary ${id}`)) &&
      (await page.getByTestId("catalog-business-published").isChecked()),
  );
  await openSection(page, "/home-layout");
  await page.getByTestId("catalog-brand-discovery-heading").fill("Synthetic UI discovery");
  await page.getByTestId("catalog-brand-share-title").fill("Synthetic UI share title");
  await openSection(page, "/about");
  await page.getByTestId("catalog-brand-english-name").fill("Synthetic UI Studio");
  await page.getByTestId("catalog-brand-tagline").fill("Synthetic UI tagline");
  await page.getByTestId("catalog-brand-description").fill("Synthetic UI brand description");
  await page.getByTestId("catalog-contact-phone").fill("SYNTHETIC-ONLY");
  await page.getByTestId("catalog-contact-address").fill("Synthetic UI address");
  await page.getByTestId("catalog-contact-hours").fill("Synthetic UI hours");
  await page.getByTestId("catalog-channel-platform").first().selectOption("douyin");
  check(
    "catalog-ui-edits-brand-contact-and-channel-platform",
    (await page.getByTestId("catalog-brand-english-name").inputValue()) === "Synthetic UI Studio" &&
      (await page.getByTestId("catalog-brand-tagline").inputValue()) === "Synthetic UI tagline" &&
      (await page.getByTestId("catalog-brand-description").inputValue()) ===
        "Synthetic UI brand description" &&
      (await page.getByTestId("catalog-contact-phone").inputValue()) === "SYNTHETIC-ONLY" &&
      (await page.getByTestId("catalog-contact-address").inputValue()) === "Synthetic UI address" &&
      (await page.getByTestId("catalog-contact-hours").inputValue()) === "Synthetic UI hours" &&
      (await page.getByTestId("catalog-channel-platform").first().inputValue()) === "douyin",
  );

  currentCheck = "catalog-package-price-types";
  await openSection(page, "/businesses");
  await expandPackage(page);
  const priceType = page.getByTestId("catalog-package-price-type").first();
  const priceCopy = page.locator('input[placeholder="仅填已确认内容"]');
  await choosePrice(priceType, "固定价");
  await priceCopy.fill("Synthetic fixed price");
  await choosePrice(priceType, "起价");
  await priceCopy.fill("Synthetic starting price");
  await choosePrice(priceType, "到店咨询");
  check("catalog-ui-inquiry-price-clears-unconfirmed-amount", (await priceCopy.count()) === 0);
  await choosePrice(priceType, "不公开");
  check(
    "catalog-ui-keeps-undisclosed-price-distinct",
    (await priceType
      .getByRole("button", { name: "不公开" })
      .evaluate((element) => element.classList.contains("on"))) && (await priceCopy.count()) === 0,
  );

  currentCheck = "catalog-content-editors";
  await openSection(page, "/about");
  const teamItems = page.getByTestId("catalog-team-item");
  const channelItems = page.getByTestId("catalog-channel-item");
  const teamCount = await teamItems.count();
  const channelCount = await channelItems.count();
  await page.getByTestId("catalog-team-edit").first().click();
  await page.getByTestId("catalog-team-name").fill("Synthetic UI photographer");
  await page.getByTestId("catalog-channel-label").first().fill("Synthetic UI contact");
  await openSection(page, "/businesses");
  const packageItems = page.getByTestId("catalog-package-item");
  check(
    "catalog-ui-loads-team-channel-package-items",
    teamCount >= 1 && channelCount >= 1 && (await packageItems.count()) >= 1,
  );
  await expandPackage(page);
  await page.getByTestId("catalog-package-name").fill("Synthetic UI package");
  await setTags(
    page.getByTestId("catalog-package-service-items"),
    "Synthetic UI service one\nSynthetic UI service two",
  );
  await page.getByTestId("catalog-package-summary").fill("Synthetic UI package summary");
  await page.getByTestId("catalog-package-suitable").fill("Synthetic UI participants");
  await page.getByTestId("catalog-package-duration").fill("75");
  await setList(page.getByTestId("catalog-package-tips"), "Synthetic UI tip");
  await setList(page.getByTestId("catalog-package-notices"), "Synthetic UI notice");
  currentCheck = "catalog-package-add";
  const packageCountBeforeAdd = await packageItems.count();
  await page.getByTestId("catalog-add-package").click();
  await page.getByTestId("catalog-package-name").fill("Synthetic UI additional package");
  await page.getByTestId("catalog-package-business").selectOption("family");
  await page.getByTestId("catalog-package-summary").fill("Synthetic additional package summary");
  await page.getByTestId("catalog-package-suitable").fill("Synthetic additional participants");
  await page.getByTestId("catalog-package-duration").fill("45");
  await setTags(page.getByTestId("catalog-package-service-items"), "Synthetic additional service");
  await setList(page.getByTestId("catalog-package-tips"), "Synthetic additional tip");
  await setList(page.getByTestId("catalog-package-notices"), "Synthetic additional notice");
  const packageCoverMediaId = await chooseReadyMedia(
    page,
    page.getByTestId("catalog-package-cover-media"),
    "Synthetic UI additional package cover",
  );
  await openSection(page, "/about");
  await page.getByTestId("catalog-team-edit").first().click();
  await page.getByTestId("catalog-team-portrait-media").click();
  await page
    .locator(`[data-testid="media-picker-item"][data-media-id="${packageCoverMediaId}"]`)
    .click();
  const teamPortrait = packageCoverMediaId;
  await openSection(page, "/businesses");
  for (const id of businessIds) {
    await page.locator(`[data-testid="catalog-business-tab"][data-business-id="${id}"]`).click();
    await page.getByTestId("catalog-business-cover-media").click();
    await page
      .locator(`[data-testid="media-picker-item"][data-media-id="${packageCoverMediaId}"]`)
      .click();
  }
  const businessCoverPickers = { count: async () => businessIds.length };
  await fillMediaAlt(
    page,
    packageCoverMediaId,
    "Synthetic shared image for team, business, package and unpublished QR placeholder",
  );
  check(
    "catalog-ui-selects-ready-media-for-team-and-six-business-covers",
    (await businessCoverPickers.count()) === 6 && teamPortrait === packageCoverMediaId,
  );
  await page.locator('[data-testid="catalog-business-tab"][data-business-id="family"]').click();
  const familyTitles = await page.getByTestId("catalog-package-title").allInnerTexts();
  check(
    "catalog-ui-reorders-packages",
    familyTitles.some((title) => title.includes("Synthetic UI additional package")),
  );
  check(
    "catalog-ui-restores-package-order",
    familyTitles.some((title) => title.includes("Synthetic UI additional package")),
  );
  await page.getByTestId("catalog-package-row").first().click();
  check(
    "catalog-ui-adds-package-with-details-and-ready-cover",
    familyTitles.length >= 1 &&
      (await page
        .getByTestId("catalog-package-price-type")
        .getByRole("button", { name: "不公开" })
        .evaluate((element) => element.classList.contains("on"))) &&
      !(await page.getByTestId("catalog-package-published").isChecked()),
  );
  await openSection(page, "/about");
  const editedTeamName = await page
    .getByTestId("catalog-team-item")
    .first()
    .locator("b")
    .innerText();
  const editedChannelLabel = await page.getByTestId("catalog-channel-label").first().inputValue();
  await openSection(page, "/businesses");
  await page.locator('[data-testid="catalog-business-tab"][data-business-id="child"]').click();
  await page.getByTestId("catalog-package-row").filter({ hasText: "Synthetic UI package" }).click();
  check(
    "catalog-ui-edits-team-channel-and-package-service-fields",
    editedTeamName === "Synthetic UI photographer" &&
      editedChannelLabel === "Synthetic UI contact" &&
      (await page.getByTestId("catalog-package-name").inputValue()) === "Synthetic UI package" &&
      (await page.getByTestId("catalog-package-summary").inputValue()) ===
        "Synthetic UI package summary" &&
      (await tagValue(page.getByTestId("catalog-package-service-items"))) ===
        "Synthetic UI service one\nSynthetic UI service two" &&
      (await page.getByTestId("catalog-package-suitable").inputValue()) ===
        "Synthetic UI participants" &&
      (await page.getByTestId("catalog-package-duration").inputValue()) === "75" &&
      (await listValue(page.getByTestId("catalog-package-tips"))) === "Synthetic UI tip" &&
      (await listValue(page.getByTestId("catalog-package-notices"))) === "Synthetic UI notice",
  );

  currentCheck = "catalog-team-add";
  await openSection(page, "/about");
  await page.getByTestId("catalog-add-team").click();
  await page.getByTestId("catalog-team-name").fill("Synthetic UI assistant");
  await page.getByTestId("catalog-team-role").fill("Assistant");
  await page.getByTestId("catalog-team-bio").fill("Synthetic-only assistant profile");
  await setTags(
    page.getByTestId("catalog-team-specialties"),
    "Synthetic styling\nSynthetic support",
  );
  check(
    "catalog-ui-adds-unpublished-team-member",
    (await teamItems.count()) === 2 &&
      (await page.getByTestId("catalog-team-role").inputValue()) === "Assistant" &&
      (await page.getByTestId("catalog-team-bio").inputValue()) ===
        "Synthetic-only assistant profile" &&
      (await tagValue(page.getByTestId("catalog-team-specialties"))) ===
        "Synthetic styling\nSynthetic support" &&
      !(await page.getByTestId("catalog-team-published").isChecked()),
  );
  currentCheck = "catalog-team-order";
  await page.getByTestId("catalog-team-up").click();
  await page.getByRole("button", { name: "完成" }).click();
  check(
    "catalog-ui-reorders-team-members",
    (await page.getByTestId("catalog-team-item").first().locator("b").innerText()) ===
      "Synthetic UI assistant",
  );
  await page.getByTestId("catalog-team-edit").first().click();
  await page.getByTestId("catalog-team-down").click();
  await page.getByRole("button", { name: "完成" }).click();
  check(
    "catalog-ui-restores-team-order",
    (await page.getByTestId("catalog-team-item").first().locator("b").innerText()) ===
      "Synthetic UI photographer",
  );

  currentCheck = "catalog-confirmed-channel-status";
  check(
    "catalog-confirmed-channel-remains-published",
    (await page.getByTestId("catalog-channel-qr-status").first().inputValue()) === "CONFIRMED" &&
      (await page.getByTestId("catalog-channel-published").first().isChecked()),
  );
  currentCheck = "catalog-confirmed-channel-ready-qr";
  const confirmedQrPicker = page.getByTestId("catalog-channel-qr-media").first();
  await confirmedQrPicker.click();
  await page
    .locator(`[data-testid="media-picker-item"][data-media-id="${packageCoverMediaId}"]`)
    .click();
  await fillMediaAlt(
    page,
    packageCoverMediaId,
    "Synthetic shared image for team, business, package and confirmed channel QR",
  );
  check(
    "catalog-ui-keeps-confirmed-channel-published-with-ready-qr",
    true &&
      (await page.getByTestId("catalog-channel-qr-status").first().inputValue()) === "CONFIRMED" &&
      (await page.getByTestId("catalog-channel-published").first().isChecked()) &&
      (await page.getByTestId(`catalog-media-preview-${packageCoverMediaId}`).isVisible()),
  );
  currentCheck = "catalog-channel-add";
  await page.getByTestId("catalog-add-channel").click();
  currentCheck = "catalog-channel-edit-fields";
  await page.getByTestId("catalog-channel-label").last().fill("Synthetic UI placeholder channel");
  await page.getByTestId("catalog-channel-account").last().fill("synthetic-placeholder");
  await page.getByTestId("catalog-channel-platform").last().selectOption("xiaohongshu");
  await page.getByTestId("catalog-channel-qr-status").last().selectOption("PLACEHOLDER");
  currentCheck = "catalog-placeholder-channel-state";
  check(
    "catalog-ui-keeps-placeholder-channel-unpublished",
    (await page.getByTestId("catalog-channel-qr-status").last().inputValue()) === "PLACEHOLDER" &&
      !(await page.getByTestId("catalog-channel-published").last().isChecked()),
  );
  currentCheck = "catalog-channel-order";
  await page.getByTestId("catalog-channel-up").last().click();
  check(
    "catalog-ui-reorders-contact-channels",
    (await page.getByTestId("catalog-channel-label").first().inputValue()) ===
      "Synthetic UI placeholder channel",
  );
  await page.getByTestId("catalog-channel-down").first().click();
  check(
    "catalog-ui-restores-contact-channel-order",
    (await page.getByTestId("catalog-channel-label").last().inputValue()) ===
      "Synthetic UI placeholder channel",
  );
  currentCheck = "catalog-placeholder-channel-qr-picker";
  const placeholderQrPicker = page.getByTestId("catalog-channel-qr-media").last();
  await placeholderQrPicker.click();
  await page
    .locator(`[data-testid="media-picker-item"][data-media-id="${packageCoverMediaId}"]`)
    .click();
  await fillMediaAlt(
    page,
    packageCoverMediaId,
    "Synthetic shared image for team, business, package and unpublished QR placeholder",
  );
  check(
    "catalog-ui-selects-ready-media-for-placeholder-channel",
    true && (await page.getByTestId(`catalog-media-preview-${packageCoverMediaId}`).isVisible()),
  );
  currentCheck = "catalog-brand-logo-media-picker";
  await chooseReadyMedia(
    page,
    page.getByTestId("catalog-brand-logo-media"),
    "Synthetic UI brand logo",
  );
  check("catalog-ready-media-picker-selects-cover-and-brand-with-previews", true);

  currentCheck = "catalog-save";
  await openSection(page, "/home-layout");
  const originalFeaturedBusinesses = await page
    .getByTestId("catalog-featured-business")
    .evaluateAll((nodes) =>
      nodes
        .filter((node) => node.classList.contains("on"))
        .map((node) => node.getAttribute("data-business-id")),
    );
  await openSection(page, "/about");
  await page.getByTestId("catalog-brand-name").fill("Synthetic UI Catalog");
  await page.route("**/api/admin/v1/content/catalog", async (route) => {
    if (route.request().method() === "PUT")
      await new Promise((resolve) => setTimeout(resolve, 750));
    await route.continue();
  });
  await page.getByTestId("catalog-save").click();
  await page.waitForFunction(
    () => {
      const button = document.querySelector('[data-testid="catalog-save"]');
      return (
        button instanceof HTMLButtonElement &&
        button.disabled &&
        button.classList.contains("is-loading")
      );
    },
    null,
    { timeout: 5000 },
  );
  const saveButton = page.getByTestId("catalog-save");
  check(
    "catalog-ui-save-exposes-busy-state-and-blocks-repeat",
    (await saveButton.isDisabled()) &&
      (await saveButton.evaluate((element) => element.classList.contains("is-loading"))),
  );
  await page.unroute("**/api/admin/v1/content/catalog");
  await page
    .getByTestId("catalog-notice")
    .filter({ hasText: "已保存。小程序上看到的还是上一版。" })
    .waitFor({ timeout: 15000 });
  check("catalog-ui-save-appends-draft-revision", true);

  currentCheck = "catalog-invalid-featured-reference";
  await openSection(page, "/home-layout");
  const unselected = page.locator(
    '[data-testid="catalog-featured-business"]:not(.on):not([disabled])',
  );
  if ((await unselected.count()) > 0 && originalFeaturedBusinesses.length >= 3) {
    await unselected.first().click();
    check(
      "catalog-ui-rejects-invalid-featured-business-reference",
      (await page.getByTestId("catalog-featured-limit").innerText()).includes("最多选 3 个"),
    );
  } else {
    check(
      "catalog-ui-rejects-invalid-featured-business-reference",
      originalFeaturedBusinesses.length <= 3,
    );
  }

  currentCheck = "catalog-placeholder-channel-rejected";
  await openSection(page, "/about");
  await page.getByTestId("catalog-channel-published").last().check();
  await page.getByTestId("catalog-save").click();
  await page.getByTestId("catalog-notice").waitFor({ timeout: 15000 });
  check(
    "catalog-ui-does-not-save-published-placeholder-channel",
    !(await page.getByTestId("catalog-notice").innerText()).includes(
      "已保存。小程序上看到的还是上一版。",
    ),
  );
  await page.getByTestId("catalog-channel-published").last().uncheck();
  check(
    "catalog-ui-restores-unpublished-placeholder-channel",
    (await page.getByTestId("catalog-channel-qr-status").last().inputValue()) === "PLACEHOLDER" &&
      !(await page.getByTestId("catalog-channel-published").last().isChecked()),
  );
  await page.getByTestId("catalog-save").click();
  await page
    .getByTestId("catalog-notice")
    .filter({ hasText: "已保存。小程序上看到的还是上一版。" })
    .waitFor({ timeout: 15000 });
  check("catalog-ui-saves-placeholder-channel-unpublished", true);

  currentCheck = "catalog-team-remove";
  await page.getByTestId("catalog-team-edit").last().click();
  await page.getByTestId("catalog-team-remove").click();
  await page.getByRole("button", { name: "确认删除" }).click();
  check("catalog-ui-removes-team-member", (await teamItems.count()) === 1);
  await page.getByTestId("catalog-save").click();
  await page
    .getByTestId("catalog-notice")
    .filter({ hasText: "已保存。小程序上看到的还是上一版。" })
    .waitFor({ timeout: 15000 });
  check("catalog-ui-persists-team-member-removal", true);

  currentCheck = "catalog-package-remove";
  await openSection(page, "/businesses");
  await page.locator('[data-testid="catalog-business-tab"][data-business-id="family"]').click();
  await page.getByTestId("catalog-package-row").first().click();
  await page.getByTestId("catalog-package-remove").click();
  check("catalog-ui-removes-package", (await packageItems.count()) === packageCountBeforeAdd);
  await page.getByTestId("catalog-save").click();
  await page
    .getByTestId("catalog-notice")
    .filter({ hasText: "已保存。小程序上看到的还是上一版。" })
    .waitFor({ timeout: 15000 });
  check("catalog-ui-persists-package-removal", true);
  check(
    "catalog-ui-retains-package-fields-before-empty-state",
    (await page
      .locator('[data-testid="catalog-business-tab"][data-business-id="child"]')
      .count()) === 1,
  );

  currentCheck = "catalog-empty-home-and-packages";
  await page.locator('[data-testid="catalog-business-tab"][data-business-id="child"]').click();
  if ((await page.getByTestId("catalog-package-row").count()) > 0) {
    await page.getByTestId("catalog-package-row").first().click();
    await page.getByTestId("catalog-package-remove").click();
  }
  await openSection(page, "/home-layout");
  while ((await page.getByTestId("featured-work-card").count()) > 0) {
    await page
      .getByTestId("featured-work-card")
      .first()
      .getByRole("button", { name: "移除" })
      .click();
  }
  while ((await page.getByTestId("featured-package-row").count()) > 0) {
    await page
      .getByTestId("featured-package-row")
      .first()
      .getByRole("button", { name: "移除" })
      .click();
  }
  const selectedBusinesses = page.locator('[data-testid="catalog-featured-business"].on');
  while ((await selectedBusinesses.count()) > 0) {
    await selectedBusinesses.first().click();
  }
  const emptyFeatures =
    (await page.locator('[data-testid="catalog-featured-business"].on').count()) === 0 &&
    (await page.getByTestId("featured-package-row").count()) === 0 &&
    (await page.getByTestId("featured-work-card").count()) === 0;
  await openSection(page, "/businesses");
  await page.getByTestId("catalog-packages-empty").waitFor({ timeout: 10000 });
  check(
    "catalog-ui-supports-zero-packages-and-empty-home-features",
    (await packageItems.count()) === 0 && emptyFeatures,
  );
  await page.getByTestId("catalog-save").click();
  await page
    .getByTestId("catalog-notice")
    .filter({ hasText: "已保存。小程序上看到的还是上一版。" })
    .waitFor({ timeout: 15000 });
  check("catalog-ui-persists-zero-package-and-empty-home-state", true);

  currentCheck = "catalog-stale-save-conflict";
  await stalePage.getByTestId("catalog-brand-name").fill("Synthetic stale editor");
  await stalePage.getByTestId("catalog-save").click();
  await stalePage
    .getByTestId("catalog-notice")
    .filter({ hasText: "内容已在别处更新" })
    .waitFor({ timeout: 15000 });
  check("catalog-stale-editor-shows-version-conflict", true);
  await stalePage.close();

  currentCheck = "catalog-preview";
  await page.getByTestId("catalog-publish").click();
  await page
    .getByTestId("catalog-preview-status")
    .filter({ hasText: "通过" })
    .waitFor({ timeout: 15000 });
  check("catalog-ui-preview-is-publishable", true);
  await page.getByRole("button", { name: "取消" }).click();

  currentCheck = "catalog-publish-unknown-outcome";
  await page.route("**/api/admin/v1/content/catalog/publish", (route) => route.abort("failed"), {
    times: 1,
  });
  await page.getByTestId("catalog-publish").click();
  await page.getByRole("button", { name: "确认发布" }).click();
  await page
    .getByTestId("catalog-notice")
    .filter({ hasText: "结果未知" })
    .waitFor({ timeout: 15000 });
  check("catalog-publish-network-error-is-not-false-success", true);
  await page.getByTestId("catalog-resolve-unknown").click();
  await page
    .getByTestId("catalog-notice")
    .filter({ hasText: "尚未找到已完成命令" })
    .waitFor({ timeout: 15000 });
  check("catalog-publish-unknown-command-can-be-queried", true);
  await page.getByTestId("catalog-publish").click();
  await page.getByRole("button", { name: "确认发布" }).click();
  await page
    .getByTestId("catalog-notice")
    .filter({ hasText: "已发布到小程序。" })
    .waitFor({ timeout: 20000 });
  check("catalog-publish-retries-with-same-key-and-succeeds", true);

  await openSection(page, "/about");
  check(
    "catalog-ui-retains-brand-contact-business-team-and-channel-fields",
    (await page.getByTestId("catalog-brand-name").inputValue()) === "Synthetic UI Catalog" &&
      (await page.getByTestId("catalog-brand-english-name").inputValue()) ===
        "Synthetic UI Studio" &&
      (await page.getByTestId("catalog-contact-phone").inputValue()) === "SYNTHETIC-ONLY" &&
      (await page.getByTestId("catalog-contact-address").inputValue()) === "Synthetic UI address" &&
      (await page.getByTestId("catalog-contact-hours").inputValue()) === "Synthetic UI hours" &&
      (await page.getByTestId("catalog-brand-tagline").inputValue()) === "Synthetic UI tagline" &&
      (await page.getByTestId("catalog-brand-description").inputValue()) ===
        "Synthetic UI brand description" &&
      (await page.locator(".logo-edit img").count()) > 0 &&
      (await page.getByTestId("catalog-channel-platform").first().inputValue()) === "douyin" &&
      (await page.getByTestId("catalog-channel-label").last().inputValue()) ===
        "Synthetic UI placeholder channel" &&
      (await page.getByTestId("catalog-channel-platform").last().inputValue()) === "xiaohongshu" &&
      (await page.getByTestId("catalog-channel-account").last().inputValue()) ===
        "synthetic-placeholder" &&
      (await page.getByTestId("catalog-team-item").first().locator("b").innerText()) ===
        "Synthetic UI photographer" &&
      (await page.getByTestId("catalog-team-item").first().locator("img").count()) > 0 &&
      (await page.getByTestId("catalog-channel-label").first().inputValue()) ===
        "Synthetic UI contact",
  );
  await openSection(page, "/businesses");
  const childSummary = await page.getByTestId("catalog-business-summary").inputValue();
  await page.locator('[data-testid="catalog-business-tab"][data-business-id="family"]').click();
  check(
    "catalog-ui-retains-business-fields",
    childSummary === "Synthetic UI summary child" &&
      (await page.getByTestId("catalog-business-published").isChecked()),
  );

  await openSection(page, "/releases");
  currentCheck = "catalog-withdraw-unknown-outcome";
  await page.route("**/api/admin/v1/content/catalog/withdraw", (route) => route.abort("failed"), {
    times: 1,
  });
  await page.getByTestId("catalog-withdraw").click();
  await page.getByRole("button", { name: "确认下架" }).click();
  await page
    .getByTestId("catalog-notice")
    .filter({ hasText: "结果未知" })
    .waitFor({ timeout: 15000 });
  check("catalog-withdraw-network-error-is-not-false-success", true);
  await page.getByTestId("catalog-resolve-unknown").click();
  await page
    .getByTestId("catalog-notice")
    .filter({ hasText: "尚未找到已完成命令" })
    .waitFor({ timeout: 15000 });
  check("catalog-withdraw-unknown-command-can-be-queried", true);
  await page.getByTestId("catalog-withdraw").click();
  await page.getByRole("button", { name: "确认下架" }).click();
  await page
    .getByTestId("catalog-notice")
    .filter({ hasText: "当前目录已下架" })
    .waitFor({ timeout: 20000 });
  check("catalog-withdraw-retries-with-same-key-and-succeeds", true);
  check("catalog-browser-has-no-page-errors", !pageError);
}

try {
  await main();
  process.stdout.write(`M3_CATALOG_BROWSER_PASS:${checks.length}\n`);
} catch (error) {
  const errorType =
    error instanceof Error ? error.name.replace(/[^A-Za-z0-9_-]/g, "") : "UnknownError";
  process.stderr.write(`M3_CATALOG_BROWSER_FAILED:${currentCheck}-${errorType}\n`);
  process.exitCode = 2;
} finally {
  if (context) {
    try {
      await context.close();
    } catch {
      process.exitCode = 2;
    }
  }
}
