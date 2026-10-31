"""Stage-two engineering gate.

Starts nothing on an existing server. The caller supplies the booking fixture through the
environment. This process provisions the disposable C3 instance, forwards both fixtures into
verify --with-reactor, checks admin schema validation and an unseeded public migration, then
removes only the C3 instance it created.
"""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

REPO = Path(__file__).resolve().parents[2]
EVIDENCE = REPO / "docs/开发与演示/证据/stage-two-engineering-close.json"
spec = importlib.util.spec_from_file_location("s1b_fixture", REPO / "backend/scripts/verify-admin-isolated.py")
c2 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(c2)
account_spec = importlib.util.spec_from_file_location(
    "c3_fixture", REPO / "backend/scripts/verify-account-cli-isolated.py")
c3 = importlib.util.module_from_spec(account_spec)
account_spec.loader.exec_module(c3)

BOOKING_KEYS = (
    "SOUTHLEAF_BOOKING_IT_URL",
    "SOUTHLEAF_BOOKING_IT_USERNAME",
    "SOUTHLEAF_BOOKING_IT_PASSWORD",
    "SOUTHLEAF_BOOKING_IT_INSTANCE_ID",
)
PUBLIC_SCHEMA = "southleaf_public_gate"
STEP = "preflight"


def require(condition, code):
    if not condition:
        raise c2.VerificationFailure(code)


def clear_stopped_c3_leftover():
    if not c3.BASE.exists():
        return
    children = list(c3.BASE.iterdir())
    if not children:
        c3.BASE.rmdir()
        return
    require(len(children) == 1, "C3_FIXTURE_ALREADY_EXISTS_REFUSE_OVERWRITE")
    manifest = children[0] / "manifest.json"
    require(manifest.is_file(), "C3_FIXTURE_ALREADY_EXISTS_REFUSE_OVERWRITE")
    meta = json.loads(manifest.read_text(encoding="utf-8"))
    require(meta.get("kind") == "southleaf-s1c3-explicitly-authorized-test",
            "C3_FIXTURE_ALREADY_EXISTS_REFUSE_OVERWRITE")
    require(meta.get("status") == "prepared-and-cleanly-stopped",
            "C3_FIXTURE_ALREADY_EXISTS_REFUSE_OVERWRITE")
    c3.cleanup(children[0])


