# S1-D secure admin web login

## Goal

Deliver the explicitly authorized **non-production** S1-D minimum: a same-origin HTTPS-capable admin HTTP surface and an independent `admin-web` client that let a staff account log in, inspect its current identity and permissions, and log out safely. This is a local isolated-candidate slice, not C2 public recovery, production deployment, or a business-management UI.

## Scope

- Backend routes, under `/api/admin/v1`: CSRF retrieval, login, current session and logout, as specified by D-403.
- A separate Vue 3/TypeScript `admin-web` application containing only `/login`, `/`, `/forbidden` and explicit 404 states from D-606.
- Browser security controls: secure host-only session and CSRF cookies, strict same-origin/Origin checks, CSRF rotation, no default Spring Security login mechanisms, no cache of authentication responses, bounded JSON input and safe error envelopes.
- A local, disposable, non-production L5 test fixture and repeatable tests. Test identities must be created only by the existing controlled C3 CLI path; no default administrator or plaintext credential is added to source, configuration or evidence.

## Hard boundaries

- Do not touch C2's original public fixture, `127.0.0.1:3306/southleaf_public_it`, original service, production, real customers, real notifications, system/production trust, or C2's unchanged public-regression guard.
- Do not expose account bootstrap, password changes, disablement, permissions, migrations or arbitrary SQL through HTTP. C3 remains the only account-writer path.
- Do not add a public API controller, Reader fallback, automatic migration, default user, HTTP Basic, form login, remember-me, `JSESSIONID`, wildcard credential CORS, token body response, or browser storage of session/CSRF tokens.
- No commit, push, real deployment or permanent listener. The test server must use a dedicated owned fixture/port and be cleaned up.

## Requirements

1. The runtime opens a listener only after the existing verified admin bootstrap has succeeded and only with explicit local S1-D configuration, a configured public admin origin and TLS contract.
2. `POST /auth/login` validates bounded JSON before KDF, requires same-origin Origin and current CSRF protection, maps credential ambiguity to `401 INVALID_CREDENTIALS`, and sets a new `__Host-sl_admin_session` cookie only after session issuance succeeds.
3. `GET /auth/session` re-authenticates against the authoritative session/account state for every request and returns a stable, sorted, secret-free identity DTO; `POST /auth/logout` revokes before reporting `204` and clears cookies only after success.
4. Authentication/CSRF responses are `Cache-Control: no-store`, return a server request ID, use stable JSON error envelopes, and do not disclose credentials, hashes, raw tokens, SQL, schema, account existence or stack traces.
5. `admin-web` calls only relative API paths, stores the identity and CSRF token only in memory, avoids automatic replay of login/write operations, handles 401/403/429/503 as specified by D-606, and has accessible login/exit states.
6. The implementation has L1/L2 tests and a separately controlled L5 browser/API/MySQL acceptance path for S1D-01 through S1D-09. Until that fixture is actually run, it must be reported `NOT_RUN`, never inferred from component tests.

## Acceptance Criteria

- [ ] D-403 endpoint/error/cookie/Origin/CSRF contract is materialized in versioned OpenAPI and backend tests.
- [ ] Explicit configuration rejects missing or unsafe origin/TLS/listener inputs before binding a listener; public/admin artifact boundaries remain valid.
- [ ] Login/session/logout bridge only to `AdminLoginService` and `AdminSessionService`; no account lifecycle writer, migration or public Reader is reachable.
- [ ] `admin-web` is a separate application with unit/component tests for state, route protection, return-path validation and recoverable failure states.
- [ ] L5 isolation run proves the core login→session→logout cookie/CSRF flow and representative negative cases with a disposable test identity, or is truthfully retained as BLOCKED/NOT_RUN with its exact reason.
- [x] C2 is independently tracked; its later `PASS_OWNED_PUBLIC_DB_AND_NATIVE_REGRESSION` is recorded in P03 §15 and was not part of S1-D; C3 evidence remains intact; no production/original-service/commit/push activity occurred.

## Out of scope

Content, media, booking, orders, account management UI, registration/recovery, production reverse-proxy deployment, cross-origin clients, real IdP/WeChat integration, production load testing and S2–S7 business interfaces.
