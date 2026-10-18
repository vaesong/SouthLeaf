import assert from "node:assert/strict";
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const base = process.env.SOUTHLEAF_API_ORIGIN;
if (!base) throw new Error("Explicit SOUTHLEAF_API_ORIGIN required");
globalThis.getApp = () => ({ runtime: { apiOrigin: base, release: false } });
globalThis.wx = {
  async request(options) {
    try {
      const response = await fetch(options.url, {
        signal: AbortSignal.timeout(options.timeout),
      });
      options.success({
        statusCode: response.status,
        data: await response.json(),
      });
    } catch (error) {
      options.fail({
        errMsg: error.name === "TimeoutError" ? "timeout" : error.message,
      });
    }
  },
};
const api = require("../api/public.js");
const home = await api.getHome();
assert.equal(home.businesses.length, 3);
assert.equal(home.packages.length, 0);
const brand = await api.getBrand();
assert.equal(brand.team.length, 2);
assert.equal(brand.contact.channels.length, 3);
assert.ok(brand.shareTitle);
const businesses = await api.getBusinesses();
assert.equal(businesses.items.length, 6);
const first = await api.getWorks({ limit: 2, sort: "newest" });
assert.equal(first.items.length, 2);
assert.ok(first.nextCursor);
const next = await api.getWorks({
  limit: 2,
  sort: "newest",
  cursor: first.nextCursor,
});
assert.equal(next.items.length, 2);
assert.equal(new Set([...first.items, ...next.items].map((v) => v.id)).size, 4);
const work = await api.getWork(first.items[0].id);
assert.equal(work.media.length, 3);
const business = await api.getBusiness(businesses.items[0].id);
assert.equal(business.packages.length, 1);
const packages = await api.getPackages({ limit: 50 });
assert.equal(packages.items.length, 6);
const pkg = await api.getPackage(packages.items[0].id);
assert.ok(pkg.serviceItems.length);
const media = await api.getMedia(work.media[0].id);
assert.ok(media.src.startsWith(base));
await assert.rejects(
  api.getWork("missing"),
  (e) => e.code === "PUBLIC_RESOURCE_UNAVAILABLE",
);
console.log(
  "LIVE_NATIVE_CONTRACT_PASS successCalls=10 uniqueOperations=9; strict transport/mapping/pagination; database-backed API",
);
