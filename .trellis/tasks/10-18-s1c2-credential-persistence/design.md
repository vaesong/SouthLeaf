# C2 G1 design

## Schema and SQL
V3 only: admin_auth_event(event_id CHAR36 ASCII binary PK, occurred_at DATETIME6 NOT NULL, event_type VARCHAR40 ASCII binary NOT NULL, actor_account_id VARCHAR64 ASCII binary NULL FK admin_account, request_id CHAR36 ASCII binary NOT NULL). Types limited LOGIN_DENIED/CREDENTIAL_UPGRADE_REQUIRED; upgrade requires actor. Index time, actor+time, request. No raw attempted names/password/hash/IP/UA; no default data. Existing V1/V2 unchanged. Future account_command/guard remain C3's separate migration.

CredentialLookup: one parameterized primary-store SELECT account_id,enabled,security_version,credential_hash by exact validated ID. REQUIRES_NEW read-only READ_COMMITTED, timeout5; exit transaction before KDF; no stale ambient transaction/cache. Empty means absent; malformed facts/technical failure not absence.
Event sink: INSERT five fields with generated UUID, UTC microsecond timestamp. REQUIRES_NEW read-write timeout5; failure never swallowed. Upgrade hint is NOT success; success audit stays existing session+audit transaction. No retry on uncertain commit. New adapters wrap only expected DataAccess/Transaction/data-validation errors into existing storage exception; no secret logging.

## Exact authority / composition
CONTRACT_ID admin-isolated-v2; required direct grants = existing6 + admin_auth_event INSERT (five tables/seven privileges); no old-profile fallback, no account UPDATE/event SELECT/history SELECT. Pool remains private capability wrapper; same verified source builds all JDBC ports. Startup also checks five ordered auth-event column names/types/nullability via information_schema (visible with INSERT), no reading event rows/history, no auto-migration. Shape check does not substitute CLI Flyway checksum/constraint validation.
Runtime adds crypto module, JDK Clock UTC, SecureSessionTokenCodec, two-hour AdminSessionService and AdminLoginService explicitly; no scanning/Web/raw DataSource bean. Guard failure prevents publication. Same C1 KDF parameters, static budget2, no online rehash.

## Tests / authorization
Use existing private manifest, UUID/datadir ownership checks, loopback and VERIFY_IDENTITY, DPAPI secrets via child environment/stdin. New V3/grants apply only there. Admin IT require dedicated SECURITY_IT URL/user/password/UUID/truststore; reject3306, require exact schema and expectedUUID, no Reader or old test fallback. Existing public IT/Reader unchanged. Test-only helper and --with-reactor harness run full verify while owned instance alive, then SQL SHUTDOWN. New prepared artifact must exist first (package, not clean). Auth-event fixtures have only synthetic credentials; no default production seed.

## Required real cases
V3 migration idempotence, event constraints/UTC/no secrets/append-only, unknown/case sensitivity/current read outside outer snapshot, real matches/denials/old profile, event INSERT failure before issuance, lookup failure, reset/disable between verification and issuance, SQL outside KDF locks, exact Writer event SELECT/UPDATE/DELETE and account UPDATE denied, missing/extra event grants and incompatible event shape refuse startup. Extend current 29-scenario harness, do not replace old negatives.

## Deferred
C3 last-maintainer guard/recovery/password creation/blocklist/CLI exact grants are not C2 acceptance. HTTP/external rate-limiting stays S1-D. Test-CA/production-mode fixture not production acceptance. No independent sub-agent audit claim.


## 2026-10-24 C2 verification closure design

- The existing C2 scope remains non-Web. User approved continuing implementation, not database/grants/service/prod operations; request one explicit minimal environment authorization before real verification.
- Every step after starting the owned server, including both migrations and incremental grants, is inside cleanup coverage. Failure retains primary and cleanup classifications, never a false stopped/PASS claim.
- Invalidate previous latest PASS/partial reports when a valid owned manifest starts a new run. Write new JSON atomically with run ID, source/candidate/probe hashes and actual shutdown outcome; never arbitrary exception/SQL output.
- `verify --with-reactor` runs the existing full-reactor PowerShell entry with owner-injected SECURITY_IT inputs, BEFORE final candidate pinning. Default probes clearly record reactor NOT_RUN; neither report alone closes C2.
- Full reactor may build candidates; after it finishes, freeze the runtime JAR, embedded classes/libraries and test-only probe classes in the private owned directory. All process probes use that frozen copy. Assert source and original candidate/test-class hashes unchanged before accepting results. No mid-probe install/rebuild.
- Test-DatabaseIntegration.ps1 rejects missing/invalid isolated inputs before reading public DPAPI credentials or running Maven. `-ValidateOnly` performs no connection or credential-store access. Public IT still uses its existing explicitly configured test schema; real authorization must mention this fixture write scope.
- Add deterministic stdlib tests with mocked DB/process/Maven seams for migration/grant/build/probe/cleanup failures, stale reports, candidate drift and output sanitization. Add real JDBC login combination cases separately; compile/unit checks do not count as real IT acceptance.
- Retain all existing runtime permission/TLS/store/shutdown cases and add a real owned-fixture extra-column refusal/restoration probe. Do not edit applied V1/V2, original grants, or widen runtime capabilities.


## Authorized execution checkpoint (2026-10-24)

The initial continuation authorization above was later extended explicitly by the user for controlled existing test fixtures and 30-day same-key public test certificates/JKS/manifest maintenance. No new private key/plaintext password, system/production trust or original service operation was authorized/performed. j16 renewed the test public trust fixture; j18 management-only IT and j19 fixed-candidate runtime probes passed with normal shutdown. Full/public/native acceptance remains BLOCKED on the original public test endpoint; do not relabel the subset as C2 completion. Current evidence is in the C2 guide/credential-validation record, not the earlier no-DB checkpoint. This does not grant C3/production/commit authority.
