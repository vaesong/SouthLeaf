# Implementation design

> Historical design, superseded by the database-only AT10 architecture and current delivery record. Do not reintroduce JSON/legacy adapters or side-by-side compatibility from this text.

Use the existing port/adaptor architecture. PublicCatalogSource holds validated catalog snapshots; optional file resources are re-read before requests, with SHA-256 content versions and no last-good fallback on invalid updates. Demo data is bundled and explicitly labelled. Production refuses unapproved/demo-image catalogs.

PublicMediaReader remains the metadata port; PublicMediaContentReader returns bounded private bytes. The JSON media adapter resolves server-owned bindings and rechecks eligibility before metadata and after loading bytes. DemoArtRenderer supplies original geometric PNGs, not customer photos. Approved local PNG/JPEG files may be supplied through a configured private media root; remote URLs and traversal are disallowed.

C1 adds a side-by-side typed scope/result contract and pure predicate, without pretending the legacy demo authorization adapter is a production authority. Actual scope/currentness and concurrency remain C2/C3.

Frontend shares request, controlled URL and lifecycle helpers. Demo badges follow server metadata; release builds require HTTPS/AppID and reject non-production metadata. Existing public paths remain stable; byte routes and meta.dataMode are additive. All public JSON is currently no-store, trading performance for simple consistent demonstration updates.
