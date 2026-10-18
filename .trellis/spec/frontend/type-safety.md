# Frontend Data Boundary and Type Safety

- JavaScript source currently uses explicit runtime validation in api/api.js and api/public.js. There is no TypeScript compiler gate; do not claim one.
- Validate HTTP/envelope agreement, metadata/data mode, required DTO types, enum values, paging and controlled gateway media URLs before updating page state.
- New DTO fields are sourced from the server. Do not accept old prices, standalone WeChat contacts, old detail parameters or business fallback values.
- Optional/nullable fields follow the current OpenAPI, not arbitrary truthiness. A malformed response produces an explicit error, never fixture data.
- Plain validated view objects isolate page templates from transport shape. Display-only helpers do not decide authorization or fabricate availability.
- Test the actual transport and mappers against the live DB service separately from mocked network tests.
