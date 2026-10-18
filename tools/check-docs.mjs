import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const walk = (dir) =>
  fs
    .readdirSync(dir, { withFileTypes: true })
    .flatMap((e) =>
      e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)],
    );
const files = ["docs", ".trellis/spec"]
  .flatMap((d) => walk(path.join(root, d)))
  .filter((f) => f.endsWith(".md"));
let links = 0;
const errors = [];
for (const file of files) {
  const text = fs
    .readFileSync(file, "utf8")
    .replace(/^(```|~~~)[\s\S]*?^\1[^\n]*$/gm, "");
  for (const m of text.matchAll(/!?\[[^\]\n]*\]\(([^)\n]+)\)/g)) {
    let target = m[1]
      .trim()
      .replace(/^<([^>]+)>.*$/, "$1")
      .replace(/\s+['"].*$/, "");
    if (
      !target ||
      target.startsWith("#") ||
      /^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(target)
    )
      continue;
    target = decodeURIComponent(target.split(/[?#]/)[0]);
    if (!target) continue;
    links++;
    const resolved = path.resolve(path.dirname(file), target);
    if (!resolved.startsWith(root + path.sep) || !fs.existsSync(resolved))
      errors.push(
        `${path.relative(root, file)}: missing local target ${target}`,
      );
  }
}
console.log(
  JSON.stringify(
    { check: "local-markdown-file-links", files: files.length, links, errors },
    null,
    2,
  ),
);
if (errors.length) process.exitCode = 1;
// Does not claim heading-anchor, prose truth, external URL or future-scope completeness validation.
