import assert from "node:assert/strict";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { chromium, request } from "playwright-core";

const baseUrl = process.env.S1D_L5_BASE_URL;
const accountId = process.env.S1D_L5_ACCOUNT;
const password = process.env.S1D_L5_ACCOUNT_PASSWORD;
const fixtureRoot = process.env.S1D_L5_FIXTURE_ROOT;
const profileDir = process.env.S1D_L5_PROFILE_DIR;
const fixtureKind = process.env.SOUTHLEAF_S1D_FIXTURE_KIND;
const csrfPath = "/api/admin/v1/auth/csrf";
const loginPath = "/api/admin/v1/auth/login";
const sessionPath = "/api/admin/v1/auth/session";
const logoutPath = "/api/admin/v1/auth/logout";
const sessionCookieName = "__Host-sl_admin_session";
const csrfCookieName = "__Host-sl_admin_csrf";
const checks = [];
let currentCheck = "preflight";
let context;

function check(name, condition) {
  assert.ok(condition, name);
  checks.push(name);
}

function cookie(cookies, name) {
  return cookies.find((candidate) => candidate.name === name);
}

async function json(response) {
  return response.json();
}

async function main() {
  assert.equal(fixtureKind, "S1D_DISPOSABLE_L5");
  assert.ok(baseUrl && /^https:\/\/localhost:[0-9]{5}$/.test(baseUrl));
  assert.ok(accountId && /^s1d\.[a-z0-9]{8}$/.test(accountId));
  assert.ok(password && password.length >= 20);
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
  let sameOriginRequestCount = 0;
  let sessionBootstrapRequested = false;
  let sessionBootstrapStatus;
  let csrfBootstrapRequested = false;
  let csrfBootstrapOriginMatches = false;
  let csrfBootstrapRequestFailed = false;
  let csrfBootstrapStatus;
  let pageErrorName;
  page.on("pageerror", (error) => {
    const name = error?.constructor?.name;
    if (
      [
        "Error",
        "TypeError",
        "ReferenceError",
        "SyntaxError",
        "RangeError",
        "URIError",
        "EvalError",
      ].includes(name)
    )
      pageErrorName = name;
  });
  page.on("request", (browserRequest) => {
    const url = new URL(browserRequest.url());
    if (url.protocol === "data:") return;
    if (url.origin !== baseUrl) externalRequests.push(url.protocol);
    else sameOriginRequestCount += 1;
    if (url.pathname === sessionPath) sessionBootstrapRequested = true;
    if (url.pathname === csrfPath) {
      csrfBootstrapRequested = true;
      csrfBootstrapOriginMatches = url.origin === baseUrl;
    }
  });
  page.on("requestfailed", (browserRequest) => {
    const url = new URL(browserRequest.url());
    if (url.pathname === csrfPath) csrfBootstrapRequestFailed = true;
  });
  page.on("response", (browserResponse) => {
    const url = new URL(browserResponse.url());
    if (url.pathname === sessionPath) sessionBootstrapStatus = browserResponse.status();
    if (url.pathname === csrfPath) csrfBootstrapStatus = browserResponse.status();
  });

  currentCheck = "same-origin-ui-navigation";
  await page.goto(`${baseUrl}/`, { waitUntil: "domcontentloaded", timeout: 20000 });
  currentCheck = "management-heading-visible";
  await page.getByRole("heading", { name: "管理入口" }).waitFor({ timeout: 15000 });
  currentCheck = "csrf-bootstrap-ready";
  await page.locator('input[name="username"]').fill(accountId);
  await page.locator('input[name="password"]').fill(password);
  try {
    await page.waitForFunction(
      () => {
        const submit = document.querySelector('button[type="submit"]');
        return submit && !submit.disabled;
      },
      null,
      { timeout: 15000 },
    );
  } catch {
    const status = page.getByRole("status");
    const statusText = (await status.count()) ? ((await status.first().textContent()) ?? "") : "";
    const alert = page.getByRole("alert");
    const alertVisible = (await alert.count()) > 0;
    const alertText = alertVisible ? ((await alert.first().textContent()) ?? "") : "";
    const uiState = alertText.includes("安全初始化暫時無法完成")
      ? "csrf"
      : alertText.includes("目前無法確認管理服務狀態")
        ? "session"
        : alertVisible
          ? "alert"
          : statusText.includes("正在準備安全驗證")
            ? "pending"
            : statusText.includes("需要完成安全初始化")
              ? "unready"
              : "none";
    const pageUrl = new URL(page.url());
    const routeCode = pageUrl.pathname === "/login" ? "login" : "other";
    const originCode = pageUrl.origin === baseUrl ? "same" : "other";
    const sessionCode =
      sessionBootstrapStatus === undefined
        ? sessionBootstrapRequested
          ? "sx"
          : "sn"
        : `s${sessionBootstrapStatus}`;
    if (!csrfBootstrapRequested)
      currentCheck = `csrf-no-req-${uiState}-${routeCode}-${originCode}-${sessionCode}-r${Math.min(sameOriginRequestCount, 99)}`;
    else if (!csrfBootstrapOriginMatches) currentCheck = "csrf-origin-mismatch";
    else if (csrfBootstrapRequestFailed) currentCheck = "csrf-request-failed";
    else if (csrfBootstrapStatus === undefined) currentCheck = "csrf-no-response";
    else currentCheck = `csrf-http-${csrfBootstrapStatus}`;
    if (pageErrorName) currentCheck += `-${pageErrorName.toLowerCase()}`;
    throw new Error("CSRF bootstrap not ready");
  }
  currentCheck = "csrf-cookie-visible-to-script";
  await page.waitForFunction(() => document.cookie.includes("__Host-sl_admin_csrf="), null, {
    timeout: 15000,
  });
  currentCheck = "same-origin-request-scope";
  check("same-origin-https-ui", externalRequests.length === 0);

  currentCheck = "csrf-token-and-cookie";
  const csrfResponse = await context.request.get(`${baseUrl}${csrfPath}`);
  check("csrf-endpoint", csrfResponse.status() === 200);
  const csrfEnvelope = await json(csrfResponse);
  const csrfToken = csrfEnvelope?.data?.csrfToken;
  check(
    "csrf-token-contract",
    csrfEnvelope?.code === "OK" &&
      csrfEnvelope?.data?.headerName === "X-CSRF-TOKEN" &&
      typeof csrfToken === "string" &&
      csrfToken.length >= 1 &&
      csrfToken.length <= 4096,
  );
  const beforeLoginCookies = await context.cookies(baseUrl);
  const csrfCookie = cookie(beforeLoginCookies, csrfCookieName);
  check(
    "csrf-cookie-secure-samesite",
    Boolean(csrfCookie) &&
      csrfCookie.secure &&
      !csrfCookie.httpOnly &&
      csrfCookie.sameSite === "Strict" &&
      csrfCookie.path === "/",
  );
  check("no-servlet-session-cookie", !cookie(beforeLoginCookies, "JSESSIONID"));

  currentCheck = "anonymous-session";
  const anonymous = await context.request.get(`${baseUrl}${sessionPath}`);
  check("session-requires-authentication", anonymous.status() === 401);

  currentCheck = "missing-origin-rejected";
  const missingOrigin = await context.request.post(`${baseUrl}${loginPath}`, {
    headers: { "Content-Type": "application/json" },
    data: { accountId: "s1d.00000000", password: "not-a-real-secret" },
  });
  const missingOriginBody = await json(missingOrigin);
  check(
    "missing-origin-rejected",
    missingOrigin.status() === 403 && missingOriginBody?.code === "ORIGIN_REJECTED",
  );

  currentCheck = "forged-origin-rejected";
  const forgedOrigin = await context.request.post(`${baseUrl}${loginPath}`, {
    headers: {
      Origin: "https://invalid.example",
      "Content-Type": "application/json",
    },
    data: { accountId: "s1d.00000000", password: "not-a-real-secret" },
  });
  const forgedOriginBody = await json(forgedOrigin);
  check(
    "forged-origin-rejected",
    forgedOrigin.status() === 403 && forgedOriginBody?.code === "ORIGIN_REJECTED",
  );

  currentCheck = "missing-csrf-rejected";
  const missingCsrf = await page.evaluate(
    async (body) => {
      const response = await fetch("/api/admin/v1/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "same-origin",
        body: JSON.stringify(body),
      });
      return { status: response.status, body: await response.json() };
    },
    { accountId: "s1d.00000000", password: "not-a-real-secret" },
  );
  check(
    "missing-csrf-rejected",
    missingCsrf.status === 403 && missingCsrf.body?.code === "CSRF_REJECTED",
  );

  currentCheck = "forged-csrf-rejected";
  const forgedCsrf = await page.evaluate(
    async (body) => {
      const response = await fetch("/api/admin/v1/auth/login", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-CSRF-TOKEN": "deliberately-invalid-test-token",
        },
        credentials: "same-origin",
        body: JSON.stringify(body),
      });
      return { status: response.status, body: await response.json() };
    },
    { accountId: "s1d.00000000", password: "not-a-real-secret" },
  );
  check(
    "forged-csrf-rejected",
    forgedCsrf.status === 403 && forgedCsrf.body?.code === "CSRF_REJECTED",
  );

  currentCheck = "wrong-password-rejected";
  await page.locator('input[name="username"]').fill(accountId);
  await page.locator('input[name="password"]').fill("S1D_invalid_fixture_password_92");
  const wrongLoginWait = page.waitForResponse(
    (response) => response.url().endsWith(loginPath) && response.request().method() === "POST",
    { timeout: 15000 },
  );
  await page.getByRole("button", { name: "安全登入" }).click();
  const wrongLogin = await wrongLoginWait;
  const wrongLoginBody = await json(wrongLogin);
  check(
    "wrong-password-rejected",
    wrongLogin.status() === 401 && wrongLoginBody?.code === "INVALID_CREDENTIALS",
  );
  await page.getByRole("alert").waitFor({ timeout: 5000 });

  currentCheck = "valid-login-session-identity";
  await page.locator('input[name="password"]').fill(password);
  const goodLoginWait = page.waitForResponse(
    (response) => response.url().endsWith(loginPath) && response.request().method() === "POST",
    { timeout: 20000 },
  );
  await page.getByRole("button", { name: "安全登入" }).click();
  const goodLogin = await goodLoginWait;
  check("login-accepted", goodLogin.status() === 200);
  const identityEnvelope = await json(goodLogin);
  check(
    "login-identity-dto",
    identityEnvelope?.code === "OK" &&
      identityEnvelope?.data?.accountId === accountId &&
      Array.isArray(identityEnvelope?.data?.permissions) &&
      identityEnvelope.data.permissions.includes("STAFF_ACCESS_MANAGE") &&
      typeof identityEnvelope?.data?.expiresAt === "string",
  );
  await page.getByRole("heading", { name: "已安全登入" }).waitFor({ timeout: 15000 });
  check("identity-rendered-by-spa", (await page.locator("body").innerText()).includes(accountId));
  check(
    "permission-rendered-by-spa",
    (await page.locator("body").innerText()).includes("STAFF_ACCESS_MANAGE"),
  );

  const sessionResponse = await context.request.get(`${baseUrl}${sessionPath}`);
  check("current-session-endpoint", sessionResponse.status() === 200);
  const currentIdentity = await json(sessionResponse);
  check(
    "current-session-identity",
    currentIdentity?.data?.accountId === accountId &&
      currentIdentity?.data?.permissions?.includes("STAFF_ACCESS_MANAGE"),
  );

  currentCheck = "secure-session-cookie";
  const authenticatedCookies = await context.cookies(baseUrl);
  const sessionCookie = cookie(authenticatedCookies, sessionCookieName);
  check(
    "session-cookie-secure-httponly-samesite",
    Boolean(sessionCookie) &&
      sessionCookie.secure &&
      sessionCookie.httpOnly &&
      sessionCookie.sameSite === "Strict" &&
      sessionCookie.path === "/",
  );
  check("no-jessionid-after-login", !cookie(authenticatedCookies, "JSESSIONID"));
  const oldSessionValue = sessionCookie?.value;
  check(
    "old-session-captured-in-memory-only",
    typeof oldSessionValue === "string" && oldSessionValue.length > 20,
  );

  currentCheck = "memory-only-auth-state";
  const storedState = await page.evaluate(() => ({
    localStorage: window.localStorage.length,
    sessionStorage: window.sessionStorage.length,
  }));
  check(
    "auth-state-not-in-browser-storage",
    storedState.localStorage === 0 && storedState.sessionStorage === 0,
  );

  currentCheck = "logout-revokes-old-session";
  const logoutWait = page.waitForResponse(
    (response) => response.url().endsWith(logoutPath) && response.request().method() === "POST",
    { timeout: 20000 },
  );
  await page.getByRole("button", { name: "退出登入" }).click();
  const logoutResponse = await logoutWait;
  check("logout-endpoint", logoutResponse.status() === 204);
  await page.getByRole("heading", { name: "管理入口" }).waitFor({ timeout: 15000 });
  const afterLogoutCookies = await context.cookies(baseUrl);
  check("logout-clears-session-cookie", !cookie(afterLogoutCookies, sessionCookieName));
  check("no-jessionid-after-logout", !cookie(afterLogoutCookies, "JSESSIONID"));

  currentCheck = "old-session-invalidated";
  const staleContext = await request.newContext({
    ignoreHTTPSErrors: true,
    extraHTTPHeaders: { Cookie: `${sessionCookieName}=${oldSessionValue}` },
  });
  try {
    const staleResponse = await staleContext.get(`${baseUrl}${sessionPath}`);
    check("old-session-rejected-after-logout", staleResponse.status() === 401);
  } finally {
    await staleContext.dispose();
  }

  currentCheck = "memory-only-after-logout";
  const finalStorage = await page.evaluate(() => ({
    localStorage: window.localStorage.length,
    sessionStorage: window.sessionStorage.length,
  }));
  check(
    "storage-remains-empty-after-logout",
    finalStorage.localStorage === 0 && finalStorage.sessionStorage === 0,
  );
  check("all-browser-requests-same-origin", externalRequests.length === 0);
}

try {
  await main();
  process.stdout.write(`S1D_BROWSER_PASS:${checks.length}\n`);
} catch {
  process.stderr.write(`S1D_BROWSER_FAILED:${currentCheck}\n`);
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
