import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
const root = fileURLToPath(new URL("../", import.meta.url));
const sources = [
  "app.js",
  "app.json",
  "app.wxss",
  "sitemap.json",
  "scripts",
  "api",
  "pages",
  "components",
  "presentation",
  "static",
  "config",
];
test("clean source-only build is repeatable and rejects contaminated input without replacing good output", () => {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), "southleaf-build-"));
  try {
    for (const name of sources)
      fs.cpSync(path.join(root, name), path.join(temp, name), {
        recursive: true,
      });
    const build = () =>
      spawnSync(process.execPath, ["scripts/build-native.mjs", "demo"], {
        cwd: temp,
        env: { ...process.env, SOUTHLEAF_API_ORIGIN: "http://127.0.0.1:8090" },
        encoding: "utf8",
      });
    let result = build();
    assert.equal(result.status, 0, result.stderr);
    const output = path.join(temp, "dist/build/mp-weixin");
    const app = fs.readFileSync(path.join(output, "app.js"), "utf8");
    fs.writeFileSync(path.join(output, "stale-vendor.js"), "stale");
    result = build();
    assert.equal(result.status, 0, result.stderr);
    assert.equal(fs.existsSync(path.join(output, "stale-vendor.js")), false);
    fs.writeFileSync(path.join(temp, "static/unknown.jpg"), "unapproved");
    result = build();
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /Undeclared static/);
    assert.equal(fs.readFileSync(path.join(output, "app.js"), "utf8"), app);
    fs.unlinkSync(path.join(temp, "static/unknown.jpg"));
    fs.appendFileSync(
      path.join(temp, "pages/about/about.wxml"),
      '<view wx:if="{{a &amp;&amp; b}}"/>',
    );
    result = build();
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /expression encoding/);
    assert.equal(fs.readFileSync(path.join(output, "app.js"), "utf8"), app);
    assert.equal(
      fs.existsSync(path.join(temp, "dist/build/mp-weixin.staging")),
      false,
    );
  } finally {
    fs.rmSync(temp, { recursive: true, force: true });
  }
});
