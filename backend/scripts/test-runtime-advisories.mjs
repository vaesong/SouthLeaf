import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath, pathToFileURL } from "node:url";
import test from "node:test";

const scanner = pathToFileURL(
  path.join(
    path.dirname(fileURLToPath(import.meta.url)),
    "check-runtime-advisories.mjs",
  ),
).href;
function fixture(run) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "southleaf-osv-test-"));
  try {
    fs.mkdirSync(path.join(dir, "public-api/target"), { recursive: true });
    fs.writeFileSync(
      path.join(dir, "pom.xml"),
      "<project><parent><version>4.1.1</version></parent></project>",
    );
    fs.writeFileSync(path.join(dir, "public-api/pom.xml"), "<project/>");
    const inventory = path.join(
      dir,
      "public-api/target/runtime-dependencies.txt",
    );
    fs.writeFileSync(
      inventory,
      "   org.springframework.boot:spring-boot:jar:4.1.1:compile\n",
    );
    const future = new Date(Date.now() + 2000);
    fs.utimesSync(inventory, future, future);
    const report = path.join(dir, "public-api/target/runtime-advisories.json");
    fs.writeFileSync(
      report,
      JSON.stringify({ matches: [], staleFixture: true }),
    );
    run({ dir, inventory, report });
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}
function execute(dir, fetchBody, scope = "public-api") {
  return spawnSync(
    process.execPath,
    [
      "--input-type=module",
      "-e",
      `process.argv=["node","scanner",${JSON.stringify(scope)}]; globalThis.fetch = async () => { ${fetchBody} }; await import(${JSON.stringify(scanner)});`,
    ],
    { cwd: dir, encoding: "utf8", timeout: 10000 },
  );
}

test("transport failure removes the previous clean report", () =>
  fixture(({ dir, report }) => {
    const result = execute(
      dir,
      'throw new Error("synthetic offline fixture");',
    );
    assert.equal(result.status, 1);
    assert.match(result.stderr, /synthetic offline fixture/);
    assert.equal(fs.existsSync(report), false);
  }));
test("stale inventory removes the previous clean report without querying", () =>
  fixture(({ dir, inventory, report }) => {
    fs.utimesSync(inventory, new Date(0), new Date(0));
    const result = execute(dir, 'throw new Error("must not query");');
    assert.equal(result.status, 1);
    assert.match(
      result.stderr,
      /Regenerate resolved runtime dependency inventory/,
    );
    assert.doesNotMatch(result.stderr, /must not query/);
    assert.equal(fs.existsSync(report), false);
  }));
test("incomplete response cannot leave a clean report", () =>
  fixture(({ dir, report }) => {
    const result = execute(
      dir,
      "return {ok:true, json:async()=>({results:[]})};",
    );
    assert.equal(result.status, 1);
    assert.match(result.stderr, /Incomplete advisory result/);
    assert.equal(fs.existsSync(report), false);
  }));
test("runtime inventory includes the shipped maintained KDF dependencies", () => {
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),"southleaf-runtime-osv-test-"));
  try {
    fs.mkdirSync(path.join(dir,"admin-runtime/target"),{recursive:true});
    fs.mkdirSync(path.join(dir,"admin-credential-crypto"),{recursive:true});
    fs.writeFileSync(path.join(dir,"pom.xml"),"<project><parent><version>4.1.1</version></parent></project>");
    fs.writeFileSync(path.join(dir,"admin-runtime/pom.xml"),"<project/>");
    fs.writeFileSync(path.join(dir,"admin-credential-crypto/pom.xml"),"<project><dependency><artifactId>bcprov-jdk18on</artifactId><version>1.86</version></dependency></project>");
    const inventory=path.join(dir,"admin-runtime/target/runtime-dependencies.txt");
    fs.writeFileSync(inventory,[
      " org.springframework.security:spring-security-crypto:jar:7.1.1:compile",
      " org.springframework:spring-core:jar:7.0.9:compile",
      " org.bouncycastle:bcprov-jdk18on:jar:1.86:runtime"
    ].join("\n"));
    const future=new Date(Date.now()+2000);fs.utimesSync(inventory,future,future);
    const report=path.join(dir,"admin-runtime/target/runtime-advisories.json");
    const result=execute(dir,"return {ok:true,json:async()=>({results:[{},{},{}]})};","admin-runtime");
    assert.equal(result.status,0,result.stderr);
    const data=JSON.parse(fs.readFileSync(report,"utf8"));
    assert.equal(data.module,"admin-runtime");
    assert.equal(data.queries.length,3);
    assert.ok(data.queries.some(q=>q.package.name==="org.bouncycastle:bcprov-jdk18on"));
  } finally { fs.rmSync(dir,{recursive:true,force:true}); }
});

test("complete response replaces the report for the selected inventory", () =>
  fixture(({ dir, report }) => {
    const result = execute(
      dir,
      "return {ok:true, json:async()=>({results:[{}]})};",
    );
    assert.equal(result.status, 0, result.stderr);
    const data = JSON.parse(fs.readFileSync(report, "utf8"));
    assert.equal(data.module, "public-api");
    assert.equal(data.queries.length, 1);
    assert.deepEqual(data.matches, []);
    assert.equal(data.staleFixture, undefined);
  }));
