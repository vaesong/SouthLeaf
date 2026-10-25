# C3 implementation check — 2026-10-24

Status: in_progress. This task implements source and static/unit checks only. No database, credentials, grant, migration execution, service control, public fixture, production, commit or push operation occurred.

## Planning and boundary

- User directed accelerated stage-two progress while C2 stays `BLOCKED_PUBLIC_REGRESSION`; C3 was created as `10-24-s1c3-account-lifecycle` and its PRD/design/implement/context manifests validate.
- C3 remains a non-Web independent CLI design. Its source/static progression neither completes C2 nor authorizes HTTP/admin-web.

## Source increment and actual checks

- Added core lifecycle command/result/store abstractions, creation/reset password policy, secret command duplicate rejection and canonical non-secret command fingerprints.
- Added V4 migration source for command facts and the singleton bootstrap guard, plus an exact separate CLI grant policy. V1–V3 were not edited and V4 was not executed.
- j34 first core test invocation failed only for Spotless; j35 formatted the seven new Java files. j36 exposed an NPE caused by probing `Set.of(...).contains(null)`; fixed with a null-safe stream predicate. j38 then passed core: 65 tests, 0 failures/errors/skips.
- j39 passed format/db-policy then exposed a migration test incorrectly expecting the filename token `V4` inside SQL; test corrected. j40 passed db-policy: 71 tests and admin-migration-cli: 15 tests, all 0 failures/errors/skips.

## CLI source increment and actual checks

- Added `admin-account-cli` to the reactor. It has an explicit `AccountCliMain`; strict action/flag parser rejects password/secret argv, unknown/duplicate flags and positional passwords; only the console-based, twice-confirmed secret reader is accepted for bootstrap/change-password.
- The separate environment namespace validates canonical TLS URL/schema, expected identity and exact CLI grants before an account password is requested. It uses a one-connection verified Hikari pool and never imports or expands runtime credentials.
- Added a JDBC `AccountLifecycleStore`: every mutation locks the singleton guard before the target account, persists non-secret command result/idempotency facts, keeps password-command payload fingerprints null, blocks operations before bootstrap, enforces expected version and prevents removal/disable of the last enabled maintainer.
- Updated reactor graph and artifact scanner. j43/j44 and the final j45 re-run passed `mvnw.cmd -pl admin-account-cli -am test`: core 65, crypto 12, db-policy 71 and CLI 7 tests, all 0 failures/errors/skips. CLI tests include an actual Java child-process negative test that rejects a `--password` argv without DB configuration or secret echo. Packaging and `python scripts/check-module-boundaries.py` passed (8 declared modules; 28 CLI artifact entries scanned; 2 negative self-tests). j49 reran the scanner after explicit public/runtime/migration/crypto exclusions for the CLI package were added and passed. j46 ran the repository-root markdown/link check (131 files, 587 links, 0 errors) and scoped `git diff --check` with exit 0; its D-201 LF→CRLF message is a Git normalization warning only.
- Added a JDBC-proxy verifier unit test that asserts the exact `DATABASE()` → `CURRENT_USER()` → `CURRENT_ROLE()` → `@@GLOBAL.mandatory_roles` → `SHOW GRANTS FOR CURRENT_USER()` sequence, accepts only the dedicated schema/identity/no-role/exact-grant set, and rejects schema/identity/role/mandatory-role/extra-rights cases without a database. The first fake-proxy implementation exposed two test-harness defects (void `close()` handling and unwrapped grant-policy rejection) in j51/j52; both were corrected. j53 then passed core 65, crypto 12, db-policy 71 and CLI 9 tests with 0 failures/errors/skips. j56 added the ordering check that an invalid configuration rejects before password prompting/database verification; j58 ran the extended verifier checks and passed core 65, crypto 12, db-policy 71 and CLI 10, all 0 failures/errors/skips. The writer transaction isolation now matches the review contract at `READ_COMMITTED`; the singleton guard remains the first write lock.
- These checks were source-only. The verifier/store were never invoked with usable configuration, so no DB connection, schema/migration execution, account/grant change, service control, public fixture or production interaction occurred.

## Still required

- Drafted `research/isolated-mysql-authorization-plan.md`: a non-executable minimum-authorization and S1C-03..07 runbook. It does not grant or perform any database action; only a future explicit user/owner authorization can move these L3/L4 checks out of `NOT_RUN`.
- Separately authorized isolated MySQL identity/schema/grants and D-703 S1C-03..07: real TLS/identity/grant negative tests, bootstrap/idempotency/version/last-maintainer transaction and concurrency cases.
- Review and execute the offline recovery SOP only under its future operational authorization. C3 remains non-Web; S1-D/admin-web are not started by this work.
- C2 remains `BLOCKED_PUBLIC_REGRESSION`; C3 source/static success is not C2/S1/production acceptance.

Post-update j41: 131 Markdown files / 587 local links / 0 errors; scoped `git diff --check` exits 0. The sole LF→CRLF message is Git normalization notice for D-201, not a whitespace error.

## Superseding authorized isolated L3/L4 execution — 2026-10-24

The preceding source-only statements describe the pre-authorization checkpoint and are superseded for this one explicitly authorized execution. The user then authorized only the disposable C3 fixture described in `research/isolated-mysql-authorization-plan.md`; no C2 public fixture, original service, production system, real customer data or notification path was used.

- j78 ran `python scripts/verify-account-cli-isolated.py --confirm-isolated-c3-provisioning-and-run`. The runner created a random, owned loopback MySQL datadir/port/schema and independent C3 migration/CLI/runtime identities; it only read C2 private-test public trust and existing TLS server material, wrote no new private key or plaintext password, explicitly migrated V2–V4, and deleted the owned fixture at completion.
- The opt-in `c3-isolated-it` Failsafe profile executed `AccountLifecycleRealIT`: 1 completed, 0 failures, 0 errors and 0 skipped. The real fixture test covered concurrent singleton bootstrap, password/permission version transitions, canonical non-secret idempotent retry, stale-version rejection, secret retry/result-query separation, and the last-enabled-maintainer guard.
- Machine evidence is `docs/开发与演示/证据/stage-two-s1c3-isolated-it.json`: `status=PASS`, `originalServicesChanged=false`, `publicFixtureChanged=false`, `productionAccessed=false`, `c2FixtureModified=false`, and `ownedFixtureRemoved=true`. The Failsafe summary independently records the same zero-error result.
- This closes only the authorized C3 non-Web L3/L4 lifecycle check. It does not complete C2, all of S1, HTTP/admin-web, public/native-real-time contracts, production acceptance, commit or push. C2 remains `BLOCKED_PUBLIC_REGRESSION` until its existing public fixture is restored by its owner and the unchanged full gate can be run.