def certificate_still_valid(path):
    result = subprocess.run(
        [str(c2.OPENSSL), "x509", "-in", str(path), "-noout", "-checkend", "0"],
        stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return result.returncode == 0


def owned_c2_manifest():
    """Use a stopped owned fixture whose server certificate is still inside its validity window."""
    chosen = None
    chosen_at = ""
    for path in sorted(c2.BASE.glob("*/manifest.json")):
        try:
            _, meta, passwords = c2.load_manifest(path)
        except c2.VerificationFailure:
            continue
        if "s1b_public_it" not in passwords or "s1b_public_reader" not in passwords:
            passwords.clear()
            continue
        if not certificate_still_valid(path.parent / "server-cert.pem"):
            passwords.clear()
            continue
        passwords.clear()
        created = meta.get("createdAt") or ""
        if chosen is None or created > chosen_at:
            chosen = path
            chosen_at = created
    require(chosen is not None, "C2_TEST_CERTIFICATE_EXPIRED")
    return chosen


def unseeded_public_migration(meta, passwords):
    c2.sql(meta, passwords["root"],
           f"CREATE DATABASE `{PUBLIC_SCHEMA}` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;")
    try:
        environment = {
            "SOUTHLEAF_MIGRATION_URL":
                f"jdbc:mysql://localhost:{meta['port']}/{PUBLIC_SCHEMA}?sslMode=VERIFY_IDENTITY"
                "&allowPublicKeyRetrieval=false&connectionTimeZone=UTC"
                "&forceConnectionTimeZoneToSession=true",
            "SOUTHLEAF_MIGRATION_USERNAME": "root",
            "SOUTHLEAF_MIGRATION_PASSWORD": passwords["root"],
            "JAVA_TOOL_OPTIONS":
                "-Djavax.net.ssl.trustStore=" + str(Path(meta["root"]) / "truststore.jks")
                + " -Djavax.net.ssl.trustStoreType=JKS",
        }
        result = subprocess.run(
            [str(REPO / "backend" / "mvnw.cmd"), "-f", "public-api/pom.xml", "-DskipTests", "compile",
             "org.codehaus.mojo:exec-maven-plugin:3.5.0:java",
             "-Dexec.mainClass=cn.zhinan.uniapp.tooling.DatabaseMigrationMain"],
            cwd=REPO / "backend", env=c2.clean_env(environment), stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=300)
        require(result.returncode == 0 and b"SCHEMA_MIGRATION_PASS" in result.stdout
                and b"EXPLICIT_DEMO_IMPORT_PASS" not in result.stdout
                and b"--seed-demo" not in result.stdout, "PUBLIC_UNSEEDED_MIGRATION_FAILED")
        count = c2.sql(meta, passwords["root"],
                       f"SELECT COUNT(*) FROM `{PUBLIC_SCHEMA}`.catalog_release;").strip()
        require(count == b"0", "PUBLIC_UNSEEDED_MIGRATION_NOT_EMPTY")
    finally:
        c2.sql(meta, passwords["root"], f"DROP DATABASE IF EXISTS `{PUBLIC_SCHEMA}`;")


def write_evidence(payload):
    payload["secretsRecorded"] = False
    payload["realOrProductionDataUsed"] = False
    payload["originalServerTouched"] = False
    EVIDENCE.parent.mkdir(parents=True, exist_ok=True)
    c2.atomic_json(EVIDENCE, payload)


def main():
    global STEP
    require("--confirm-isolated-gate" in sys.argv, "EXPLICIT_GATE_CONFIRMATION_REQUIRED")
    require(all(os.environ.get(key) for key in BOOKING_KEYS), "BOOKING_FIXTURE_ENV_MISSING")
    manifest = meta = passwords = server = None
    c3_removed = False
    failure = None
    failed_step = None
    admin_validated = False
    public_unseeded = False
    c3_injected = False
    try:
        STEP = "reuse-stopped-c2-manifest"
        manifest = owned_c2_manifest()
        STEP = "clear-stopped-c3-leftover"
        clear_stopped_c3_leftover()
        STEP = "provision-c3"
        _, meta, passwords = c3.provision(manifest)
        STEP = "hold-c3"
        server = c2.start_server(meta, passwords["root"])
        os.environ.update(c3.c3_it_environment(meta, passwords))
        c3_injected = True
        STEP = "admin-schema-validation"
        command, env = c3.admin_migration_command(meta, passwords)
        c2.run(command + ["--validate"], env=env, marker="ADMIN_SCHEMA_VALIDATION_PASS")
        admin_validated = True
        STEP = "reactor"
        c2.verify(manifest, with_reactor=True, require_console_shutdown=False)
        STEP = "public-unseeded-migration"
        unseeded_public_migration(meta, passwords)
        public_unseeded = True
    except BaseException as error:
        failure = error
        failed_step = STEP
    finally:
        if server is not None and meta is not None and passwords is not None:
            try:
                c2.stop_server(meta, passwords["root"], server)
            except BaseException as error:
                if failure is None:
                    failure = error
                    failed_step = "stop-c3"
        if meta is not None:
            try:
                c3.cleanup(Path(meta["root"]))
                c3_removed = True
            except BaseException as error:
                c3_removed = False
                if failure is None:
                    failure = error
                    failed_step = "remove-c3"
        if passwords:
            passwords.clear()
        write_evidence({
            "kind": "STAGE_TWO_ENGINEERING_CLOSE",
            "status": "PASS" if failure is None and c3_removed else "FAIL",
            "failedStep": failed_step,
            "failure": (str(failure) if isinstance(failure, c2.VerificationFailure)
                        else None if failure is None else type(failure).__name__),
            "bookingFixtureInjected": True,
            "c3FixtureInjected": c3_injected,
            "sameMavenVerify": failure is None,
            "mavenProfile": "c3-isolated-it",
            "adminSchemaValidationPass": admin_validated,
            "publicMigrationSeedDemo": False,
            "publicUnseededMigrationPass": public_unseeded,
            "c3FixtureRemoved": c3_removed,
            "bookingDatadirRemoved": False,
            "consoleShutdownProbe": "NOT_RUN",
        })
    if failure is not None:
        raise failure


if __name__ == "__main__":
    try:
        main()
    except (c2.VerificationFailure, subprocess.TimeoutExpired, OSError, ValueError) as failure:
        detail = str(failure) if isinstance(failure, c2.VerificationFailure) else type(failure).__name__
        if isinstance(failure, subprocess.TimeoutExpired):
            command = failure.cmd[-1] if isinstance(failure.cmd, (list, tuple)) and failure.cmd else failure.cmd
            detail += ":" + Path(str(command)).name + ":" + str(failure.timeout)
        print("STAGE_TWO_GATE_FAILED:" + STEP + ":" + detail, file=sys.stderr)
        sys.exit(2)
