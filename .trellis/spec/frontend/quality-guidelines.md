# Frontend Quality Guidelines

- Native App/Page/Component only; no permanent parallel Vue/uni-app runtime. No booking/admin/payment/private delivery expansion in stage one.
- Run `npm ci --ignore-scripts`, `npm run verify`, official `npm audit`, explicit demo build, live `test:contract`, and one complete simulator regression.
- Set `SOUTHLEAF_API_ORIGIN` explicitly. Release reads structured `config/release.json`, requires public HTTPS and explicit WeChat AppID; demo defaults neither endpoint nor genuine platform identity. Syntax validation is not ownership verification.
- Prettier formats JS/MJS/JSON/WXSS/WXML; WXML uses HTML formatting, WXSS CSS formatting. No TypeScript/compiler/SAST completeness claim.
- Native WXML expressions must not contain HTML-escaped logical operators or JavaScript unicode escapes. Different events on one element use distinct dataset keys; duplicate attributes are forbidden. Preserve these regression tests.
- Only eight declared static PNGs may enter the build; reject unexpected assets rather than silently deleting them after copying. Quarantined rights-unknown assets are never runtime content.
- Node build validates syntax/module closure, not the WeChat compiler. A clean source-only repeat-build test is required; simulator compile/UI validation remains separate.
- Error paths cannot show fixture data as successful business content. Explicit cursor expiry permits one fresh read; no arbitrary retries.
- Official npm audit covers the current lockfile only, not DevTools, Maven plugins or full supply-chain security. Do not force versions merely to produce a zero count.
- Real content, real authorization evidence and production/device release acceptance are stage-two responsibilities. Preserve demo labels and safety guards.

## admin-web

- Visual template is `docs/设计笔记集/后台界面设计稿-v2.html`; reuse the tokens and classes in `admin-web/src/style.css` and `src/components/` instead of new styles or UI libraries. Element Plus is removed; do not reintroduce it.
- All UI copy is Simplified Chinese written for the studio owner. Templates must not expose IDs/UUIDs, revision numbers, English enums, ISO timestamps, time-zone strings or words like 接口/服务器/会话. Format through `src/content/format.ts` (Beijing time; unknown enums render as “其他”).
- Never invent data the admin API does not return; omit it or state a real limitation once in a `.note`.
- `*/api.ts` transport, CSRF, If-Match, Idempotency-Key reuse and error classification are frozen; feature work may only add pure helpers or change user-facing strings.
- `npm run dev:mock` serves fixtures from `admin-web/mock/` only when `mode === "mock"`; production `dist` must contain no fixture strings (check `MOCKFIX`).
