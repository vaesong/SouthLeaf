import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import {
  candidateFiles,
  root,
  manifestPath,
  contentHash,
} from "./stage-one-files.mjs";
const mode = process.argv[2];
if (!["--write", "--check"].includes(mode) || process.argv.length !== 3)
  throw new Error("Usage: node tools/freeze-stage-one.mjs --write|--check");
const hash = (data) => crypto.createHash("sha256").update(data).digest("hex");
const files = candidateFiles().map((name) => ({
  path: name,
  sha256: contentHash(fs.readFileSync(path.join(root, name))),
}));
const current = {
  format: 2,
  hashing: "sha256-utf8-lf-binary-exact",
  scope: "stage-one-demo-source-not-production",
  candidateId: hash(JSON.stringify(files)),
  files,
};
const target = path.join(root, manifestPath);
if (mode === "--write") {
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, JSON.stringify(current, null, 2) + "\n");
} else {
  const expected = JSON.parse(fs.readFileSync(target, "utf8"));
  if (JSON.stringify(expected) !== JSON.stringify(current))
    throw new Error(
      "Candidate changed: review differences and rerun validation before refreezing",
    );
}
console.log(
  "STAGE_ONE_CANDIDATE_" +
    (mode === "--write" ? "FROZEN" : "VERIFIED") +
    " files=" +
    files.length +
    " sha256=" +
    current.candidateId,
);
