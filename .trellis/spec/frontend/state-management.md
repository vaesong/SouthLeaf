# Frontend State Management

- Server data is authoritative per response. Do not persist public catalogs, work image arrays or authorization decisions as local truth.
- Local state owns loading/error/empty/success, selection, paging and view preferences. Global data mode is derived from server metadata, not an override of it.
- Generation guards invalidate hidden/outdated requests. Hide clears media; resume re-reads. Failed refresh does not restore stale imagery.
- Pagination appends only within the same query/publication. Explicit cursor expiry clears the old page and retries once. Arbitrary errors remain visible.
- Sorting resets pagination but keeps category and view. Direct works and detail layout cycles retain independent UI-only preferences; storage failure cannot change visibility.
- Navigation/retry remains available in error states. No local data fallback, automatic multi-retry or hidden success state.
