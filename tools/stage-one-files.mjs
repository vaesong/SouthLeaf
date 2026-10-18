import fs from "node:fs";
import crypto from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";
export const root = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
export const manifestPath = "docs/开发与演示/证据/stage-one-candidate.json";
const entries = [
  "README.md",
  "LICENSE",
  "AGENTS.md",
  ".gitignore",
  ".gitattributes",
  "frontend",
  "backend/pom.xml",
  "backend/mvnw",
  "backend/mvnw.cmd",
  "backend/.mvn",
  "backend/.gitignore",
  "backend/.gitattributes",
  "backend/src",
  "backend/scripts",
  "tools",
  ".trellis/spec",
  "docs",
];
const excluded = new Set([
  "node_modules",
  "dist",
  "target",
  "unpackage",
  ".git",
  ".hbuilderx",
]);
export function candidateFiles() {
  const files = [];
  function walk(relative) {
    if (relative === manifestPath) return;
    const name = path.basename(relative);
    if (
      excluded.has(name) ||
      name.startsWith(".env") ||
      name === "project.private.config.json" ||
      name === "application-local.properties"
    )
      return;
    const absolute = path.join(root, relative);
    const stat = fs.lstatSync(absolute);
    if (stat.isSymbolicLink())
      throw new Error("Source symlink requires explicit review: " + relative);
    if (stat.isDirectory())
      for (const child of fs.readdirSync(absolute).sort())
        walk(relative + "/" + child);
    else if (stat.isFile()) files.push(relative);
    else throw new Error("Unexpected source entry: " + relative);
  }
  for (const entry of entries) walk(entry);
  return files.sort();
}

// Git may normalize text line endings at add/checkout. Hash text canonically, binary exactly.
export function contentHash(bytes) {
  let canonical = bytes;
  if (!bytes.includes(0)) {
    try {
      const text = new TextDecoder("utf-8", {
        fatal: true,
        ignoreBOM: true,
      }).decode(bytes);
      canonical = Buffer.from(text.replace(/\r\n/g, "\n"), "utf8");
    } catch {
      /* Invalid UTF-8 is binary and remains byte-exact. */
    }
  }
  return crypto.createHash("sha256").update(canonical).digest("hex");
}
