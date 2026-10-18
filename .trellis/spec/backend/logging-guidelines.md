# Backend Logging Guidelines

- Correlate public requests with server requestId and stable error category; public responses expose no internal stack/SQL/path.
- Never log passwords, credential objects, connection URLs containing credentials, customer evidence, full private payloads or media bytes. SQL parameters are not diagnostic free text.
- Do not print secrets from scripts. Credentials go through system storage and process-local environment; remove temporary secret variables after use.
- Migration/startup/backup/SCA commands must return a nonzero failure rather than a reassuring success label on an incomplete operation.
- Record suite results, scope, artifact hash/versions, registry/advisory source and remaining gates in delivery evidence. A fixture authorization is not real customer consent.
- Current logging is not a complete tamper-proof audit, metrics platform or production retention policy; these require explicit operations acceptance.
