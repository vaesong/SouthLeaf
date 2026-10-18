import fs from "node:fs";
const file = "target/runtime-dependencies.txt";
if (fs.statSync(file).mtimeMs < fs.statSync("pom.xml").mtimeMs)
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
  !coordinates.some(
    (m) =>
      m[1] === "org.springframework.boot" &&
      m[2] === "spring-boot" &&
      m[3] === version,
  )
)
  throw new Error("Inventory does not match current Boot version");
const queries = coordinates.map((m) => ({
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
  "target/runtime-advisories.json",
  JSON.stringify(
    {
      checkedAt: new Date().toISOString(),
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
  `OSV_RUNTIME_SCAN packages=${queries.length} advisoryMatches=${matches.length}`,
);
for (const m of matches) console.log(`${m.package}@${m.version} ${m.id}`);
if (matches.length) process.exitCode = 1;
