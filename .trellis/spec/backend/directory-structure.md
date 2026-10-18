# Backend Directory Structure

> Current database-only stage-one architecture. AT10 and the final stage-one delivery record describe the implementation.

## Layout

```
backend/src/main/java/cn/zhinan/uniapp/
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
backend/src/main/resources/db/migration/ Versioned schema only
backend/src/main/resources/db/demo/      Explicit fictional import only
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
