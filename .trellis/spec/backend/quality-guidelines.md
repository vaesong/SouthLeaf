# Backend Quality Guidelines

- Keep domain/application/ports plain Java, with no Spring, Web, JDBC or filesystem implementation dependencies. Config is the framework composition root; adapters own technical concerns.
- No compatibility branches, placeholder adapters, catch-all successful empty responses, stale authorization cache or automatic data repair.
- Use immutable records/defensive copies. Bound publication sizes and media reads. Keep data/meta on one snapshot and bind cursors to query/version.
- Catch only expected technical exceptions at adapters; preserve failure versus denied/unknown authorization. Never swallow an arbitrary RuntimeException as data absence.
- Maven validate enforces Spotless. Verify includes pure rules/architecture tests and mandatory real MySQL tests; no H2/skip substitution.
- After formatter changes, refresh read/SHA before edits. Preserve UTF-8 and checked-out line endings.
- Re-run unit, database, startup negative, live HTTP and actual frontend contract checks after functional backend changes. Simulator/device evidence is separate.
- Query resolved runtime advisories with the checked-in OSV script; query failure or a stale/empty inventory is not a clean result. This scan excludes build plugins and does not prove exploitability or complete supply-chain safety.
- Report known vulnerabilities, untested gates and fixture limitations. Do not replace acceptance with a plan checkbox or count of tests.

## Multi-module checks

Use `backend/scripts/Test-DatabaseIntegration.ps1` for the full reactor plus artifact proof. `mvnw.cmd test` is only the unit layer; database fixtures require verify. Keep test-scoped edges in cycle analysis and inspect nested Boot dependencies, not only direct jar entries. After moving SQL/resources, verify byte hashes against the recorded pre-migration baseline. Candidate HTTP tests must target the newly built artifact/port; success against an old running instance is not new-build evidence. Candidate processes must be stopped without touching pre-existing jobs.

## S1-B evidence levels

`python backend/scripts/test-admin-runtime-guards.py` checks the real packaged process with synthetic negative-only credentials, isolated child environments, poisoned external config files and a bound-but-not-listening local port. It must assert exact sanitized output and nonzero exit; never dump raw output on failure. Observe only candidate PIDs and clean up only those children.

Metadata double/context tests prove control flow, not actual MySQL grants. Historical shared-schema MySQL IT cases do not by themselves close isolated Writer acceptance. Keep the real management positive/negative gate visibly BLOCKED until separately authorized schema/users/grants/TLS exist. Never change existing global mandatory_roles or TLS settings to manufacture a negative case; use an explicitly approved independent test environment if needed.

## Windows isolated verification lessons

MySQL on Windows defaults to a monitor/worker pair. The explicit isolated harness uses the built-in --no-monitor flag so the process handle, listener PID and cleanup owner match; never weaken a listener assertion or kill an unrelated server. Verify unique UUID/datadir before provisioning and normal SQL SHUTDOWN afterwards.

A private Java console allows a real Ctrl+C/shutdown-hook test without touching an existing console. After detaching, inherited Windows stdin handles may be invalid: child commands without input use DEVNULL explicitly, commands carrying SQL use private pipes. Capture only stable results/error codes/output hashes; never dump raw credential/SQL output. Keep partial failure evidence distinct from a completed PASS.

## Credential and advisory evidence

Scan the crypto module's resolved runtime inventory separately; public-api's 52-package result does not cover added KDF/provider libraries. The scanner accepts only an explicit module allowlist and excludes private reactor coordinates from registry queries. Invalidate the previous selected-module report before reading/validating/querying; a transient network failure must not leave stale clean evidence for collectors. Four deterministic Node tests cover transport failure, stale inventory, incomplete response and successful replacement. The C1 collector also checks report freshness against inventory and scanner source.

Spring crypto's standalone artifact requires explicit spring-core for used utility/logging classes; test with the actual resolved runtime, not only a compile or a search result. KDF benchmark samples are engineering observations, not production capacity or exact RSS. Follow [credential contracts](credential-guidelines.md); never claim all plaintext copies can be erased or both supported profiles have identical latency.


## C2 harness lifecycle and evidence

After a validated owned manifest starts a new attempt, invalidate prior latest PASS/partial reports. Every step after server start, including migrations and grants, is inside cleanup coverage. Retain primary and cleanup classifications; publish PASS only after actual successful shutdown. Use atomic reports with run/source/candidate/probe binding; arbitrary exception/SQL/child output is not evidence.

`verify --with-reactor` builds/verifies BEFORE candidate pinning, via the full PowerShell entry with explicit owner-supplied SECURITY_IT environment. The entry validates inputs before DPAPI/Maven; `-ValidateOnly` has no connection/credential-store access and is not real identity/TLS proof. Never restore a 3306 management test fallback. Pin immutable JAR/classes/libraries/probes for all packaged checks and reject subsequent source/artifact drift; no mid-probe install or rebuild.

Run `python backend/scripts/test-verify-admin-isolated.py` for deterministic no-DB regressions. They and metadata tests do not close real MySQL/grants acceptance. New IT may be compiled in `test` without executing Failsafe; say so explicitly. A separate package with -DskipTests is preparation only after actual unit execution, never replacement test evidence. Spotless spotlessFiles is a regex, not a glob; target explicit files, then refresh read/SHA before further edits.
