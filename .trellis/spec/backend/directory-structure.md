# Backend Directory Structure

> Current S1-A reactor + S1-B verified non-Web bootstrap, including local isolated Writer acceptance. Public behavior remains the stage-one baseline; C1 pure credential core/crypto is implemented; JDBC credential wiring, lifecycle CLI, HTTP/login and production acceptance remain pending.

## Layout

```
backend/public-api/src/main/java/cn/zhinan/uniapp/
  controller/                 Thin public JSON and controlled media HTTP adapters
  dto/PublicDtos.java         New public response records, never SQL entities/storage keys
  publicquery/domain/         Immutable CatalogSnapshot publication aggregate
  publicquery/port/           PublishedCatalogRepository and explicit storage failure
  publicquery/application/    Snapshot reads, projections, strict versioned cursors
  publicquery/contract/       Shared plain request rules and public read contracts
  publicquery/adapter/jdbc/   Relational publication reader and immutable bounded cache
  authorization/port/        Batch current scoped facts; no persistence dependency
  authorization/adapter/jdbc/ Two-query repeatable-read fact batch
  works/domain/              Pure scoped authorization predicate
  works/application/         Visibility policy, fail closed versus explicit dependency failure
  media/port/                Prepared object-store boundary
  media/application/         Buffered reads and final qualification recheck
  media/adapter/filesystem/  Private canonical-root/size/SHA validation
  config/                   Runtime wiring and fail-fast schema/publication validation
  tooling/                  Explicit migration/demo preparation, not web beans
backend/public-api/src/main/resources/db/migration/ Versioned schema only
backend/public-api/src/main/resources/db/demo/      Explicit fictional import only
```

## Rules

- No JPA/CRUD, legacy profile, JSON/Empty source, automatic seed, repair or runtime fallback.
- Domain and ports do not import Spring, Web, JDBC or filesystem implementations.
- Current DB publication pointer is read for every query; one result owns data and metadata.
- Authorization is fresh, batched and scoped. Missing/revoked facts deny; dependency failure is 503, never a successful empty catalog.
- Immutable publication updates use a new release and atomic pointer switch; do not mutate cached release rows.
- Malformed/query-mismatched cursors are 400; publication/anchor expiry is 409. Never silently return an empty continuation.
- Runtime account is SELECT-only. Migrations and explicit fictional imports use separate processes/credentials. Applied V1 is immutable; use V2+.
- Media is prepared privately, bound, checked and requalified after buffering. No remote image fallback, raw originals or public storage keys.
- `mvnw.cmd test` runs pure tests; `scripts/Test-DatabaseIntegration.ps1` runs verify against the mandatory isolated real MySQL database. No H2 or skipped substitute.
- Spotless is bound to validate. Preserve UTF-8 and repository line endings; refresh SHA after formatter writes.
- No automated test establishes genuine consent, external authority ownership or production acceptance.

Application services are plain Java; config wires them. Startup enforces actual direct SELECT-only grants/TLS; only test source can replace the guard for mutable fixtures. Scoped facts include release identity.

## Stage-two management security increment

- `adminsecurity/domain`, `port`, and `application` remain plain Java. Crypto/JDBC live in adapters; there are no automatically discovered management beans or endpoints yet.
- `JdbcAdminSessionStore` binds JDBC and its transaction manager to the same explicit writer DataSource. Issue locks the account and rechecks the verified security version; issue/revoke audit commits atomically. Reads fetch current account and permissions in one SQL statement.
- Production management security belongs in a separate schema: public schema-wide SELECT must never expose staff credentials. The independent `db/admin-migration` history is not scanned by the public migration runner. Never alter applied public V1 or applied management V2.
- Existing isolated `_it` schema hosts management fixtures only for tests; this is not proof of production cross-schema grants. No truncate/reset/clean. Fixture cleanup is by test-owned IDs.
- `AdminDatabaseMigrationMain` is explicit deployment tooling, not a runtime auto-migrator. No automatic baseline/seed/root/weak TLS, no credentials in URLs.
- `AdminSessionStoreIT` requires real MySQL, tests transaction/audit rollback and lock-controlled reset/issuance races. In-memory unit doubles are not production adapters.
- Session UTC values are truncated to microseconds for DATETIME(6). Authentication does not replace resource/state checks or transaction-time revalidation for sensitive business commands.
- Runtime Writer guard is implemented and locally verified, including real isolated MySQL grants and Store operations. C1 credential verification is an unwired pure use case plus independent crypto adapter; JDBC credential/event wiring, lifecycle CLI, HTTP/CSRF and admin UI remain future slices.

