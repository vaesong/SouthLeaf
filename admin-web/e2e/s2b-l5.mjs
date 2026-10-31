// S2-B disposable L5 browser check. Driven only by verify-s2b-work-publication-isolated.py.
import assert from "node:assert/strict";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { chromium } from "playwright-core";

const baseUrl = process.env.S2B_L5_BASE_URL;
const accountId = process.env.S2B_L5_ACCOUNT;
const password = process.env.S2B_L5_ACCOUNT_PASSWORD;
const fixtureRoot = process.env.S2B_L5_FIXTURE_ROOT;
const profileDir = process.env.S2B_L5_PROFILE_DIR;
const workId = process.env.S2B_L5_WORK_ID;
const newTitle = process.env.S2B_L5_NEW_TITLE;
const fixtureKind = process.env.SOUTHLEAF_S2B_FIXTURE_KIND;
const checks = [];
let currentCheck = "preflight";
let context;

function check(name, condition) {
    assert.ok(condition, name);
    checks.push(name);
}

async function main() {
    assert.equal(fixtureKind, "S2B_DISPOSABLE_L5");
    assert.ok(baseUrl && /^https:\/\/localhost:[0-9]{5}$/.test(baseUrl));
    assert.ok(accountId && /^s2b\.[a-z0-9]{8}$/.test(accountId));
    assert.ok(password && password.length >= 20);
    assert.ok(workId && /^[0-9a-f-]{36}$/.test(workId));
    assert.ok(newTitle && newTitle.length <= 80);
    assert.ok(fixtureRoot && profileDir);
    assert.equal(path.dirname(path.resolve(profileDir)), path.resolve(fixtureRoot));
    await mkdir(profileDir);

    context = await chromium.launchPersistentContext(profileDir, {
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
    const page = await context.newPage();
    const externalRequests = [];
    let pageError = false;
    page.on("pageerror", () => {
        pageError = true;
    });
    page.on("request", (browserRequest) => {
        const url = new URL(browserRequest.url());
        if (url.protocol !== "data:" && url.origin !== baseUrl) externalRequests.push(url.protocol);
    });

    currentCheck = "login";
    await page.goto(`${baseUrl}/`, { waitUntil: "domcontentloaded", timeout: 20000 });
    await page.getByRole("heading", { name: "管理入口" }).waitFor({ timeout: 15000 });
    await page.locator('input[name="username"]').fill(accountId);
    await page.locator('input[name="password"]').fill(password);
    await page.getByRole("button", { name: "安全登录" }).click();
    await page.getByRole("heading", { name: "今天先看这些。" }).waitFor({ timeout: 15000 });
    check("ui-login", true);

    currentCheck = "works-link";
    await page.getByTestId("works-link").click();
    await page.getByRole("heading", { name: "作品" }).waitFor({ timeout: 15000 });
    check("works-route-from-home", page.url() === `${baseUrl}/works`);

    currentCheck = "works-table";
    await page.getByTestId("works-table").waitFor({ timeout: 15000 });
    check(
        "works-table-lists-fixture-work",
        (await page.getByTestId(`work-state-${workId}`).count()) === 1,
    );

    currentCheck = "media-thumbnails";
    const thumbs = page.locator('[data-testid="media-grid"] img');
    await thumbs.first().waitFor({ timeout: 15000 });
    await page.waitForFunction(
        () =>
            [...document.querySelectorAll('[data-testid="media-grid"] img')].every(
                (img) => img.complete && img.naturalWidth > 0,
            ),
        null,
        { timeout: 15000 },
    );
    const widths = await thumbs.evaluateAll((images) => images.map((img) => img.naturalWidth));
    check(
        "thumbnails-served-same-origin-with-session",
        widths.length >= 2 && widths.every((w) => w > 0 && w <= 480),
    );

    currentCheck = "edit-page";
    await page.goto(`${baseUrl}/works/${workId}`, { waitUntil: "domcontentloaded", timeout: 20000 });
    await page.getByRole("heading", { name: "編輯作品" }).waitFor({ timeout: 15000 });
    const before = await page.getByTestId("work-version").innerText();
    const beforeVersion = Number(/v(\d+)/.exec(before)?.[1]);
    check("edit-page-shows-server-revision", beforeVersion >= 1);

    currentCheck = "save-draft";
    const titleInput = page.locator('[data-testid="work-title-input"]');
    await titleInput.fill(newTitle);
    await page.getByTestId("save-draft").click();
    await page.waitForFunction(
        (expected) =>
            document.querySelector('[data-testid="work-version"]')?.textContent?.includes(`v${expected}`),
        beforeVersion + 1,
        { timeout: 15000 },
    );
    check("draft-save-increments-revision", true);

    currentCheck = "preview";
    await page.waitForFunction(
        () => document.querySelector('[data-testid="publishable"]')?.textContent?.trim() === "是",
        null,
        { timeout: 15000 },
    );
    check("server-preview-publishable", true);

    currentCheck = "publish";
    await page.getByTestId("publish").click();
    await page.waitForFunction(
        () => document.querySelector('[data-testid="content-notice"]')?.textContent?.includes("已发布"),
        null,
        { timeout: 20000 },
    );
    check(
        "ui-publish-succeeded",
        (await page.getByTestId("last-command").innerText()).includes("PUBLISH"),
    );
    await page.getByTestId("withdraw").waitFor({ state: "visible", timeout: 10000 });
    check("withdraw-offered-after-publish", (await page.getByTestId("withdraw").count()) === 1);

    currentCheck = "catalog-media-picker";
    await page.goto(`${baseUrl}/catalog`, { waitUntil: "domcontentloaded", timeout: 20000 });
    await page.getByRole("heading", { name: "品牌与目录" }).waitFor({ timeout: 15000 });
    const mediaPicker = page.getByTestId("catalog-brand-logo-media");
    await mediaPicker.waitFor({ timeout: 15000 });
    const readyOptions = await mediaPicker.locator("option").evaluateAll((options) =>
        options.filter((option) => option.value && !option.disabled && option.textContent?.includes("READY")).length,
    );
    check("catalog-route-loads-ready-media-picker", readyOptions >= 1);
    await page.getByTestId("catalog-refresh-media").click();
    check("catalog-media-refresh-keeps-ready-options", (await mediaPicker.locator("option").count()) >= readyOptions + 1);

    currentCheck = "storage";
    const storage = await page.evaluate(
        () => window.localStorage.length + window.sessionStorage.length,
    );
    check("no-browser-storage", storage === 0);
    check("no-page-errors", !pageError);
    check("all-requests-same-origin", externalRequests.length === 0);
}

try {
    await main();
    process.stdout.write(`S2B_BROWSER_PASS:${checks.length}\n`);
} catch {
    process.stderr.write(`S2B_BROWSER_FAILED:${currentCheck}\n`);
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
