# Database Guidelines

Current scope: MySQL stage-one public read model. D-502/503/504 and AT10 are authoritative.

- Spring JDBC adapters only; no JPA/CRUD, JSON/Empty storage or legacy profile.
- Every request confirms the active publication. Repeatable-read immutable snapshots may be cached by release/version, never used to mask a database failure.
- Current scoped authorization is a separate two-query batch, not N+1 or cached business content. Include release identity as well as exact candidate/binding/media/subject/purpose/channel/time.
- Public runtime is direct schema SELECT-only, encrypted and non-root. Startup verifies actual grants/cipher; production requires VERIFY_IDENTITY. Roles, global SELECT, write privileges and GRANT OPTION fail explicitly.
- The schema verifier validates Flyway and requires an existing applied migration; no migrate/repair/seed/clean at runtime.
- Independent migration credentials and explicit V2+ scripts. Applied V1 is immutable. Demo import is separate and refuses an existing catalog_release.
- New publication and current facts are prepared/reviewed before one transaction switches the pointer. Respect authorization parent/child foreign keys; never disable them for publishing.
- Isolated integration tests may mutate their fixture schema and restore changes. Their startup account guard is mocked only in test source; separate real Reader/writer tests exercise the actual guard. There is no production bypass flag.
- Backup SQL plus referenced immutable media and checksums. Test-BackupRestore is explicitly destructive only to the fixed test schema and requires confirmation. Local success is not production disaster-recovery acceptance.

## Management S1-B

Management runtime has the explicit D-506 four-table/six-privilege matrix, not the public Reader's schema-wide SELECT rule. No account UPDATE, global/schema/column extras, roles, grant option or history-table access. Verify actual identity/TLS and complete grants before publishing the Store. Test SQL under that exact identity as well as parser/control flow; FOR UPDATE on a SELECT-only account table was a real incompatibility and is now FOR SHARE, with real race tests. Explicit independent test instances must not change the existing server's accounts, global roles or TLS. Local test certificates/production mode do not establish production acceptance.
