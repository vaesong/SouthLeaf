"""Packaged non-Web startup negatives. No real credentials, SQL provisioning or grants.
Run after full reactor verify. Only the child environment is sanitized.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "admin-runtime/target/admin-runtime-0.1.0-SNAPSHOT.jar"
JAVA = shutil.which("java")
if not JAR.is_file() or not JAVA:
    raise SystemExit("Build admin-runtime and provide Java first")


def child_environment():
    # Never pass an accidentally inherited actual management/migration credential to this test.
    return {k: v for k, v in os.environ.items() if not k.upper().startswith(
        ("SOUTHLEAF_", "SPRING_", "LOGGING_", "LOGBACK_", "LOG_", "SERVER_"))
        and k.upper() not in ("DEBUG", "TRACE", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")}


def fixture(port):
    return {"SOUTHLEAF_ADMIN_MODE": "demo",
            "SOUTHLEAF_ADMIN_DB_URL": f"jdbc:mysql://127.0.0.1:{port}/southleaf_admin_negative?sslMode=REQUIRED",
            "SOUTHLEAF_ADMIN_DB_USERNAME": "negative_fixture_only",
            "SOUTHLEAF_ADMIN_DB_PASSWORD": "NOT_A_CREDENTIAL_NEGATIVE_FIXTURE",
            "SOUTHLEAF_ADMIN_DB_SCHEMA": "southleaf_admin_negative",
            "SOUTHLEAF_ADMIN_DB_EXPECTED_USER": "negative_fixture_only@localhost"}


def check(label, values, reason, workdir, args=("--check",), jvm=(), monitor=False):
    env = child_environment()
    env.update(values)
    command = [JAVA, *jvm, "-jar", str(JAR), *args]
    samples = 0
    with subprocess.Popen(command, env=env, cwd=workdir, stdin=subprocess.DEVNULL,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT) as process:
        try:
            deadline = time.monotonic() + 25
            while monitor and process.poll() is None:
                if time.monotonic() > deadline:
                    raise AssertionError(label + ": child timeout")
                # Inspect only the new child's PID; do not stop or probe existing services.
                if os.name == "nt":
                    network = subprocess.run(["netstat", "-ano", "-p", "TCP"],
                                             capture_output=True, text=True, check=True, timeout=5)
                    for line in network.stdout.splitlines():
                        fields = line.split()
                        if len(fields) >= 5 and fields[-1] == str(process.pid) and fields[-2] == "LISTENING":
                            raise AssertionError(label + ": unexpected listener")
                    samples += 1
                time.sleep(0.1)
            output, _ = process.communicate(timeout=25)
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)
    expected = f"ADMIN_RUNTIME_REFUSED:{reason}".encode()
    if process.returncode != 2 or output.strip() != expected:
        # Do not dump arbitrary JVM/library output; it could contain an inherited secret.
        raise AssertionError(f"{label}: exit={process.returncode}, output-sha256={hashlib.sha256(output).hexdigest()}")
    if monitor and os.name == "nt" and samples == 0:
        raise AssertionError(label + ": no listener observation")
    return {"case": label, "exitCode": process.returncode, "reason": reason,
            "exactSanitizedOutput": True, "listenerSamples": samples}


results = []
# Bound but NOT listening, so no other process can claim the negative-test port.
with socket.socket() as reserved, tempfile.TemporaryDirectory(prefix="southleaf-admin-negative-") as folder:
    reserved.bind(("127.0.0.1", 0))
    valid = fixture(reserved.getsockname()[1])
    # Poison usual external Config Data locations. These are synthetic nonsecret sentinels.
    workdir = Path(folder)
    (workdir / "config").mkdir()
    for target in (workdir / "application.properties", workdir / "application-admin.properties",
                   workdir / "config/application.properties", workdir / "config/application-admin.properties"):
        target.write_text("southleaf.admin.runtime.config-id=PUBLIC_POISON\nspring.main.web-application-type=servlet\nspring.config.import=file:DO_NOT_LOAD_SENTINEL\n", encoding="utf-8")
    results.append(check("missing-management-configuration", {}, "CONFIGURATION", workdir))
    results.append(check("public-environment-never-fallback", {"SOUTHLEAF_DB_URL": "PUBLIC_SENTINEL", "SOUTHLEAF_DB_USERNAME": "reader", "SOUTHLEAF_DB_PASSWORD": "FAKE_PUBLIC_SENTINEL"}, "CONFIGURATION", workdir))
    results.append(check("unsupported-cli-before-boot", valid, "ARGUMENTS", workdir, args=("--server.port=0",)))
    for key in valid:
        values = dict(valid)
        del values[key]
        results.append(check("required-" + key, values, "CONFIGURATION", workdir))
    for key in ("SPRING_CONFIG_LOCATION", "SPRING_CONFIG_IMPORT", "SPRING_PROFILES_ACTIVE", "SPRING_APPLICATION_JSON", "SPRING_MAIN_WEB_APPLICATION_TYPE", "LOGGING_CONFIG", "SOUTHLEAF_ADMIN_BYPASS"):
        values = dict(valid)
        values[key] = "POISON_SENTINEL"
        results.append(check("reject-env-" + key, values, "CONFIG_SOURCE", workdir))
    for option in ("-Dspring.config.location=POISON_SENTINEL", "-Dlogback.configurationFile=POISON_SENTINEL", "-Dorg.springframework.boot.logging.LoggingSystem=none"):
        results.append(check("reject-jvm-" + option.split("=")[0][2:], valid, "CONFIG_SOURCE", workdir, jvm=(option,)))
    values = dict(valid)
    values.update(SOUTHLEAF_ADMIN_DB_USERNAME="root", SOUTHLEAF_ADMIN_DB_EXPECTED_USER="root@localhost")
    results.append(check("reject-root", values, "CONFIGURATION", workdir))
    values = dict(valid)
    values["SOUTHLEAF_ADMIN_MODE"] = "production"
    values["SOUTHLEAF_ADMIN_DB_SCHEMA"] = "southleaf_admin"
    values["SOUTHLEAF_ADMIN_DB_URL"] = valid["SOUTHLEAF_ADMIN_DB_URL"].replace("southleaf_admin_negative", "southleaf_admin")
    results.append(check("reject-production-weak-tls", values, "CONFIGURATION", workdir))
    values["SOUTHLEAF_ADMIN_MODE"] = "demo"
    results.append(check("reject-demo-production-schema", values, "CONFIGURATION", workdir))
    results.append(check("check-mode-db-unavailable-no-external-config", valid, "DATABASE_CHECK", workdir, monitor=True))
    results.append(check("default-mode-db-unavailable-no-listener", valid, "DATABASE_CHECK", workdir, args=(), monitor=True))

print(json.dumps({"result": "PASS", "jarSha256": hashlib.sha256(JAR.read_bytes()).hexdigest(),
                  "cases": results, "limitations": ["No real Writer grants acceptance", "No successful real-DB runtime or production TLS certificate acceptance", "Listener sampling on Windows only; fixed non-Web composition also tested separately"]}, indent=2))
