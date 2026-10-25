import fs from "node:fs/promises";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";
import {
  resolveBuildConfig,
  approvedTabAssets,
  routes,
} from "./build-config.mjs";
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const mode = process.argv[2];
if (process.argv.length !== 3)
  throw new Error("Usage: node scripts/build-native.mjs demo|release");
const deployment =
  mode === "release"
    ? JSON.parse(
        await fs.readFile(path.join(root, "config/release.json"), "utf8"),
      )
    : {};
const config = resolveBuildConfig({
  mode,
  apiOrigin: process.env.SOUTHLEAF_API_ORIGIN,
  deployment,
});
const app = JSON.parse(await fs.readFile(path.join(root, "app.json"), "utf8"));
const assets = approvedTabAssets(app);
// Reject undeclared static files before creating or changing any output.
async function staticFiles(directory, prefix = "static") {
  const result = [];
  for (const entry of await fs.readdir(directory, { withFileTypes: true })) {
    const name = prefix + "/" + entry.name;
    if (entry.isSymbolicLink())
      throw new Error("Static symlinks forbidden: " + name);
    if (entry.isDirectory())
      result.push(
        ...(await staticFiles(path.join(directory, entry.name), name)),
      );
    else if (entry.isFile()) result.push(name);
    else throw new Error("Unexpected static entry: " + name);
  }
  return result;
}
const actualAssets = await staticFiles(path.join(root, "static"));
if (
  actualAssets.length !== assets.size ||
  actualAssets.some((name) => !assets.has(name))
)
  throw new Error(
    "Undeclared static asset; only eight approved tab PNGs are allowed",
  );
const output = path.join(root, "dist/build/mp-weixin");
const stage = output + ".staging";
await fs.rm(stage, { recursive: true, force: true });
await fs.mkdir(stage, { recursive: true });
try {
  const files = [
    "app.js",
    "app.json",
    "app.wxss",
    "sitemap.json",
    ...routes.flatMap((r) =>
      ["js", "json", "wxml", "wxss"].map((e) => r + "." + e),
    ),
    ...["js", "json", "wxml", "wxss"].map(
      (e) => "components/demo-notice/index." + e,
    ),
    ...[
      "api/api.js",
      "api/public.js",
      "api/booking.js",
      "api/public-display.js",
      "presentation/views.js",
      "presentation/page-state.js",
    ],
    ...assets,
  ];
  for (const name of files) {
    const source = path.join(root, name),
      target = path.join(stage, name);
    const stat = await fs.lstat(source);
    if (!stat.isFile() || stat.isSymbolicLink())
      throw new Error("Regular declared source file required: " + name);
    if (name.endsWith(".js")) {
      const text = await fs.readFile(source, "utf8");
      new vm.Script(text, { filename: name });
      if (/\b(?:uni|Vue)\s*\.|__SOUTHLEAF_|@dcloudio|@vue|\$vm/.test(text))
        throw new Error("Non-native runtime source: " + name);
    }
    if (name.endsWith(".json")) JSON.parse(await fs.readFile(source, "utf8"));
    if (name.endsWith(".wxml")) {
      const text = await fs.readFile(source, "utf8");
      for (const expression of text.matchAll(/{{([\s\S]*?)}}/g)) {
        if (
          /&(?:amp|lt|gt);|\\(?:u[0-9a-f]{4}|x[0-9a-f]{2})/i.test(expression[1])
        )
          throw new Error("Non-native expression encoding: " + name);
      }
      if (
        /\bv-(?:if|else|for|bind|on)|@(?:click|change)|<template\b|<DemoNotice\b/.test(
          text,
        )
      )
        throw new Error("Non-native template: " + name);
    }
    if (name.endsWith(".png")) {
      const b = await fs.readFile(source);
      if (b.subarray(0, 8).toString("hex") !== "89504e470d0a1a0a")
        throw new Error("Invalid PNG: " + name);
    }
    await fs.mkdir(path.dirname(target), { recursive: true });
    await fs.copyFile(source, target);
  }
  await fs.mkdir(path.join(stage, "config"), { recursive: true });
  await fs.writeFile(
    path.join(stage, "config/runtime.js"),
    "module.exports=Object.freeze(" + JSON.stringify(config.runtime) + ");\n",
  );
  // Audit every literal CommonJS dependency; no registry modules, computed imports or framework loader.
  for (const name of files.filter((f) => f.endsWith(".js"))) {
    const text = await fs.readFile(path.join(stage, name), "utf8");
    const matches = [
      ...text.matchAll(/\brequire\s*\(\s*(['"])([^'"]+)\1\s*\)/g),
    ];
    if ((text.match(/\brequire\s*\(/g) || []).length !== matches.length)
      throw new Error("Computed dependency forbidden: " + name);
    for (const match of matches) {
      const id = match[2];
      if (!id.startsWith("."))
        throw new Error("External runtime dependency: " + id);
      const resolved = path.resolve(stage, path.dirname(name), id);
      if (!resolved.startsWith(stage + path.sep))
        throw new Error("Dependency escapes app");
      await fs.access(resolved);
    }
  }
  await fs.writeFile(
    path.join(stage, "project.config.json"),
    JSON.stringify(
      {
        description: "SouthLeaf native WeChat stage one",
        projectname: "SouthLeaf",
        compileType: "miniprogram",
        miniprogramRoot: "./",
        appid: config.appId,
        libVersion: "3.17.3",
        setting: {
          urlCheck: config.runtime.release,
          es6: true,
          minified: true,
          postcss: true,
          enhance: true,
        },
      },
      null,
      2,
    ),
  );
  // DevTools holds the output directory open on Windows, even after closing a project.
  // Validate the complete staging tree first, then replace children without removing its root.
  // This is a local build, not an atomic production deployment protocol.
  await fs.mkdir(output, { recursive: true });
  for (const entry of await fs.readdir(output)) {
    if (entry === "project.private.config.json") continue;
    await fs.rm(path.join(output, entry), {
      recursive: true,
      force: true,
      maxRetries: 3,
      retryDelay: 150,
    });
  }
  await fs.cp(stage, output, { recursive: true });
  await fs.rm(stage, { recursive: true, force: true });
  console.log(
    "NATIVE_BUILD_PASS mode=" +
      mode +
      " declaredFiles=" +
      files.length +
      " runtimeDependencies=0 routes=" +
      routes.length +
      " assets=" +
      assets.size,
  );
} catch (error) {
  await fs.rm(stage, { recursive: true, force: true });
  throw error;
}
