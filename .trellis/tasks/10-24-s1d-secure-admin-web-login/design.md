# S1-D design — secure admin HTTP and minimum web client

## 1. Architecture and trust boundary

`admin-web` and `admin-runtime` share one configured HTTPS origin. The browser uses relative `/api/admin/v1` URLs only. The runtime is the sole browser-facing adapter and delegates authentication/session decisions to the existing application services; it never exposes JDBC, Flyway, C3 lifecycle commands or public Reader capabilities.

The Web adapter owns request size/content-type/JSON checks, request-ID generation, Origin/CSRF validation, cookie translation and stable HTTP errors. `AdminLoginService` owns credential verification/event facts, while `AdminSessionService` remains the authority for session issuance, current-session validation and revocation.

The listener is opt-in. Existing non-Web startup behavior remains the safe default. A dedicated local S1-D configuration namespace must require `mode=demo`, exact `PUBLIC_ADMIN_ORIGIN`, strict HTTPS/TLS inputs, and the existing verified admin database configuration before any listener bind. Missing, public/production-like or internally inconsistent inputs fail closed.

## 2. Endpoint contract

| Route | Protection | Success | Failure constraints |
|---|---|---|---|
| `GET /api/admin/v1/auth/csrf` | anonymous same-origin retrieval | 200 `{code,data:{csrfToken,headerName},requestId}` plus CSRF cookie | no session issuance; no-store |
| `POST /api/admin/v1/auth/login` | `application/json`, <=4KiB, exact fields, Origin and CSRF | 200 identity DTO and new secure session cookie | credential ambiguity 401; dependency/KDF/store failure 503; never reflect password/account existence |
| `GET /api/admin/v1/auth/session` | valid session cookie | 200 identity DTO | 401 clears an invalid token cookie; 503 does not claim logout |
| `POST /api/admin/v1/auth/logout` | Origin and CSRF; cookie optional | 204 after revocation and audit success | dependency failure 503 without reporting logout success; no token enumeration |

The identity DTO is `{accountId, permissions, expiresAt}`. Permissions are de-duplicated and stable-sorted. It intentionally excludes token, token digest, password hash, security version, role configuration and database attributes.

All errors use `{code,message,requestId}` and `X-Request-Id`; all auth/CSRF responses have `Cache-Control: no-store`. Controller advice maps only known input/auth/authorization/rate/dependency conditions, with all unexpected errors converted to the same safe 503 envelope and server-side correlation only.

## 3. Browser security mechanics

- Session cookie: `__Host-sl_admin_session; Secure; HttpOnly; SameSite=Strict; Path=/`, no Domain, with Max-Age bounded by server expiry. It is never included in a response body.
- CSRF cookie: `__Host-sl_admin_csrf; Secure; SameSite=Strict; Path=/`, not HttpOnly, and a fixed `X-CSRF-TOKEN` header. Use Spring Security's cookie repository rather than a custom signing scheme.
- Login/logout write requests require the exact configured Origin and a current CSRF token. Missing, `null`, forged or mismatched Origin yields 403 before credential work. Cross-origin credential CORS is absent.
- Disable form login, HTTP Basic, remember-me, anonymous default user and servlet session creation. A successful login rotates CSRF state; previous headers fail after rotation.
- Enforce request-size/type/JSON-shape before binding password material or invoking the KDF. Logging filters must redact cookie, CSRF and password fields.

## 4. Rate limiting and operational limits

The first local-candidate implementation uses bounded, in-memory per-origin/account rate buckets and a bounded KDF concurrency semaphore. It returns 429 with `Retry-After`, fails closed on capacity exhaustion and explicitly documents that it is single-process protection, not a multi-instance distributed limiter. Production/multi-instance behavior remains out of scope.

## 5. Admin web behavior

`admin-web` is a separate Vite/Vue application, not part of the native WeChat `frontend/` package. It has an API client, auth composable and route guard. Identity and CSRF state live only in JavaScript memory. No cookie value is readable by app code and no token/identity is persisted to localStorage/sessionStorage.

State machine: `UNKNOWN → CHECKING → AUTHENTICATED | ANONYMOUS | UNAVAILABLE`. On 401, clear memory and redirect to login; on 403 retain identity and expose a security/permission state; on 429 show wait time; on 503 expose an explicit retry state. `returnTo` accepts only known same-app relative routes and rejects schemes, `//`, backslashes and decoding bypasses. Login and logout do not auto-replay after a network failure.

## 6. Test strategy and evidence

- L1/L2: contract serialization, strict request parsing, error mapping, security filter configuration/order, origin/CSRF/cookie attributes, no-default-auth mechanisms, rate limiter, module/artifact isolation, and frontend unit/component tests.
- L5: dedicated disposable S1-D fixture only. It creates a controlled staff account through C3 CLI, starts an owned TLS candidate, uses a browser CookieJar/Playwright to cover S1D-01..09, then stops and deletes only resources proven owned by the manifest.
- C2 is not an S1-D fixture. The original public C2 entry remains untouched and its regression status stays BLOCKED.
- Evidence records commands, source/candidate binding, environment class, counts, skip/failure reason, and cleanup. It excludes passwords, private keys, raw cookies, CSRF values and request bodies.

## 7. Sequencing and rollback

1. Materialize OpenAPI and backend/web task scaffolding; preserve default non-Web behavior.
2. Add Spring Web/Security integration and test default-deny listener/configuration gates before adding endpoint bridges.
3. Add endpoint bridges and deterministic contract tests; then add `admin-web` state/route tests.
4. Propose the separate S1-D owned-fixture runner before any L5 database/listener execution; run only under this explicit authorization and preserve cleanup evidence.
5. On failure, stop only the owned candidate and remove only its manifest-owned directory. Revert source changes separately if requested; never reset/clean the shared worktree or touch C2/original/production services.
