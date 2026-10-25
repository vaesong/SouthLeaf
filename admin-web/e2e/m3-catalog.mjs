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

async function chooseReadyMedia(page, picker, altText, allowUsed = false) {
    const referencedIds = await page.locator('[data-testid^="catalog-media-alt-"]').evaluateAll((inputs) =>
        inputs.map((input) => input.getAttribute("data-testid")?.replace("catalog-media-alt-", "")),
    );
    const mediaId = await picker.locator("option").evaluateAll((options, selection) =>
        options.find((option) => option.value && !option.disabled && option.textContent?.includes("READY")
            && (selection.allowUsed || !selection.referencedIds.includes(option.value)))?.value ?? "",
        { allowUsed, referencedIds },
    );
    assert.ok(mediaId, "no unused READY media available in the picker");
    await picker.selectOption(mediaId);
    const alt = page.getByTestId(`catalog-media-alt-${mediaId}`);
    await alt.waitFor({ timeout: 10000 });
    await alt.fill(altText);
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
    page.on("pageerror", () => { pageError = true; });
    page.on("dialog", async (dialog) => {
        assert.equal(dialog.type(), "confirm");
        await dialog.accept();
    });

    currentCheck = "login";
    await page.goto(`${baseUrl}/`, { waitUntil: "domcontentloaded", timeout: 20000 });
    await page.getByRole("heading", { name: "管理入口" }).waitFor({ timeout: 15000 });
    await page.locator('input[name="username"]').fill(accountId);
    await page.locator('input[name="password"]').fill(password);
    await page.getByRole("button", { name: "安全登入" }).click();
    await page.getByRole("heading", { name: "已安全登入" }).waitFor({ timeout: 15000 });
    check("catalog-browser-login", true);

    if (catalogMode === "readonly") {
        currentCheck = "catalog-readonly-permissions";
        await page.goto(`${baseUrl}/catalog`, { waitUntil: "domcontentloaded", timeout: 20000 });
        await page.getByRole("alert").filter({ hasText: "当前账号没有编辑能力" }).waitFor({ timeout: 15000 });
        await page.getByTestId("catalog-save").waitFor({ timeout: 15000 });
        check("catalog-readonly-warns-missing-edit", true);
        check("catalog-readonly-cannot-save", await page.getByTestId("catalog-save").isDisabled());
        check("catalog-readonly-cannot-publish-or-withdraw",
            await page.getByTestId("catalog-publish").isDisabled()
            && await page.getByTestId("catalog-withdraw").isDisabled());
        check("catalog-readonly-page-has-no-errors", !pageError);
        return;
    }

    currentCheck = "catalog-route";
    await page.goto(`${baseUrl}/catalog`, { waitUntil: "domcontentloaded", timeout: 20000 });
    await page.getByRole("heading", { name: "品牌与目录" }).waitFor({ timeout: 15000 });
    await page.getByTestId("catalog-brand-name").waitFor({ timeout: 15000 });
    check("catalog-route-loads-draft", true);

    currentCheck = "stale-editor-load";
    const stalePage = await context.newPage();
    await stalePage.goto(`${baseUrl}/catalog`, { waitUntil: "domcontentloaded", timeout: 20000 });
    await stalePage.getByRole("heading", { name: "品牌与目录" }).waitFor({ timeout: 15000 });
    await stalePage.getByTestId("catalog-brand-name").waitFor({ timeout: 15000 });
    check("catalog-second-editor-loads-same-revision", true);

    currentCheck = "history-load";
    const currentRevisionBeforeHistory = await page.getByTestId("catalog-stored-revision").innerText();
    const history = page.getByTestId("catalog-history-revision");
    await history.waitFor({ timeout: 15000 });
    await history.selectOption("3");
    await page.getByTestId("catalog-load-history").click();
    await page.getByTestId("catalog-notice").filter({ hasText: "已载入历史修订 3" }).waitFor({ timeout: 15000 });
    const historyNotice = await page.getByTestId("catalog-notice").innerText();
    const currentRevisionAfterHistory = await page.getByTestId("catalog-stored-revision").innerText();
    check("catalog-history-restores-immutable-revision-into-editor",
        historyNotice.includes("当前服务器修订仍为") && currentRevisionBeforeHistory === currentRevisionAfterHistory
        && historyNotice.includes(`当前服务器修订仍为 ${currentRevisionBeforeHistory.replace("修订 ", "")}`));

    currentCheck = "catalog-featured-work-selection";
    const featuredWorkIds = (await page.getByTestId("catalog-featured-works").inputValue()).split("\n").filter(Boolean);
    const featuredWorkSelector = page.getByTestId("catalog-featured-works-list");
    const listedWorkIds = await featuredWorkSelector.locator("option").evaluateAll((options) => options.map((option) => option.value));
    check("catalog-ui-lists-work-ids-from-works-management",
        featuredWorkIds.length > 0 && featuredWorkIds.every((workId) => listedWorkIds.includes(workId)));
    await featuredWorkSelector.selectOption(featuredWorkIds);
    const selectedWorkIds = await featuredWorkSelector.evaluate((element) =>
        Array.from(element.selectedOptions, (option) => option.value));
    check("catalog-ui-selects-featured-works-from-authoritative-list",
        JSON.stringify(selectedWorkIds) === JSON.stringify(featuredWorkIds));

    currentCheck = "catalog-six-businesses";
    const businessIds = await page.getByTestId("catalog-business-id").evaluateAll((inputs) =>
        inputs.map((input) => input.value),
    );
    check("catalog-ui-preserves-six-stable-business-identities",
        JSON.stringify(businessIds) === JSON.stringify(["child", "family", "wedding", "portrait", "maternity", "id-photo"]));
    const businessNames = page.getByTestId("catalog-business-name");
    for (let index = 0; index < businessIds.length; index++) {
        await businessNames.nth(index).fill(`Synthetic UI ${businessIds[index]}`);
    }
    const businessSummaries = page.getByTestId("catalog-business-summary");
    for (let index = 0; index < businessIds.length; index++) {
        await businessSummaries.nth(index).fill(`Synthetic UI summary ${businessIds[index]}`);
    }
    const businessPublished = page.getByTestId("catalog-business-published");
    await businessPublished.nth(1).check();
    check("catalog-ui-edits-six-businesses-and-publication-flags",
        JSON.stringify(await businessNames.evaluateAll((inputs) => inputs.map((input) => input.value)))
        === JSON.stringify(businessIds.map((id) => `Synthetic UI ${id}`))
        && JSON.stringify(await businessSummaries.evaluateAll((inputs) => inputs.map((input) => input.value)))
        === JSON.stringify(businessIds.map((id) => `Synthetic UI summary ${id}`))
        && await businessPublished.nth(1).isChecked());
    await page.getByTestId("catalog-brand-english-name").fill("Synthetic UI Studio");
    await page.getByTestId("catalog-brand-discovery-heading").fill("Synthetic UI discovery");
    await page.getByTestId("catalog-brand-share-title").fill("Synthetic UI share title");
    await page.getByTestId("catalog-brand-tagline").fill("Synthetic UI tagline");
    await page.getByTestId("catalog-brand-description").fill("Synthetic UI brand description");
    await page.getByTestId("catalog-contact-phone").fill("SYNTHETIC-ONLY");
    await page.getByTestId("catalog-contact-address").fill("Synthetic UI address");
    await page.getByTestId("catalog-contact-hours").fill("Synthetic UI hours");
    await page.getByTestId("catalog-channel-platform").first().selectOption("douyin");
    check("catalog-ui-edits-brand-contact-and-channel-platform",
        await page.getByTestId("catalog-brand-english-name").inputValue() === "Synthetic UI Studio"
        && await page.getByTestId("catalog-brand-discovery-heading").inputValue() === "Synthetic UI discovery"
        && await page.getByTestId("catalog-brand-share-title").inputValue() === "Synthetic UI share title"
        && await page.getByTestId("catalog-brand-tagline").inputValue() === "Synthetic UI tagline"
        && await page.getByTestId("catalog-brand-description").inputValue() === "Synthetic UI brand description"
        && await page.getByTestId("catalog-contact-phone").inputValue() === "SYNTHETIC-ONLY"
        && await page.getByTestId("catalog-contact-address").inputValue() === "Synthetic UI address"
        && await page.getByTestId("catalog-contact-hours").inputValue() === "Synthetic UI hours"
        && await page.getByTestId("catalog-channel-platform").first().inputValue() === "douyin");

    currentCheck = "catalog-package-price-types";
    const priceType = page.getByTestId("catalog-package-price-type").first();
    const priceCopy = page.locator('input[placeholder="仅填已确认内容"]');
    await priceType.selectOption("FIXED");
    await priceCopy.fill("Synthetic fixed price");
    await priceType.selectOption("FROM");
    await priceCopy.fill("Synthetic starting price");
    await priceType.selectOption("INQUIRY");
    check("catalog-ui-inquiry-price-clears-unconfirmed-amount", (await priceCopy.count()) === 0);
    await priceType.selectOption("UNDISCLOSED");
    check("catalog-ui-keeps-undisclosed-price-distinct", (await priceType.inputValue()) === "UNDISCLOSED" && (await priceCopy.count()) === 0);

    currentCheck = "catalog-content-editors";
    const teamItems = page.getByTestId("catalog-team-item");
    const channelItems = page.getByTestId("catalog-channel-item");
    const packageItems = page.getByTestId("catalog-package-item");
    check("catalog-ui-loads-team-channel-package-items",
        (await teamItems.count()) >= 1 && (await channelItems.count()) >= 1 && (await packageItems.count()) >= 1);
    await page.getByTestId("catalog-team-name").first().fill("Synthetic UI photographer");
    await page.getByTestId("catalog-channel-label").first().fill("Synthetic UI contact");
    await page.getByTestId("catalog-package-name").first().fill("Synthetic UI package");
    await page.getByTestId("catalog-package-service-items").first().fill("Synthetic UI service one\nSynthetic UI service two");
    await page.getByTestId("catalog-package-summary").first().fill("Synthetic UI package summary");
    await page.getByTestId("catalog-package-suitable").first().fill("Synthetic UI participants");
    await page.getByTestId("catalog-package-duration").first().fill("75");
    await page.getByTestId("catalog-package-tips").first().fill("Synthetic UI tip");
    await page.getByTestId("catalog-package-notices").first().fill("Synthetic UI notice");
    currentCheck = "catalog-package-add";
    const packageCountBeforeAdd = await packageItems.count();
    await page.getByTestId("catalog-add-package").click();
    await page.getByTestId("catalog-package-name").last().fill("Synthetic UI additional package");
    await page.getByTestId("catalog-package-business").last().selectOption("family");
    await page.getByTestId("catalog-package-summary").last().fill("Synthetic additional package summary");
    await page.getByTestId("catalog-package-suitable").last().fill("Synthetic additional participants");
    await page.getByTestId("catalog-package-duration").last().fill("45");
    await page.getByTestId("catalog-package-service-items").last().fill("Synthetic additional service");
    await page.getByTestId("catalog-package-tips").last().fill("Synthetic additional tip");
    await page.getByTestId("catalog-package-notices").last().fill("Synthetic additional notice");
    const packageCoverMediaId = await chooseReadyMedia(page, page.getByTestId("catalog-package-cover-media").last(), "Synthetic UI additional package cover");
    await page.getByTestId("catalog-team-portrait-media").first().selectOption(packageCoverMediaId);
    const businessCoverPickers = page.getByTestId("catalog-business-cover-media");
    for (let index = 0; index < await businessCoverPickers.count(); index++) {
        await businessCoverPickers.nth(index).selectOption(packageCoverMediaId);
    }
    await page.getByTestId(`catalog-media-alt-${packageCoverMediaId}`).fill("Synthetic shared image for team, business, package and unpublished QR placeholder");
    check("catalog-ui-selects-ready-media-for-team-and-six-business-covers",
        (await businessCoverPickers.count()) === 6
        && await page.getByTestId("catalog-team-portrait-media").first().inputValue() === packageCoverMediaId);
    await page.getByTestId("catalog-package-up").last().click();
    check("catalog-ui-reorders-packages", await page.getByTestId("catalog-package-name").first().inputValue() === "Synthetic UI additional package");
    await page.getByTestId("catalog-package-down").first().click();
    check("catalog-ui-restores-package-order", await page.getByTestId("catalog-package-name").first().inputValue() === "Synthetic UI package");
    check("catalog-ui-adds-package-with-details-and-ready-cover",
        (await packageItems.count()) === packageCountBeforeAdd + 1
        && await page.getByTestId("catalog-package-price-type").last().inputValue() === "UNDISCLOSED"
        && !(await page.getByTestId("catalog-package-published").last().isChecked()));
    check("catalog-ui-edits-team-channel-and-package-service-fields",
        await page.getByTestId("catalog-team-name").first().inputValue() === "Synthetic UI photographer"
        && await page.getByTestId("catalog-channel-label").first().inputValue() === "Synthetic UI contact"
        && await page.getByTestId("catalog-package-name").first().inputValue() === "Synthetic UI package"
        && await page.getByTestId("catalog-package-summary").first().inputValue() === "Synthetic UI package summary"
        && await page.getByTestId("catalog-package-service-items").first().inputValue() === "Synthetic UI service one\nSynthetic UI service two"
        && await page.getByTestId("catalog-package-suitable").first().inputValue() === "Synthetic UI participants"
        && await page.getByTestId("catalog-package-duration").first().inputValue() === "75"
        && await page.getByTestId("catalog-package-tips").first().inputValue() === "Synthetic UI tip"
        && await page.getByTestId("catalog-package-notices").first().inputValue() === "Synthetic UI notice");

    currentCheck = "catalog-team-add";
    await page.getByTestId("catalog-add-team").click();
    await page.getByTestId("catalog-team-name").last().fill("Synthetic UI assistant");
    await page.getByTestId("catalog-team-role").last().fill("Assistant");
    await page.getByTestId("catalog-team-bio").last().fill("Synthetic-only assistant profile");
    await page.getByTestId("catalog-team-specialties").last().fill("Synthetic styling\nSynthetic support");
    check("catalog-ui-adds-unpublished-team-member", (await teamItems.count()) === 2
        && await page.getByTestId("catalog-team-role").last().inputValue() === "Assistant"
        && await page.getByTestId("catalog-team-bio").last().inputValue() === "Synthetic-only assistant profile"
        && await page.getByTestId("catalog-team-specialties").last().inputValue() === "Synthetic styling\nSynthetic support"
        && !(await page.getByTestId("catalog-team-published").last().isChecked()));
    currentCheck = "catalog-team-order";
    await page.getByTestId("catalog-team-up").last().click();
    check("catalog-ui-reorders-team-members", await page.getByTestId("catalog-team-name").first().inputValue() === "Synthetic UI assistant");
    await page.getByTestId("catalog-team-down").first().click();
    check("catalog-ui-restores-team-order", await page.getByTestId("catalog-team-name").first().inputValue() === "Synthetic UI photographer");

    currentCheck = "catalog-confirmed-channel-status";
    check("catalog-confirmed-channel-remains-published",
        await page.getByTestId("catalog-channel-qr-status").first().inputValue() === "CONFIRMED"
        && await page.getByTestId("catalog-channel-published").first().isChecked());
    currentCheck = "catalog-confirmed-channel-ready-qr";
    const confirmedQrPicker = page.getByTestId("catalog-channel-qr-media").first();
    await confirmedQrPicker.selectOption(packageCoverMediaId);
    await page.getByTestId(`catalog-media-alt-${packageCoverMediaId}`).fill("Synthetic shared image for team, business, package and confirmed channel QR");
    check("catalog-ui-keeps-confirmed-channel-published-with-ready-qr",
        await confirmedQrPicker.inputValue() === packageCoverMediaId
        && await page.getByTestId("catalog-channel-qr-status").first().inputValue() === "CONFIRMED"
        && await page.getByTestId("catalog-channel-published").first().isChecked()
        && await page.getByTestId(`catalog-media-preview-${packageCoverMediaId}`).isVisible());
    currentCheck = "catalog-channel-add";
    await page.getByTestId("catalog-add-channel").click();
    currentCheck = "catalog-channel-edit-fields";
    await page.getByTestId("catalog-channel-label").last().fill("Synthetic UI placeholder channel");
    await page.getByTestId("catalog-channel-account").last().fill("synthetic-placeholder");
    await page.getByTestId("catalog-channel-platform").last().selectOption("xiaohongshu");
    await page.getByTestId("catalog-channel-qr-status").last().selectOption("PLACEHOLDER");
    currentCheck = "catalog-placeholder-channel-state";
    check("catalog-ui-keeps-placeholder-channel-unpublished",
        await page.getByTestId("catalog-channel-qr-status").last().inputValue() === "PLACEHOLDER"
        && !(await page.getByTestId("catalog-channel-published").last().isChecked()));
    currentCheck = "catalog-channel-order";
    await page.getByTestId("catalog-channel-up").last().click();
    check("catalog-ui-reorders-contact-channels", await page.getByTestId("catalog-channel-label").first().inputValue() === "Synthetic UI placeholder channel");
    await page.getByTestId("catalog-channel-down").first().click();
    check("catalog-ui-restores-contact-channel-order", await page.getByTestId("catalog-channel-label").last().inputValue() === "Synthetic UI placeholder channel");
    currentCheck = "catalog-placeholder-channel-qr-picker";
    const placeholderQrPicker = page.getByTestId("catalog-channel-qr-media").last();
    await placeholderQrPicker.selectOption(packageCoverMediaId);
    await page.getByTestId(`catalog-media-alt-${packageCoverMediaId}`).fill("Synthetic shared image for team, business, package and unpublished QR placeholder");
    check("catalog-ui-selects-ready-media-for-placeholder-channel",
        await placeholderQrPicker.inputValue() === packageCoverMediaId
        && await page.getByTestId(`catalog-media-preview-${packageCoverMediaId}`).isVisible());
    currentCheck = "catalog-brand-logo-media-picker";
    await chooseReadyMedia(page, page.getByTestId("catalog-brand-logo-media"), "Synthetic UI brand logo");
    check("catalog-ready-media-picker-selects-cover-and-brand-with-previews", true);

    currentCheck = "catalog-save";
    const originalFeaturedBusinesses = await page.getByTestId("catalog-featured-businesses").inputValue();
    await page.getByTestId("catalog-brand-name").fill("Synthetic UI Catalog");
    await page.route("**/api/admin/v1/content/catalog", async (route) => {
        if (route.request().method() === "PUT") await new Promise((resolve) => setTimeout(resolve, 750));
        await route.continue();
    });
    await page.getByTestId("catalog-save").click();
    await page.waitForFunction(() => {
        const button = document.querySelector('[data-testid="catalog-save"]');
        return button instanceof HTMLButtonElement && button.disabled && button.classList.contains("is-loading");
    }, null, { timeout: 5000 });
    const saveButton = page.getByTestId("catalog-save");
    check("catalog-ui-save-exposes-busy-state-and-blocks-repeat", await saveButton.isDisabled()
        && await saveButton.evaluate((element) => element.classList.contains("is-loading")));
    await page.unroute("**/api/admin/v1/content/catalog");
    await page.getByTestId("catalog-notice").filter({ hasText: "草稿已保存为新修订" }).waitFor({ timeout: 15000 });
    check("catalog-ui-save-appends-draft-revision", true);

    currentCheck = "catalog-invalid-featured-reference";
    await page.getByTestId("catalog-featured-businesses").fill(`${originalFeaturedBusinesses}\nmissing-synthetic-business`);
    await page.getByTestId("catalog-save").click();
    await page.getByTestId("catalog-notice").waitFor({ timeout: 15000 });
    check("catalog-ui-rejects-invalid-featured-business-reference",
        !(await page.getByTestId("catalog-notice").innerText()).includes("草稿已保存为新修订"));
    await page.getByTestId("catalog-featured-businesses").fill(originalFeaturedBusinesses);

    currentCheck = "catalog-placeholder-channel-rejected";
    await page.getByTestId("catalog-channel-published").last().check();
    await page.getByTestId("catalog-save").click();
    await page.getByTestId("catalog-notice").waitFor({ timeout: 15000 });
    check("catalog-ui-does-not-save-published-placeholder-channel",
        !(await page.getByTestId("catalog-notice").innerText()).includes("草稿已保存为新修订"));
    await page.getByTestId("catalog-channel-published").last().uncheck();
    check("catalog-ui-restores-unpublished-placeholder-channel",
        await page.getByTestId("catalog-channel-qr-status").last().inputValue() === "PLACEHOLDER"
        && !(await page.getByTestId("catalog-channel-published").last().isChecked()));
    await page.getByTestId("catalog-save").click();
    await page.getByTestId("catalog-notice").filter({ hasText: "草稿已保存为新修订" }).waitFor({ timeout: 15000 });
    check("catalog-ui-saves-placeholder-channel-unpublished", true);

    currentCheck = "catalog-team-remove";
    await page.getByTestId("catalog-team-remove").last().click();
    check("catalog-ui-removes-team-member", (await teamItems.count()) === 1);
    await page.getByTestId("catalog-save").click();
    await page.getByTestId("catalog-notice").filter({ hasText: "草稿已保存为新修订" }).waitFor({ timeout: 15000 });
    check("catalog-ui-persists-team-member-removal", true);

    currentCheck = "catalog-package-remove";
    await page.getByTestId("catalog-package-remove").last().click();
    check("catalog-ui-removes-package", (await packageItems.count()) === packageCountBeforeAdd);
    await page.getByTestId("catalog-save").click();
    await page.getByTestId("catalog-notice").filter({ hasText: "草稿已保存为新修订" }).waitFor({ timeout: 15000 });
    check("catalog-ui-persists-package-removal", true);
    check("catalog-ui-retains-package-fields-before-empty-state",
        await page.getByTestId("catalog-package-name").first().inputValue() === "Synthetic UI package"
        && await page.getByTestId("catalog-package-service-items").first().inputValue() === "Synthetic UI service one\nSynthetic UI service two");

    currentCheck = "catalog-empty-home-and-packages";
    await page.getByTestId("catalog-package-remove").first().click();
    await page.getByTestId("catalog-featured-businesses").fill("");
    await page.getByTestId("catalog-featured-packages").fill("");
    await page.getByTestId("catalog-featured-works").fill("");
    await page.getByTestId("catalog-packages-empty").waitFor({ timeout: 10000 });
    check("catalog-ui-supports-zero-packages-and-empty-home-features",
        (await packageItems.count()) === 0
        && (await page.getByTestId("catalog-featured-businesses").inputValue()) === ""
        && (await page.getByTestId("catalog-featured-packages").inputValue()) === ""
        && (await page.getByTestId("catalog-featured-works").inputValue()) === "");
    await page.getByTestId("catalog-save").click();
    await page.getByTestId("catalog-notice").filter({ hasText: "草稿已保存为新修订" }).waitFor({ timeout: 15000 });
    check("catalog-ui-persists-zero-package-and-empty-home-state", true);

    currentCheck = "catalog-stale-save-conflict";
    await stalePage.getByTestId("catalog-brand-name").fill("Synthetic stale editor");
    await stalePage.getByTestId("catalog-save").click();
    await stalePage.getByTestId("catalog-notice").filter({ hasText: "內容已被其他人更新" }).waitFor({ timeout: 15000 });
    check("catalog-stale-editor-shows-version-conflict", true);
    await stalePage.close();

    currentCheck = "catalog-preview";
    await page.getByTestId("catalog-preview").click();
    await page.getByTestId("catalog-preview-status").filter({ hasText: "通过" }).waitFor({ timeout: 15000 });
    check("catalog-ui-preview-is-publishable", true);

    currentCheck = "catalog-publish-unknown-outcome";
    await page.route("**/api/admin/v1/content/catalog/publish", (route) => route.abort("failed"), { times: 1 });
    await page.getByTestId("catalog-publish").click();
    await page.getByTestId("catalog-notice").filter({ hasText: "結果未知" }).waitFor({ timeout: 15000 });
    check("catalog-publish-network-error-is-not-false-success", true);
    await page.getByTestId("catalog-resolve-unknown").click();
    await page.getByTestId("catalog-notice").filter({ hasText: "尚未找到已完成命令" }).waitFor({ timeout: 15000 });
    check("catalog-publish-unknown-command-can-be-queried", true);
    await page.getByTestId("catalog-publish").click();
    await page.getByTestId("catalog-notice").filter({ hasText: "已发布目录修订" }).waitFor({ timeout: 20000 });
    check("catalog-publish-retries-with-same-key-and-succeeds", true);

    check("catalog-ui-retains-brand-contact-business-team-and-channel-fields",
        await page.getByTestId("catalog-brand-name").inputValue() === "Synthetic UI Catalog"
        && await page.getByTestId("catalog-brand-english-name").inputValue() === "Synthetic UI Studio"
        && await page.getByTestId("catalog-contact-phone").inputValue() === "SYNTHETIC-ONLY"
        && await page.getByTestId("catalog-contact-address").inputValue() === "Synthetic UI address"
        && await page.getByTestId("catalog-contact-hours").inputValue() === "Synthetic UI hours"
        && await page.getByTestId("catalog-brand-discovery-heading").inputValue() === "Synthetic UI discovery"
        && await page.getByTestId("catalog-brand-share-title").inputValue() === "Synthetic UI share title"
        && await page.getByTestId("catalog-brand-tagline").inputValue() === "Synthetic UI tagline"
        && await page.getByTestId("catalog-brand-description").inputValue() === "Synthetic UI brand description"
        && await page.getByTestId("catalog-brand-logo-media").inputValue() !== ""
        && await page.getByTestId("catalog-channel-platform").first().inputValue() === "douyin"
        && await page.getByTestId("catalog-channel-label").last().inputValue() === "Synthetic UI placeholder channel"
        && await page.getByTestId("catalog-channel-platform").last().inputValue() === "xiaohongshu"
        && await page.getByTestId("catalog-channel-account").last().inputValue() === "synthetic-placeholder"
        && await page.getByTestId("catalog-business-summary").first().inputValue() === "Synthetic UI summary child"
        && await page.getByTestId("catalog-business-published").nth(1).isChecked()
        && await page.getByTestId("catalog-team-name").first().inputValue() === "Synthetic UI photographer"
        && await page.getByTestId("catalog-team-role").first().inputValue() !== ""
        && await page.getByTestId("catalog-team-portrait-media").first().inputValue() !== ""
        && await page.getByTestId("catalog-channel-label").first().inputValue() === "Synthetic UI contact");

    currentCheck = "catalog-withdraw-unknown-outcome";
    await page.route("**/api/admin/v1/content/catalog/withdraw", (route) => route.abort("failed"), { times: 1 });
    await page.getByTestId("catalog-withdraw").click();
    await page.getByTestId("catalog-notice").filter({ hasText: "結果未知" }).waitFor({ timeout: 15000 });
    check("catalog-withdraw-network-error-is-not-false-success", true);
    await page.getByTestId("catalog-resolve-unknown").click();
    await page.getByTestId("catalog-notice").filter({ hasText: "尚未找到已完成命令" }).waitFor({ timeout: 15000 });
    check("catalog-withdraw-unknown-command-can-be-queried", true);
    await page.getByTestId("catalog-withdraw").click();
    await page.getByTestId("catalog-notice").filter({ hasText: "当前目录已下架" }).waitFor({ timeout: 20000 });
    check("catalog-withdraw-retries-with-same-key-and-succeeds", true);
    check("catalog-browser-has-no-page-errors", !pageError);
}

try {
    await main();
    process.stdout.write(`M3_CATALOG_BROWSER_PASS:${checks.length}\n`);
} catch (error) {
    const errorType = error instanceof Error ? error.name.replace(/[^A-Za-z0-9_-]/g, "") : "UnknownError";
    process.stderr.write(`M3_CATALOG_BROWSER_FAILED:${currentCheck}-${errorType}\n`);
    process.exitCode = 2;
} finally {
    if (context) {
        try { await context.close(); } catch { process.exitCode = 2; }
    }
}
