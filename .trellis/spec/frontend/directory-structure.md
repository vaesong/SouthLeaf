# Frontend Directory Structure

```text
frontend/
  app.js / app.json / app.wxss   Native application and four-tab routing
  pages/                        Four tabs plus two detail pages; JS/JSON/WXML/WXSS
  components/demo-notice/       Server-derived demo indicator
  api/api.js                    wx.request, strict envelope/error/HTTP checks
  api/public.js                 Runtime DTO and controlled media URL validation
  api/public-display.js         Pure price/layout/group helpers and guarded contact actions
  presentation/                 Native setData commit and display-only derived fields
  config/release.json           Non-secret deployment configuration, empty by default
  scripts/                      Explicit native demo/release build and live API check
  tests/                        Node unit, lifecycle, source and clean-build checks
  design/                       SVG source; not automatically copied to app
  static/icons/                 Eight declared tab PNGs only
  dist/                         Generated, ignored; import dist/build/mp-weixin in DevTools
```

No Vue, uni-app, Vite, old HBuilder entry or node runtime package is shipped. Pages use the shared API adapter rather than direct network calls or embedded business records. Backend facts are authoritative; client presentation never grants authorization. Backend build directories and old unpackage output are not source entries.

Build stage validates declared files/dependencies and eight static assets before replacing generated output children. On Windows it retains the output directory itself because DevTools holds it open. This local build operation is not an atomic production deployment protocol.
