# Credential core and KDF contracts (C1)

## 1. Scope / Trigger

New pure credential/login boundary and maintained-library adapter. C1 is unwired; SQL, grants, account CLI, HTTP and production deployment are not delivered. Keep core JDK-only; crypto depends inward on domain/ports, never on runtime/JDBC.

## 2. Signatures

- `AdminLoginService.login(String accountId, char[] password, UUID requestId) -> IssuedSession`.
- `CredentialLookup.find(String) -> Optional<CredentialSnapshot>`; empty is absence, not dependency failure.
- `PasswordVerifier.verify(char[], String) -> PasswordMatch(matches, upgradeRequired)`; `consumeDummyCost(char[]) -> void`.
- `PasswordHasher.hash(char[]) -> String` is low-level, not creation-policy approval.
- `AuthenticationEventSink.append(AuthenticationEvent) -> void`: durable-or-fail; session success audit remains atomic in AdminSessionStore.
- From backend: `node scripts/check-runtime-advisories.mjs [public-api|admin-credential-crypto]`.

## 3. Contracts

RequestId is trusted UUID. Login preserves original case/whitespace/Unicode; account IDs are ASCII `[A-Za-z0-9._-]{1,64}` for existing internal IDs. Future creation separately requires 3–64 and password/blocklist policy. PasswordInput bounds 1–128 Unicode scalars, UTF-8 <=512 bytes, no unpaired UTF-16 surrogate; bound before clone/lookup/KDF. Caller clears original array; service clears its clone in finally. Spring copies cannot be promised erased.

Snapshot ID must equal lookup key; hash-bearing record toString is redacted. KDF is outside locks; only captured version enters existing transaction-time recheck. Upgrade event precedes issuance and is not login success. No online rehash/account UPDATE.

Crypto: Argon2id v19, salt16/hash32; new m65536/t3/p1, accepted older m19456/t2/p1. Canonical PHC whitelist before library call; no arbitrary parameter ranges. One static nonblocking two-slot budget across adapter instances within the application classloader; all hash/verify/dummy paths use it. Does not share across JVMs. Fixed current-profile dummy never returns identity; older profile timing differs.

## 4. Validation & Error Matrix

| Condition | Behavior |
|---|---|
| Empty/oversized/invalid UTF-16 password | deny + event; no copy/lookup/KDF |
| Invalid ID, absent/disabled account with bounded password | current dummy then generic denial |
| Wrong password or version changed before issue | generic AdminAuthenticationException |
| Invalid stored hash, inconsistent lookup identity | CredentialDependencyException; no token, not absence |
| Both KDF slots busy | CAPACITY_EXHAUSTED, immediate; no queue; future HTTP mapping is separate |
| lookup/event/Store failure | propagate failure; no successful result |
| Old profile matches | upgrade event before issue; no account write |
| Scan/inventory/response failure | nonzero; remove prior selected-module report before attempting validation/query |

## 5. Good / Base / Bad Cases

Good: independently generated UTF-8 vector including trailing spaces matches exactly; captured version stays unchanged through KDF and is accepted by Store. Base: current profile match, no upgrade event; success audit in existing transaction. Bad: normalized password, tampered Base64 low bits, huge memory/iteration/p count, third KDF instance, audit outage or stale version must not issue a session.

## 6. Required Tests

AdminLoginServiceTest checks immutable caller input, cleared owned copy on success/failure, snapshot binding, dummy paths, unified denials, version rejection, upgrade ordering and dependency failures. Argon2PasswordServiceTest uses argon2-cffi 25.1.0 vectors, random salts, Unicode boundaries, strict PHC and a two-slot barrier with a third adapter. Run actual benchmark separately; sampled heap is not RSS or production capacity.

Run full reactor verify/real MySQL IT, declared graph + jdeps + nested artifact scans, fresh resolved inventories and separate OSV scans. `node --test backend/scripts/test-runtime-advisories.mjs` verifies failed/stale/incomplete scans cannot retain a prior clean report. Candidate public smoke/contract must use the new candidate port; keep original service/legacy target untouched.

## 7. Wrong vs Correct

Wrong: call Spring defaults or pass database PHC straight to the decoder; attach crypto to core; cache a credential/version; return a login success after event failure; read an older clean JSON after OSV failed.

Correct: explicit maintained adapter profile, validate bounded canonical PHC first, share two slots, verify outside lock and recheck inside Store; persist-or-fail events, no runtime wiring before C2; require successful latest scan and a fresh report. Do not log passwords, hashes, caller arrays or raw SQL failures.


## 8. C2 persistence/composition continuation (not acceptance)

The unwired descriptions above refer to the C1 increment. C2 source now explicitly composes the same pure login use case/KDF with JDBC ports after verified-pool checks; management-scope real MySQL tests and runtime probes have passed. It remains non-Web, with full C2 acceptance blocked on the original public test environment; local isolated success is not production acceptance.

- Credential lookup is exact-ID primary SELECT in REQUIRES_NEW/read-only/READ_COMMITTED with five-second timeout. End that transaction before KDF; no cached version, normalization, or dependency-failure-as-absence.
- Authentication events INSERT only five safe fields (generated UUID, UTC microsecond time, limited type, optional known account, trusted request UUID) in independent short write transactions; failure propagates. Upgrade hint precedes issuance, successful session audit remains in Store transaction; no online account UPDATE.
- Runtime contract admin-isolated-v2 requires the previous six privileges plus auth-event INSERT and compatible ordered columns. Same private verified source supplies all ports. Column shape is not a substitute for CLI migration/checksum/constraints validation.
- Test SQL hooks inject faults/order barriers with real successful JDBC work; never label them a database outage. KDF/reset/disable, event failure-before-issue and outer snapshot scenarios need actual owned-instance IT, not only pure-core mocks.
- Current evidence/remaining authorization: [C2 guide](../../../docs/开发与演示/阶段二S1-C2凭据持久化与接线验证指南.md). No-DB checks or a probe report alone do not close C2, C3, HTTP/UI or production gates.
