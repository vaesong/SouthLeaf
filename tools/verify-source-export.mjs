import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { candidateFiles, root } from "./stage-one-files.mjs";
if (!process.env.SOUTHLEAF_API_ORIGIN)
  throw new Error(
    "Explicit SOUTHLEAF_API_ORIGIN required for live export verification",
  );
const temporary = fs.mkdtempSync(
  path.join(os.tmpdir(), "southleaf-source-export-"),
);
try {
  const files = candidateFiles();
  for (const file of files) {
    const dest = path.join(temporary, file);
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.copyFileSync(path.join(root, file), dest);
  }
  const cwd = path.join(temporary, "frontend");
  const commands = [
    "npm ci --ignore-scripts --registry=https://registry.npmjs.org",
    "npm run verify",
    "npm audit --registry=https://registry.npmjs.org",
    "npm run build:demo",
    "npm run test:contract",
  ];
  for (const command of commands) {
    console.log("SOURCE_EXPORT_RUN " + command);
    const result =
      process.platform === "win32"
        ? spawnSync("cmd.exe", ["/d", "/s", "/c", command], {
            cwd,
            env: process.env,
            stdio: "inherit",
          })
        : spawnSync("sh", ["-c", command], {
            cwd,
            env: process.env,
            stdio: "inherit",
          });
    if (result.error || result.status !== 0)
      throw new Error("Source export verification failed: " + command);
  }
  for (const flag of ["--write", "--check"]) {
    const check = spawnSync(
      process.execPath,
      ["tools/freeze-stage-one.mjs", flag],
      { cwd: temporary, encoding: "utf8" },
    );
    if (check.status !== 0)
      throw new Error(
        "Export candidate fingerprint verification failed: " + check.stderr,
      );
  }
  const readme = path.join(temporary, "README.md");
  fs.writeFileSync(
    readme,
    fs.readFileSync(readme, "utf8").replace(/\r?\n/g, "\r\n"),
  );
  const crlf = spawnSync(
    process.execPath,
    ["tools/freeze-stage-one.mjs", "--check"],
    { cwd: temporary, encoding: "utf8" },
  );
  if (crlf.status !== 0)
    throw new Error(
      "Candidate must survive Git line-ending normalization: " + crlf.stderr,
    );
  console.log(
    "CANDIDATE_LINE_ENDING_NORMALIZATION_PASS (isolated export only)",
  );
  fs.appendFileSync(
    path.join(temporary, "README.md"),
    "\nSource-export tamper probe\n",
  );
  const tampered = spawnSync(
    process.execPath,
    ["tools/freeze-stage-one.mjs", "--check"],
    { cwd: temporary, encoding: "utf8" },
  );
  if (tampered.status === 0 || !tampered.stderr.includes("Candidate changed"))
    throw new Error("Candidate checker failed to detect changed source");
  console.log("CANDIDATE_TAMPER_DETECTION_PASS (isolated export only)");
  console.log(
    "SOURCE_EXPORT_PASS files=" +
      files.length +
      "; no reused node_modules/dist/unpackage/target; clean install/tests/build/live contract",
  );
} finally {
  fs.rmSync(temporary, { recursive: true, force: true, maxRetries: 3 });
}
