import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import {
  resolveBuildConfig,
  approvedTabAssets,
} from "../scripts/build-config.mjs";
const valid = {
  mode: "release",
  deployment: {
    apiOrigin: "https://public.example.com",
    appId: "wx0123456789abcdef",
  },
};
test("release uses only explicit structured native configuration", () => {
  assert.deepEqual(resolveBuildConfig(valid), {
    appId: valid.deployment.appId,
    runtime: { apiOrigin: valid.deployment.apiOrigin, release: true },
  });
});
for (const apiOrigin of [
  "http://public.example.com",
  "https://localhost",
  "https://127.0.0.1",
  "https://[::1]",
  "https://192.168.1.2",
  "https://foo.local",
  "https://u:p@public.example.com",
  "https://public.example.com/path",
  "https://public.example.com?x=1",
  "https://public.example.com#x",
])
  test("release rejects unsafe or non-origin config: " + apiOrigin, () => {
    assert.throws(() =>
      resolveBuildConfig({
        ...valid,
        deployment: { ...valid.deployment, apiOrigin },
      }),
    );
  });
for (const appId of ["", 123, "touristappid", "wx-bad"])
  test("release rejects missing or invalid native AppID: " + appId, () => {
    assert.throws(() =>
      resolveBuildConfig({
        ...valid,
        deployment: { ...valid.deployment, appId },
      }),
    );
  });
test("no implicit build mode or implicit API endpoint", () => {
  assert.throws(() => resolveBuildConfig({}));
  assert.throws(() => resolveBuildConfig({ mode: "demo" }));
  assert.throws(() => resolveBuildConfig({ ...valid, mode: "other" }));
});
test("guest AppID is explicitly demo only, release cannot import demo origin", () => {
  assert.equal(
    resolveBuildConfig({ mode: "demo", apiOrigin: "http://127.0.0.1:8090" })
      .appId,
    "touristappid",
  );
  assert.throws(() =>
    resolveBuildConfig({
      mode: "release",
      apiOrigin: "https://public.example.com",
      deployment: { appId: valid.deployment.appId },
    }),
  );
});
test("six routes and eight exact approved assets fail closed", () => {
  const app = JSON.parse(
    fs.readFileSync(new URL("../app.json", import.meta.url)),
  );
  assert.equal(approvedTabAssets(app).size, 8);
  app.tabBar.list[0].iconPath = "static/private.jpg";
  assert.throws(() => approvedTabAssets(app));
  assert.throws(() => approvedTabAssets({}));
});