## S1-A module and artifact isolation (implemented)

- `backend/pom.xml` is a dependency-free aggregator; retained BOM/plugin versions live there. Spotless is managed in the parent and activated in children, not run over legacy parent output.
- `public-api` keeps `cn.zhinan.uniapp.*` and `uniapp-api` artifact. It has no management dependencies/classes/resources. Public runtime/migration scripts explicitly select this module.
- `admin-security-core` owns `cn.zhinan.adminsecurity.domain/application/port` with JDK-only main dependencies; `admin-security-jdbc` owns the explicit JDBC adapter.
- `admin-db-policy` contains pure strict migration URL/target policy; `admin-migration-cli` owns explicit tooling + the single V2 SQL source. No runtime auto-migration.
- `admin-runtime` owns `cn.zhinan.adminruntime`, production token codec and S1-B explicit verified non-Web composition. Configuration validation precedes Boot; Store creation follows owned-pool verification. No raw DataSource Bean or component scan. HTTP remains S1-D.
- Core/JDBC tests cannot depend on runtime even with test scope (reactor cycle). Small deterministic test-only codecs exercise ports; production codec tests stay in runtime. JDBC migration resources use test-scoped CLI jar, never a copied SQL fixture.
- `Test-DatabaseIntegration.ps1` runs reactor verify followed by `check-module-boundaries.py`: full declared graph including test edges, jdeps pure jars and recursively scanned final Boot artifacts. Do not substitute source-import scans alone.
- Preserve original runtime `backend/target/classes` until the user authorizes service replacement; new builds use child target directories. Never clean an actively served output tree.
- Original tests are tracked by suite/case through migration; when architecture tests change, document replacement assertions, not just total count. Applied V1/V2 remain byte-identical.
- `stage-one-files.mjs` intentionally refuses stage-two layout. Do not regenerate the stage-one manifest to disguise source changes.

Implementation evidence: [S1-A guide](../../../docs/开发与演示/阶段二S1-A模块迁移与验证指南.md). Independent management grants/login/production acceptance are still not complete.

## S1-B configuration and ownership boundary

Only six explicit SOUTHLEAF_ADMIN_* process variables feed immutable/redacted properties; no Spring binding or Reader fallback. Remove default system/environment Spring property sources before Config Data; pin classpath admin configuration, reject external Spring/profile/logging/CLI overrides, and test poisoned working-directory configuration against the real packaged process.

The package-private VerifiedAdminDataSource owns the pool, verifies fixed metadata/grants/roles queries, then builds JdbcAdminSessionStore with the exact DataSource it inspected. Never add a public constructor/raw-pool getter or environment guard bypass. Package-only pool factory seams are for strict JDBC metadata doubles in test source. Close on validation failure, later context failure and normal shutdown.

AdminJdbcUrl contains shared shape parsing only; migration always retains VERIFY_IDENTITY, while runtime policy separately limits explicit demo/production targets and canonical values. Runtime grants are exact four-table/six-privilege sets, no roles/global/schema/column/unknown extras. SQL additions require an explicit matrix/version review, not a permissive parser.

## Actual SQL must work under the declared minimum grant set

Account reads during issuance use FOR SHARE, not FOR UPDATE: actual MySQL 8.4 rejected the latter for the SELECT-only account table. Keep the account read-only matrix; do not widen grants to make a test pass. Shared account locks block reset/disable writes until issue/audit commit while allowing independent concurrent issuances. Session revocation retains FOR UPDATE and its existing UPDATE privilege. The real JDBC IT includes both mutation orderings and a two-issuer post-lock barrier; the independent runtime probe also executes the actual Store under exact grants.

## S1-C1 credential boundary

`admin-credential-crypto` is the seventh reactor module, a plain jar implementing core PasswordVerifier/PasswordHasher ports with maintained Argon2id. It has no JDBC/Web/runtime dependency. `admin-security-core` adds AdminLoginService and immutable credential/event facts but remains JDK-only. Runtime does not yet depend on crypto or instantiate login: durable CredentialLookup/AuthenticationEventSink and versioned grants must be delivered in C2 before wiring. Public artifacts must never contain crypto/provider/admin classes. Benchmark and test doubles remain test-source only.

Executable contracts, limits and error cases: [credential guidelines](credential-guidelines.md).
