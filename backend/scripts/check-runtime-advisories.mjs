import fs from "node:fs";
// Run from backend. Explicitly scan every shipped graph containing the credential adapter.
const module = process.argv[2] ?? "public-api";
if (
  process.argv.length > 3 ||
  !["public-api", "admin-credential-crypto", "admin-runtime"].includes(module)
)
  throw new Error("Unsupported runtime inventory scope");
const file = `${module}/target/runtime-dependencies.txt`;
const reportFile = `${module}/target/runtime-advisories.json`;
// A failed/latest attempt must not leave an older clean result available to evidence collectors.
fs.rmSync(reportFile, { force: true });
if (
  fs.statSync(file).mtimeMs <
  Math.max(
    fs.statSync("pom.xml").mtimeMs,
    fs.statSync(`${module}/pom.xml`).mtimeMs,
  )
)
  throw new Error(
    "Regenerate resolved runtime dependency inventory after the POM change",
  );
const text = fs.readFileSync(file, "utf8");
const coordinates = [
  ...text.matchAll(/^\s+([^\s:]+):([^\s:]+):jar:([^\s:]+):(compile|runtime)/gm),
];
if (!coordinates.length)
  throw new Error("Runtime dependency inventory is empty");
const version = fs
  .readFileSync("pom.xml", "utf8")
  .match(/<parent>[\s\S]*?<version>([^<]+)<\/version>/)?.[1];
if (
  module === "public-api" &&
  !coordinates.some(
    (m) =>
      m[1] === "org.springframework.boot" &&
      m[2] === "spring-boot" &&
      m[3] === version,
  )
)
  throw new Error("Inventory does not match current Boot version");
if (["admin-credential-crypto", "admin-runtime"].includes(module)) {
  const cryptoModule = "admin-credential-crypto";
  const providerVersion = fs
    .readFileSync(`${cryptoModule}/pom.xml`, "utf8")
    .match(
      /<artifactId>bcprov-jdk18on<\/artifactId>\s*<version>([^<]+)<\/version>/,
    )?.[1];
  if (
    !providerVersion ||
    !coordinates.some(
      (m) =>
        m[1] === "org.bouncycastle" &&
        m[2] === "bcprov-jdk18on" &&
        m[3] === providerVersion,
    ) ||
    !coordinates.some(
      (m) =>
        m[1] === "org.springframework.security" &&
        m[2] === "spring-security-crypto",
    ) ||
    !coordinates.some(
      (m) => m[1] === "org.springframework" && m[2] === "spring-core",
    )
  )
    throw new Error("Incomplete credential runtime inventory");
}
// Private reactor coordinates are local code, not public registry packages; do not send them to OSV.
const queries = coordinates
  .filter((m) => m[1] !== "cn.zhinan")
  .map((m) => ({
    package: { ecosystem: "Maven", name: `${m[1]}:${m[2]}` },
    version: m[3],
  }));
const response = await fetch("https://api.osv.dev/v1/querybatch", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ queries }),
  signal: AbortSignal.timeout(30000),
});
if (!response.ok) throw new Error(`OSV unavailable: ${response.status}`);
const { results } = await response.json();
if (!Array.isArray(results) || results.length !== queries.length)
  throw new Error("Incomplete advisory result");
const matches = queries.flatMap((q, i) =>
  (results[i].vulns || []).map((v) => ({
    package: q.package.name,
    version: q.version,
    id: v.id,
  })),
);
fs.writeFileSync(
  reportFile,
  JSON.stringify(
    {
      checkedAt: new Date().toISOString(),
      module,
      scope:
        "resolved Maven compile/runtime dependencies; not build plugins or exploitability proof",
      queries,
      results,
      matches,
    },
    null,
    2,
  ),
);
console.log(
  `OSV_RUNTIME_SCAN packages=${queries.length} advisoryMatches=${matches.length} module=${module}`,
);
for (const m of matches) console.log(`${m.package}@${m.version} ${m.id}`);
if (matches.length) process.exitCode = 1;
