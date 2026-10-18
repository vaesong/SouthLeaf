# Backend Error Handling

- Application errors are PublicQueryException codes, not HTTP dependencies. HTTP status/envelope mapping belongs to GlobalExceptionHandler/controllers.
- Invalid query/cursor syntax or query mismatch: 400; expired publication/anchor cursor: 409 PUBLIC_CURSOR_EXPIRED; non-public or absent resource: 404; storage/current-fact dependency outage: unavailable, never a successful empty catalog.
- An explicit unknown/revoked/out-of-scope fact denies access. A failed/incomplete authority batch is a dependency failure. Do not conflate these states.
- Media authorization/path/byte integrity failures cannot fall back to another URL, original image or generated placeholder.
- Successful Meta and nullable ErrorMeta follow the OpenAPI contract. Do not leak SQL, storage paths, credentials, evidence bodies or internal exception messages to clients.
- Log request correlation/classification at the HTTP boundary. Adapters translate only expected database/transaction/file failures; unexpected programming errors remain failures.
- No broad retry. The client restarts a page exactly once only for explicit cursor expiry, clearing old items first.
