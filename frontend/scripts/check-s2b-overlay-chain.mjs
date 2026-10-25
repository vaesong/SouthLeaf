// S2-B: real mini-program read chain (api/public.js + wx.request shim) against a live public API.
// Expectation is explicit: "visible" (work + media served) or "gone" (resource unavailable).
import assert from "node:assert/strict";
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const base = process.env.SOUTHLEAF_API_ORIGIN;
const workId = process.env.SOUTHLEAF_S2B_WORK_ID;
const expect = process.env.SOUTHLEAF_S2B_EXPECT;
const title = process.env.SOUTHLEAF_S2B_TITLE;
if (!base || !workId || !["visible", "gone"].includes(expect))
  throw new Error(
    "Explicit SOUTHLEAF_API_ORIGIN, SOUTHLEAF_S2B_WORK_ID and SOUTHLEAF_S2B_EXPECT required",
  );
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
if (expect === "visible") {
  const work = await api.getWork(workId);
  assert.equal(work.id, workId);
  if (title) assert.equal(work.title, title);
  assert.ok(work.media.length >= 1);
  const list = await api.getWorks({ limit: 20, sort: "newest" });
  assert.ok(list.items.some((item) => item.id === workId));
  const media = await api.getMedia(work.media[0].id);
  assert.ok(media.src.startsWith(base));
  const bytes = await fetch(media.src);
  assert.equal(bytes.status, 200);
  console.log(`S2B_MINIPROGRAM_CHAIN_PASS:visible:media=${work.media.length}`);
} else {
  await assert.rejects(
    api.getWork(workId),
    (e) => e.code === "PUBLIC_RESOURCE_UNAVAILABLE",
  );
  const list = await api.getWorks({ limit: 50, sort: "newest" });
  assert.ok(!list.items.some((item) => item.id === workId));
  console.log("S2B_MINIPROGRAM_CHAIN_PASS:gone");
}
