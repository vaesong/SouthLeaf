# Implementation and validation

1. Add demo catalog and configurable snapshot validation.
2. Add private media byte port, guarded routes and synthetic demo art.
3. Add C1 scope contract/pure rules; do not silently switch the production authorization model.
4. Wire frontend gateway mapping, demo notice, share/contact and lifecycle guards.
5. Run Maven suite, Node tests, demo build, negative release checks, live API and PNG checks.
6. Record evidence and operational limitations; do not mark device/production acceptance complete.

7. User-approved display refinement: unified configurable contact channels on About; quiet works layout control; grouped service directory; curated homepage; no detail contact CTAs; album/overview gallery with distinct original demo art.
8. Regressions: j94 backend 37, j95 frontend 14 + demo build; j98 live smoke 196 assertions; j109 six simulator routes plus two layout tap/callback screenshots. Native action sheet selection callback is mocked; native image viewer / real device not accepted.

9. Third refinement: configurable photographer/makeup team profiles and owned portraits; owned QR media + account + direct copy, with explicit non-scannable demo placeholders. One-tap view cycling replaces ActionSheet entirely; sorting UI remains absent.
10. j111 backend 42; j112 frontend 17 + demo build; j113 smoke 230 assertions; j114 simulator six routes and complete direct tap cycles, no mock. Actual QR scanning / staff identity / real-device acceptance remain pending.

11. Fourth refinement restores compact newest/oldest sorting (not a standalone toolbar), preserving direct layout cycling and resetting pagination safely. Team presentation becomes vertical portrait/copy rows rather than two-column cards.
12. j116 frontend 19 + demo build; j124 About targeted simulator PASS; j125 Works targeted simulator PASS including real sort selection/date order and layout cycles; j126 smoke 230. Backend unchanged; latest Maven evidence remains j111 (42). Full-run timeouts are not full-run PASS evidence.
