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
