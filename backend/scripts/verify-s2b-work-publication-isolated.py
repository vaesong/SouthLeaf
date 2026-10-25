#!/usr/bin/env python3
"""S2-B 一次性本地 L5 runner：真实双 schema MySQL/InnoDB、独立账号与精确授权、
管理端上传/草稿/发布/撤回、公开 API 与小程序读链路。只操作本次创建并登记的 disposable fixture。

复用 S1-D runner 已验证的 TLS / mysqld / 进程停止实现，但使用独立的 S2B manifest 守卫、
fixture 根目录与命名；绝不触碰 127.0.0.1:3306 原服务或 C2 fixture。失败如实记录，不写 PASS。
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import re
import secrets
import shutil
import ssl
import struct
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
import zlib
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path
from queue import Empty, Queue

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("s1d_fixture", HERE / "verify-s1d-admin-web-isolated.py")
s1d = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(s1d)  # type: ignore[union-attr]

Failure = s1d.S1DFailure
require = s1d.require
REPO = s1d.REPO
BACKEND = s1d.BACKEND
WEB = s1d.WEB
FRONTEND = REPO / "frontend"
FIXTURE_KIND = "S2B_DISPOSABLE_L5"
BASE_NAME = "S2BWorkPublicationVerification"
RUNTIME_JAR = s1d.RUNTIME_JAR
PUBLIC_JAR = BACKEND / "public-api" / "target" / "uniapp-api-0.1.0-SNAPSHOT.jar"
BROWSER_TEST = WEB / "e2e" / "s2b-l5.mjs"
M3_BROWSER_TEST = WEB / "e2e" / "m3-catalog.mjs"
CHAIN_TEST = FRONTEND / "scripts" / "check-s2b-overlay-chain.mjs"
CONTRACT_TEST = FRONTEND / "scripts" / "check-public-contract.mjs"
EVIDENCE_DIR = REPO / "docs" / "开发与演示" / "证据"
EVIDENCE = EVIDENCE_DIR / "stage-two-s2b-isolated-l5.json"
READY_MARKER = "ADMIN_RUNTIME_READY:admin-isolated-v2:HTTPS_WEB_CONTENT"
DEMO_BUSINESS = "child"
SECRET_METADATA = b"S2B-SECRET-METADATA-MUST-NOT-SURVIVE"

# Mirrors AdminContentGrantPolicy exactly; the runtime also refuses to start on any drift.
BUSINESS_GRANTS = {
    "admin_media_asset": "SELECT, INSERT",
    "admin_media_variant": "SELECT, INSERT",
    "admin_media_upload_command": "SELECT, INSERT",
    "admin_work_case": "SELECT, INSERT, UPDATE",
    "admin_work_version": "SELECT, INSERT",
    "admin_work_version_business": "SELECT, INSERT",
    "admin_work_version_media": "SELECT, INSERT",
    "admin_catalog_draft": "SELECT, INSERT, UPDATE",
    "admin_catalog_draft_revision": "SELECT, INSERT",
}
PUBLICATION_ADMIN_GRANTS = {
    "admin_work_case": "SELECT",
    "admin_work_version": "SELECT",
    "admin_work_version_business": "SELECT",
    "admin_work_version_media": "SELECT",
    "admin_media_asset": "SELECT",
    "admin_media_variant": "SELECT",
    "admin_work_authorization_gate": "SELECT",
    "admin_work_publication_state": "SELECT, INSERT, UPDATE",
    "admin_publication_command": "SELECT, INSERT",
    "admin_publication_audit": "INSERT",
    "admin_catalog_draft": "SELECT",
    "admin_catalog_draft_revision": "SELECT",
    "admin_catalog_command": "SELECT, INSERT",
    "admin_catalog_audit": "INSERT",
}
PUBLICATION_PUBLIC_GRANTS = {
    "public_work_release": "INSERT",
    "public_work_release_business": "INSERT",
    "public_work_release_media": "INSERT",
    "public_work_release_variant": "INSERT",
    "public_work_release_authorization": "INSERT",
    "active_work_publication": "SELECT, INSERT, UPDATE",
    "public_work_projection_epoch": "SELECT, UPDATE",
    "public_catalog_release": "INSERT",
    "active_catalog_publication": "SELECT, INSERT, UPDATE",
    "public_catalog_release_media": "INSERT",
    "public_catalog_release_variant": "INSERT",
    "public_catalog_release_binding": "INSERT",
}


# --------------------------------------------------------------------------- fixture guards


def verify_manifest(meta: dict[str, object]) -> bool:
    try:
        root = Path(str(meta["root"]))
        disk = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
        run_id = str(disk.get("runId"))
        return (
            disk == meta
            and disk.get("kind") == FIXTURE_KIND
            and run_id == root.name
            and root.resolve().parent.name == BASE_NAME
            and disk.get("datadir") == str((root / "mysql-data").resolve())
            and disk.get("host") == "127.0.0.1"
            and disk.get("schema") == "southleaf_admin_s2b" + run_id
            and disk.get("publicSchema") == "southleaf_public_s2b" + run_id
            and disk.get("mysqlExecutable") == str(s1d.MYSQLD.resolve())
            and disk.get("mysqlStartMode") == "no-monitor"
        )
    except (OSError, ValueError, TypeError, KeyError):
        return False


# S1-D process/mysql helpers resolve these names at call time.
s1d.FIXTURE_KIND = FIXTURE_KIND
s1d.verify_manifest = verify_manifest

save_manifest = s1d.save_manifest


def check_build_inputs() -> dict[str, Path]:  # noqa: C901 - flat list of explicit artifacts
    migration = BACKEND / "admin-migration-cli"
    cli = BACKEND / "admin-account-cli"
    build = {
        "migrationClasses": migration / "target" / "classes",
        "migrationDeps": migration / "target" / "dependency",
        "cliClasses": cli / "target" / "classes",
        "cliTestClasses": cli / "target" / "test-classes",
        "cliDeps": cli / "target" / "dependency",
        "securityClasses": BACKEND / "admin-security-core" / "target" / "classes",
        "credentialClasses": BACKEND / "admin-credential-crypto" / "target" / "classes",
        "dbPolicyClasses": BACKEND / "admin-db-policy" / "target" / "classes",
        "dist": WEB / "dist",
    }
    required_paths = [
        RUNTIME_JAR,
        PUBLIC_JAR,
        build["migrationClasses"] / "db" / "admin-migration" / "V5__admin_work_publication.sql",
        build["migrationClasses"] / "db" / "admin-migration" / "V7__admin_catalog_authoring.sql",
        BACKEND / "public-api" / "target" / "classes" / "db" / "migration" / "V3__public_catalog_content.sql",
        build["cliTestClasses"] / "cn" / "zhinan" / "adminaccountcli" / "S2BAccountCliFixtureMain.class",
        build["dist"] / "index.html",
        WEB / "node_modules" / "playwright-core",
        BROWSER_TEST,
        CHAIN_TEST,
        CONTRACT_TEST,
    ]
    require(all(path.exists() for path in required_paths), "BUILD_ARTIFACTS_REQUIRED")
    require(any(build["migrationDeps"].glob("*.jar")) and any(build["cliDeps"].glob("*.jar")),
            "DEPENDENCY_CLASSPATH_REQUIRED")
    backend_sources: list[Path] = []
    for module in ("admin-runtime", "admin-content", "admin-db-policy", "admin-security-core",
                   "admin-security-jdbc", "admin-credential-crypto"):
        backend_sources += list((BACKEND / module / "src" / "main").rglob("*"))
    newest_backend = max(p.stat().st_mtime for p in backend_sources if p.is_file())
    require(newest_backend <= RUNTIME_JAR.stat().st_mtime, "STALE_ADMIN_RUNTIME_BUILD")
    # S2-B uses a disposable schema at the current migration head, including additive V7/V3,
    # while it still exercises only the S2 work APIs and authorization boundary.
    public_sources = [p for p in (BACKEND / "public-api" / "src" / "main").rglob("*") if p.is_file()]
    require(max(p.stat().st_mtime for p in public_sources) <= PUBLIC_JAR.stat().st_mtime, "STALE_PUBLIC_BUILD")
    web_inputs = [p for p in (WEB / "src").rglob("*") if p.is_file()]
    require(max(p.stat().st_mtime for p in web_inputs) <= (build["dist"] / "index.html").stat().st_mtime,
            "STALE_FRONTEND_BUILD")
    return build


def source_sha256() -> str:
    candidates: list[Path] = [BACKEND / "pom.xml", Path(__file__).resolve(), BROWSER_TEST, M3_BROWSER_TEST, CHAIN_TEST]
    for module in ("admin-runtime", "admin-content", "admin-db-policy", "admin-migration-cli", "public-api"):
        candidates += list((BACKEND / module / "src" / "main").rglob("*"))
    candidates += list((WEB / "src").rglob("*"))
    digest = hashlib.sha256()
    for path in sorted({p for p in candidates if p.is_file()}, key=lambda p: p.as_posix()):
        digest.update(path.relative_to(REPO).as_posix().encode("utf-8") + b"\0" + path.read_bytes() + b"\0")
    return digest.hexdigest()


s1d.source_sha256 = source_sha256


# --------------------------------------------------------------------------- database helpers


def q(value: str) -> str:
    return s1d.sql_quote(value)


def mysql_root(state: dict[str, object], sql: str, timeout: int = 15) -> str:
    result = s1d.mysql_query_raw(state, sql, max(timeout, 30))
    if result is None or result.returncode != 0:
        detail = (result.stderr.decode("utf-8", errors="replace") if result else "no-result")
        match = re.search(r"ERROR (\d+) \(([^)]+)\):", detail)
        code = "_".join(match.groups()) if match else "CLIENT_OR_TLS"
        print("S2B_MYSQL_DIAGNOSTIC:" + code, flush=True)
        raise Failure("MYSQL_CONTROL_QUERY_" + code)
    return result.stdout.decode("utf-8", errors="replace").strip()


def write_option_file(state: dict[str, object], user: str, password: str) -> Path:
    path = Path(str(state["meta"]["root"])) / f"client-{user}.cnf"
    path.write_text(f"[client]\nuser={user}\npassword={password}\n", encoding="utf-8", newline="\n")
    return path


def mysql_as(state: dict[str, object], user: str, sql: str) -> subprocess.CompletedProcess[bytes]:
    meta = state["meta"]
    option_file = Path(str(meta["root"])) / f"client-{user}.cnf"
    require(option_file.is_file(), "CLIENT_OPTION_FILE_MISSING")
    try:
        return subprocess.run(
            [str(s1d.MYSQL), f"--defaults-extra-file={option_file}", "--no-beep", "--protocol=TCP",
             "--host=127.0.0.1", "--port", str(meta["mysqlPort"]), "--connect-timeout=4",
             "--ssl-mode=VERIFY_IDENTITY", "--ssl-ca", str(state["tls"]["caCert"]),
             "--batch", "--raw", "--skip-column-names", "--default-character-set=utf8mb4"],
            input=sql.encode("utf-8"), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            env=s1d.child_env(), timeout=15, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise Failure("RESTRICTED_CLIENT_PROCESS") from None


def grant_statements(user: str, schema: str, grants: dict[str, str]) -> list[str]:
    return [f"GRANT {privileges} ON `{schema}`.`{table}` TO '{user}'@'localhost';"
            for table, privileges in grants.items()]


def create_schemas_and_migrators(state: dict[str, object], pw: dict[str, str]) -> None:
    meta = state["meta"]
    admin, public = str(meta["schema"]), str(meta["publicSchema"])
    mysql_root(state, "\n".join([
        f"CREATE DATABASE `{admin}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;",
        f"CREATE DATABASE `{public}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;",
        f"CREATE USER 's2b_admin_migrator'@'localhost' IDENTIFIED BY {q(pw['adminMigrator'])};",
        f"GRANT ALL PRIVILEGES ON `{admin}`.* TO 's2b_admin_migrator'@'localhost';",
        f"CREATE USER 's2b_public_migrator'@'localhost' IDENTIFIED BY {q(pw['publicMigrator'])};",
        f"GRANT ALL PRIVILEGES ON `{public}`.* TO 's2b_public_migrator'@'localhost';",
    ]))
    meta["status"] = "dual-schema-and-migrators-created"
    save_manifest(meta)


def create_scoped_users(state: dict[str, object], pw: dict[str, str]) -> None:
    meta = state["meta"]
    admin, public = str(meta["schema"]), str(meta["publicSchema"])
    statements = [
        f"CREATE USER 's2b_cli'@'localhost' IDENTIFIED BY {q(pw['cliDb'])};",
        f"GRANT SELECT, UPDATE ON `{admin}`.admin_bootstrap_guard TO 's2b_cli'@'localhost';",
        f"GRANT SELECT, INSERT, UPDATE ON `{admin}`.admin_account TO 's2b_cli'@'localhost';",
        f"GRANT SELECT, INSERT, DELETE ON `{admin}`.admin_account_permission TO 's2b_cli'@'localhost';",
        f"GRANT SELECT, INSERT ON `{admin}`.admin_account_command TO 's2b_cli'@'localhost';",
        f"CREATE USER 's2b_runtime'@'localhost' IDENTIFIED BY {q(pw['runtime'])};",
        f"GRANT SELECT ON `{admin}`.admin_account TO 's2b_runtime'@'localhost';",
        f"GRANT SELECT ON `{admin}`.admin_account_permission TO 's2b_runtime'@'localhost';",
        f"GRANT SELECT, INSERT, UPDATE ON `{admin}`.admin_session TO 's2b_runtime'@'localhost';",
        f"GRANT INSERT ON `{admin}`.admin_security_audit TO 's2b_runtime'@'localhost';",
        f"GRANT INSERT ON `{admin}`.admin_auth_event TO 's2b_runtime'@'localhost';",
        f"CREATE USER 's2b_content'@'localhost' IDENTIFIED BY {q(pw['content'])};",
        *grant_statements("s2b_content", admin, BUSINESS_GRANTS),
        f"CREATE USER 's2b_publisher'@'localhost' IDENTIFIED BY {q(pw['publisher'])};",
        *grant_statements("s2b_publisher", admin, PUBLICATION_ADMIN_GRANTS),
        *grant_statements("s2b_publisher", public, PUBLICATION_PUBLIC_GRANTS),
        f"CREATE USER 's2b_reader'@'localhost' IDENTIFIED BY {q(pw['reader'])};",
        f"GRANT SELECT ON `{public}`.* TO 's2b_reader'@'localhost';",
    ]
    mysql_root(state, "\n".join(statements))
    for user, key in (("s2b_content", "content"), ("s2b_publisher", "publisher"),
                      ("s2b_reader", "reader"), ("s2b_runtime", "runtime")):
        write_option_file(state, user, pw[key])
    meta["status"] = "scoped-users-created"
    save_manifest(meta)


def run_java(tools: dict[str, Path], args: list[str], env: dict[str, str], timeout: int, category: str) -> str:
    try:
        result = subprocess.run([str(tools["java"]), *args], env=env, cwd=str(BACKEND),
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout, check=False)
    except subprocess.TimeoutExpired:
        raise Failure(f"{category}_TIMEOUT") from None
    except OSError:
        raise Failure(f"{category}_PROCESS_START") from None
    output = (result.stdout + result.stderr).decode("utf-8", errors="replace")
    if result.returncode != 0:
        reason = re.search(r"ACCOUNT_CLI_REJECTED reason=([A-Z_]+)", output)
        if reason:
            raise Failure(f"{category}_{reason.group(1)}")
        if "S2B_CLI_FIXTURE_REFUSED" in output:
            raise Failure(f"{category}_FIXTURE_GUARD")
        raise Failure(f"{category}_EXIT_{abs(result.returncode)}")
    return output


def tls_env(state: dict[str, object], extra: dict[str, str]) -> dict[str, str]:
    env = s1d.java_options_env(state["tls"], extra)
    env["JAVA_TOOL_OPTIONS"] = env["JAVA_TOOL_OPTIONS"] + " -Duser.timezone=UTC"
    return env


def run_migrations(state: dict[str, object], tools: dict[str, Path], build: dict[str, Path], pw: dict[str, str]) -> None:
    meta = state["meta"]
    port = int(meta["mysqlPort"])
    admin_env = tls_env(state, {
        "SOUTHLEAF_ADMIN_MIGRATION_SCHEMA": str(meta["schema"]),
        "SOUTHLEAF_ADMIN_MIGRATION_URL": s1d.jdbc_url(port, str(meta["schema"])),
        "SOUTHLEAF_ADMIN_MIGRATION_USERNAME": "s2b_admin_migrator",
        "SOUTHLEAF_ADMIN_MIGRATION_PASSWORD": pw["adminMigrator"],
    })
    cp = s1d.java_classpath(build["migrationClasses"], build["migrationDeps"])
    out = run_java(tools, ["-cp", cp, "cn.zhinan.adminmigration.AdminDatabaseMigrationMain", "--migrate"],
                   admin_env, 120, "ADMIN_MIGRATION_FAILED")
    require("ADMIN_SCHEMA_MIGRATION_PASS" in out, "ADMIN_MIGRATION_MARKER_MISSING")
    out = run_java(tools, ["-cp", cp, "cn.zhinan.adminmigration.AdminDatabaseMigrationMain", "--validate"],
                   admin_env, 90, "ADMIN_MIGRATION_VALIDATION_FAILED")
    require("ADMIN_SCHEMA_VALIDATION_PASS" in out, "ADMIN_VALIDATION_MARKER_MISSING")
    admin_v5 = mysql_root(state, f"SELECT COUNT(*) FROM `{meta['schema']}`.flyway_admin_schema_history WHERE version='5' AND success=1;")
    admin_v7 = mysql_root(state, f"SELECT COUNT(*) FROM `{meta['schema']}`.flyway_admin_schema_history WHERE version='7' AND success=1;")
    require(admin_v5 == "1" and admin_v7 == "1", "ADMIN_SCHEMA_V5_OR_V7_MISSING")

    media_root = Path(str(meta["root"])) / "media"
    public_env = tls_env(state, {
        "SOUTHLEAF_MIGRATION_URL": s1d.jdbc_url(port, str(meta["publicSchema"])),
        "SOUTHLEAF_MIGRATION_USERNAME": "s2b_public_migrator",
        "SOUTHLEAF_MIGRATION_PASSWORD": pw["publicMigrator"],
        "SOUTHLEAF_MEDIA_ROOT": str(media_root),
    })
    out = run_java(tools, ["-cp", str(PUBLIC_JAR), "-Dloader.main=cn.zhinan.uniapp.tooling.DatabaseMigrationMain",
                           "org.springframework.boot.loader.launch.PropertiesLauncher", "--seed-demo"],
                   public_env, 180, "PUBLIC_MIGRATION_FAILED")
    require("EXPLICIT_DEMO_IMPORT_PASS" in out, "PUBLIC_SEED_MARKER_MISSING")
    public = str(meta["publicSchema"])
    require(mysql_root(state, f"SELECT COUNT(*) FROM `{public}`.flyway_schema_history WHERE version='2' AND success=1;") == "1",
            "PUBLIC_SCHEMA_V2_MISSING")
    require(mysql_root(state, f"SELECT COUNT(*) FROM `{public}`.flyway_schema_history WHERE version='3' AND success=1;") == "1",
            "PUBLIC_SCHEMA_V3_MISSING")
    require(mysql_root(state, f"SELECT epoch FROM `{public}`.public_work_projection_epoch WHERE singleton_id=1;") == "0",
            "PUBLIC_EPOCH_NOT_ZERO")
    require(mysql_root(state, f"SELECT epoch FROM `{public}`.active_catalog_publication WHERE singleton_id=1;") == "0",
            "CATALOG_EPOCH_NOT_ZERO")
    meta["status"] = "admin-v7-and-public-v3-migrated"
    save_manifest(meta)


def account_cli(state: dict[str, object], tools: dict[str, Path], build: dict[str, Path], pw: dict[str, str],
                args: list[str], category: str) -> str:
    meta = state["meta"]
    env = tls_env(state, {
        "SOUTHLEAF_S2B_FIXTURE_KIND": FIXTURE_KIND,
        "SOUTHLEAF_S2B_CLI_MODE": "demo",
        "SOUTHLEAF_S2B_CLI_DB_URL": s1d.jdbc_url(int(meta["mysqlPort"]), str(meta["schema"])),
        "SOUTHLEAF_S2B_CLI_DB_USERNAME": "s2b_cli",
        "SOUTHLEAF_S2B_CLI_DB_PASSWORD": pw["cliDb"],
        "SOUTHLEAF_S2B_CLI_DB_SCHEMA": str(meta["schema"]),
        "SOUTHLEAF_S2B_CLI_DB_EXPECTED_USER": "s2b_cli@localhost",
        "SOUTHLEAF_S2B_ACCOUNT_PASSWORD": pw["account"],
    })
    cp = s1d.java_classpath(build["cliClasses"], build["cliDeps"], build["cliTestClasses"],
                            build["securityClasses"], build["credentialClasses"], build["dbPolicyClasses"])
    out = run_java(tools, ["-cp", cp, "cn.zhinan.adminaccountcli.S2BAccountCliFixtureMain", *args], env, 90, category)
    require("ACCOUNT_CLI_SUCCESS" in out, f"{category}_MARKER_MISSING")
    return out


def set_fixture_publish_permission(state: dict[str, object], account: str, enabled: bool) -> None:
    schema = str(state["meta"]["schema"])
    account_sql = q(account)
    delete = f"DELETE FROM `{schema}`.admin_account_permission WHERE account_id={account_sql} AND permission_code='CONTENT_PUBLISH';"
    if enabled:
        mysql_root(state, delete + f" INSERT INTO `{schema}`.admin_account_permission(account_id,permission_code) VALUES ({account_sql},'CONTENT_PUBLISH');")
    else:
        mysql_root(state, delete)


def set_permissions(state, tools, build, pw, account: str, permissions: list[str]) -> None:
    version = mysql_root(state, f"SELECT security_version FROM `{state['meta']['schema']}`.admin_account WHERE account_id={q(account)};")
    require(version.isdigit(), "ACCOUNT_VERSION_UNREADABLE")
    args = ["replace-permissions", "--command-id", str(uuid.uuid4()), "--account", account, "--expected-version", version]
    for permission in permissions:
        args += ["--permission", permission]
    account_cli(state, tools, build, pw, args, "CLI_REPLACE_PERMISSIONS_FAILED")


# --------------------------------------------------------------------------- processes


def hidden_console_popen(args: list[str], cwd: Path, env: dict[str, str]) -> subprocess.Popen[bytes]:
    startup = subprocess.STARTUPINFO()
    startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    startup.wShowWindow = 0
    return subprocess.Popen(args, cwd=str(cwd), env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            creationflags=subprocess.CREATE_NEW_CONSOLE, startupinfo=startup)


def start_admin_runtime(state: dict[str, object], tools: dict[str, Path], candidate: Path, pw: dict[str, str]) -> None:
    meta = state["meta"]
    require(verify_manifest(meta), "MANIFEST_MISMATCH")
    port = int(meta["mysqlPort"])
    env = tls_env(state, {
        "SOUTHLEAF_ADMIN_MODE": "demo",
        "SOUTHLEAF_ADMIN_DB_URL": s1d.jdbc_url(port, str(meta["schema"])),
        "SOUTHLEAF_ADMIN_DB_USERNAME": "s2b_runtime",
        "SOUTHLEAF_ADMIN_DB_PASSWORD": pw["runtime"],
        "SOUTHLEAF_ADMIN_DB_SCHEMA": str(meta["schema"]),
        "SOUTHLEAF_ADMIN_DB_EXPECTED_USER": "s2b_runtime@localhost",
        "SOUTHLEAF_ADMIN_WEB_MODE": "demo",
        "SOUTHLEAF_ADMIN_WEB_ORIGIN": f"https://localhost:{meta['webPort']}",
        "SOUTHLEAF_ADMIN_WEB_PORT": str(meta["webPort"]),
        "SOUTHLEAF_ADMIN_WEB_KEYSTORE_PATH": str(Path(str(meta["root"])) / "runtime-keystore.jks"),
        "SOUTHLEAF_ADMIN_WEB_KEYSTORE_PASSWORD": state["tls"]["keyStorePassword"],
        "SOUTHLEAF_ADMIN_WEB_KEYSTORE_TYPE": "JKS",
        "SOUTHLEAF_ADMIN_CONTENT_MODE": "enabled",
        "SOUTHLEAF_ADMIN_CONTENT_BUSINESS_DB_USERNAME": "s2b_content",
        "SOUTHLEAF_ADMIN_CONTENT_BUSINESS_DB_PASSWORD": pw["content"],
        "SOUTHLEAF_ADMIN_CONTENT_BUSINESS_DB_EXPECTED_USER": "s2b_content@localhost",
        "SOUTHLEAF_ADMIN_CONTENT_PUBLICATION_DB_USERNAME": "s2b_publisher",
        "SOUTHLEAF_ADMIN_CONTENT_PUBLICATION_DB_PASSWORD": pw["publisher"],
        "SOUTHLEAF_ADMIN_CONTENT_PUBLICATION_DB_EXPECTED_USER": "s2b_publisher@localhost",
        "SOUTHLEAF_ADMIN_CONTENT_PUBLIC_SCHEMA": str(meta["publicSchema"]),
        "SOUTHLEAF_ADMIN_CONTENT_MEDIA_ROOT": str(Path(str(meta["root"])) / "media"),
    })
    process = hidden_console_popen([str(tools["java"]), "-jar", str(candidate)], Path(str(meta["root"])), env)
    state["runtimeProcess"] = process
    meta["runtimePid"] = process.pid
    meta["status"] = "admin-content-runtime-starting"
    save_manifest(meta)
    ready: Queue[str] = Queue()
    diagnostics: Queue[str] = Queue()
    threading.Thread(target=s1d.drain_runtime_stdout, args=(process, ready, diagnostics), daemon=True).start()
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            marker = ready.get(timeout=0.25)
        except Empty:
            if process.poll() is not None:
                diagnostic = s1d.take_runtime_diagnostic(diagnostics)
                raise Failure(f"ADMIN_RUNTIME_EXITED_{diagnostic}" if diagnostic else "ADMIN_RUNTIME_EXITED_BEFORE_READY")
            continue
        if marker.startswith("ADMIN_RUNTIME_REFUSED:"):
            reason = marker.partition(":")[2]
            require(bool(re.fullmatch(r"[A-Z0-9_]+", reason)), "ADMIN_RUNTIME_FAILURE_MARKER_INVALID")
            if reason == "INTERNAL":
                diagnostic = s1d.take_runtime_diagnostic(diagnostics)
                reason = f"INTERNAL_{diagnostic}" if diagnostic else reason
            raise Failure(f"ADMIN_RUNTIME_REFUSED_{reason}")
        require(marker == READY_MARKER, "ADMIN_RUNTIME_READY_MARKER_INVALID")
        listeners = s1d.netstat_listeners(process.pid)
        require(len(listeners) == 1 and s1d.is_loopback_listener(listeners[0], int(meta["webPort"])),
                "ADMIN_LISTENER_SCOPE")
        s1d.runtime_http_probe(int(meta["webPort"]), str(state["tls"]["caCert"]))
        meta["status"] = "admin-content-runtime-ready"
        save_manifest(meta)
        return
    raise Failure("ADMIN_RUNTIME_READY_TIMEOUT")


def start_public_runtime(state: dict[str, object], tools: dict[str, Path], pw: dict[str, str]) -> None:
    meta = state["meta"]
    workdir = Path(str(meta["root"])) / "public-runtime"
    workdir.mkdir()
    env = tls_env(state, {
        "SOUTHLEAF_DB_URL": s1d.jdbc_url(int(meta["mysqlPort"]), str(meta["publicSchema"])),
        "SOUTHLEAF_DB_USERNAME": "s2b_reader",
        "SOUTHLEAF_DB_PASSWORD": pw["reader"],
        "SOUTHLEAF_MEDIA_ROOT": str(Path(str(meta["root"])) / "media"),
        "SOUTHLEAF_BIND_ADDRESS": "127.0.0.1",
        "PORT": str(meta["publicPort"]),
    })
    process = hidden_console_popen([str(tools["java"]), "-jar", str(PUBLIC_JAR)], workdir, env)
    state["publicProcess"] = process
    meta["publicPid"] = process.pid
    meta["status"] = "public-runtime-starting"
    save_manifest(meta)
    public_diagnostics: Queue[str] = Queue(maxsize=100)
    state["publicDiagnostics"] = public_diagnostics
    def drain_public() -> None:
        if process.stdout is None:
            return
        for raw in iter(process.stdout.readline, b""):
            line = raw.decode("utf-8", errors="replace")
            match = re.search(r"([A-Za-z_$][A-Za-z0-9_.$]*(?:Exception|Error))(?=[:\s]|$)", line)
            if match:
                try:
                    public_diagnostics.put_nowait(match.group(1).rsplit(".", 1)[-1])
                except Exception:
                    try:
                        public_diagnostics.get_nowait()
                        public_diagnostics.put_nowait(match.group(1).rsplit(".", 1)[-1])
                    except Exception:
                        pass
    threading.Thread(target=drain_public, daemon=True).start()
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        require(process.poll() is None, "PUBLIC_RUNTIME_EXITED_BEFORE_READY")
        status, _, _ = public_get(state, "/api/v1/public/home")
        if status == 200:
            listeners = s1d.netstat_listeners(process.pid)
            require(len(listeners) == 1 and s1d.is_loopback_listener(listeners[0], int(meta["publicPort"])),
                    "PUBLIC_LISTENER_SCOPE")
            meta["status"] = "public-runtime-ready"
            save_manifest(meta)
            return
        time.sleep(0.5)
    raise Failure("PUBLIC_RUNTIME_READY_TIMEOUT")


def stop_process(state: dict[str, object], key: str, pid_key: str, port_key: str) -> tuple[bool, bool]:
    process = state.get(key)
    meta = state["meta"]
    if not isinstance(process, subprocess.Popen):
        return True, True
    if process.pid != meta.get(pid_key):
        return False, False
    graceful = True
    if process.poll() is None:
        try:
            s1d.ctrl_c_private_console(process)
            process.wait(timeout=25)
        except Exception:
            graceful = False
            try:
                process.terminate()
                process.wait(timeout=15)
            except Exception:
                try:
                    process.kill()
                    process.wait(timeout=10)
                except Exception:
                    return False, False
    else:
        graceful = False
    return process.poll() is not None and not s1d.port_owner(int(meta[port_key])), graceful


def remove_owned_fixture(state: dict[str, object]) -> dict[str, bool]:
    meta = state["meta"]
    result = {"browserStopped": True, "publicStopped": False, "runtimeStopped": False,
              "mysqlStopped": False, "fixtureRemoved": False}
    if not verify_manifest(meta):
        return result
    browser = state.get("browserProcess")
    if isinstance(browser, subprocess.Popen) and browser.poll() is None:
        subprocess.run(["taskkill.exe", "/PID", str(browser.pid), "/T", "/F"], stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, timeout=20, check=False)
        try:
            browser.wait(timeout=10)
        except subprocess.TimeoutExpired:
            result["browserStopped"] = False
    public_ok, public_graceful = stop_process(state, "publicProcess", "publicPid", "publicPort")
    runtime_ok, runtime_graceful = stop_process(state, "runtimeProcess", "runtimePid", "webPort")
    mysql_ok, mysql_graceful = s1d.stop_mysql(state)
    result.update(publicStopped=public_ok, runtimeStopped=runtime_ok, mysqlStopped=mysql_ok)
    meta.update(publicGracefulShutdown=public_graceful, runtimeGracefulShutdown=runtime_graceful,
                mysqlGracefulShutdown=mysql_graceful)
    save_manifest(meta)
    if not all(result[k] for k in ("browserStopped", "publicStopped", "runtimeStopped", "mysqlStopped")):
        return result
    root = Path(str(meta["root"]))
    if root.name != str(meta["runId"]) or root.parent.resolve().name != BASE_NAME or root.is_symlink():
        return result
    if not verify_manifest(meta) or any(s1d.port_owner(int(meta[k])) for k in ("mysqlPort", "webPort", "publicPort")):
        return result
    try:
        shutil.rmtree(root)
    except OSError:
        return result
    result["fixtureRemoved"] = not root.exists()
    return result


# --------------------------------------------------------------------------- HTTP clients


class AdminClient:
    """Explicit cookie handling (no cookiejar heuristics for __Host- cookies on localhost)."""

    def __init__(self, port: int, ca_cert: str):
        self.base = f"https://localhost:{port}"
        self.context = ssl.create_default_context(cafile=ca_cert)
        self.cookies: dict[str, str] = {}
        self.csrf: str | None = None
        self.lock = threading.Lock()

    def request(self, method: str, path: str, body: bytes | None = None,
                headers: dict[str, str] | None = None) -> tuple[int, dict[str, str], bytes]:
        all_headers = {"Accept": "application/json", "Origin": self.base}
        with self.lock:
            if self.cookies:
                all_headers["Cookie"] = "; ".join(f"{k}={v}" for k, v in self.cookies.items())
        all_headers.update(headers or {})
        req = urllib.request.Request(self.base + path, data=body, headers=all_headers, method=method)
        try:
            with urllib.request.urlopen(req, context=self.context, timeout=30) as response:
                status, raw_headers, payload = response.status, response.headers, response.read()
        except urllib.error.HTTPError as error:
            status, raw_headers, payload = error.code, error.headers, error.read()
        except (urllib.error.URLError, OSError, TimeoutError):
            raise Failure("ADMIN_HTTP_TRANSPORT") from None
        with self.lock:
            for cookie in raw_headers.get_all("Set-Cookie") or []:
                pair = cookie.split(";", 1)[0]
                name, _, value = pair.partition("=")
                if "max-age=0" in cookie.lower() or value == "":
                    self.cookies.pop(name.strip(), None)
                else:
                    self.cookies[name.strip()] = value.strip()
        return status, {k.lower(): v for k, v in raw_headers.items()}, payload

    def fetch_csrf(self) -> str:
        status, _, payload = self.request("GET", "/api/admin/v1/auth/csrf")
        require(status == 200, "ADMIN_CSRF_FAILED")
        self.csrf = json.loads(payload)["data"]["csrfToken"]
        return self.csrf

    def login(self, account: str, password: str) -> None:
        self.cookies.clear()
        self.fetch_csrf()
        status, _, _ = self.json("POST", "/api/admin/v1/auth/login", {"accountId": account, "password": password})
        require(status == 200, f"ADMIN_LOGIN_HTTP_{status}")
        self.fetch_csrf()

    def json(self, method: str, path: str, value: object | None = None,
             headers: dict[str, str] | None = None) -> tuple[int, dict[str, str], dict]:
        extra = {"X-CSRF-TOKEN": self.csrf or ""} if method not in ("GET", "HEAD") else {}
        body = None
        if value is not None:
            body = json.dumps(value).encode("utf-8")
            extra["Content-Type"] = "application/json"
        extra.update(headers or {})
        status, response_headers, payload = self.request(method, path, body, extra)
        try:
            parsed = json.loads(payload) if payload else {}
        except ValueError:
            parsed = {"_raw": payload[:600].decode("utf-8", errors="replace")}
        return status, response_headers, parsed

    def upload(self, data: bytes, content_type: str, key: str | None,
               filename: str = "upload.png") -> tuple[int, dict]:
        boundary = "s2b" + secrets.token_hex(12)
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
                f"Content-Type: {content_type}\r\n\r\n").encode("ascii") + data + f"\r\n--{boundary}--\r\n".encode("ascii")
        headers = {"X-CSRF-TOKEN": self.csrf or "", "Content-Type": f"multipart/form-data; boundary={boundary}"}
        if key:
            headers["Idempotency-Key"] = key
        status, _, payload = self.request("POST", "/api/admin/v1/media/uploads", body, headers)
        try:
            return status, json.loads(payload) if payload else {}
        except ValueError:
            return status, {}


def public_get(state: dict[str, object], path: str, method: str = "GET") -> tuple[int, dict[str, str], dict]:
    url = f"http://127.0.0.1:{state['meta']['publicPort']}{path}"
    req = urllib.request.Request(url, headers={"Accept": "application/json"}, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            status, headers, payload = response.status, response.headers, response.read()
    except urllib.error.HTTPError as error:
        status, headers, payload = error.code, error.headers, error.read()
    except (urllib.error.URLError, OSError, TimeoutError):
        return 0, {}, {}
    if method == "HEAD" or not payload:
        return status, dict(headers), {}
    try:
        return status, dict(headers), json.loads(payload)
    except ValueError:
        return status, dict(headers), {"_bytes": hashlib.sha256(payload).hexdigest()}


def error_code(body: dict) -> str | None:
    if isinstance(body.get("code"), str):
        return body["code"]
    nested = body.get("error")
    return nested.get("code") if isinstance(nested, dict) else None


# --------------------------------------------------------------------------- synthetic media


def png_bytes(width: int, height: int, seed: int, metadata: bool = True) -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    row_cache = []
    for y in range(height):
        row = bytearray([0])
        for x in range(width):
            row += bytes(((x * 3 + seed * 41) & 0xFF, (y * 5 + seed * 17) & 0xFF, ((x + y) * 2 + seed) & 0xFF))
        row_cache.append(bytes(row))
    raw = b"".join(row_cache)
    parts = [b"\x89PNG\r\n\x1a\n", chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))]
    if metadata:
        parts.append(chunk(b"tEXt", b"Comment\x00" + SECRET_METADATA))
    parts += [chunk(b"IDAT", zlib.compress(raw, 6)), chunk(b"IEND", b"")]
    return b"".join(parts)


def png_chunk_types(data: bytes) -> list[bytes]:
    require(data.startswith(b"\x89PNG\r\n\x1a\n"), "DERIVATIVE_NOT_PNG")
    types, offset = [], 8
    while offset + 8 <= len(data):
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        types.append(data[offset + 4:offset + 8])
        offset += 12 + length
    return types


def media_digest(media_ids: list[str]) -> str:
    return hashlib.sha256("\n".join(sorted(media_ids)).encode("utf-8")).hexdigest()


# --------------------------------------------------------------------------- S2-B checks


class Checks:
    def __init__(self) -> None:
        self.names: list[str] = []
        self.facts: dict[str, object] = {}

    def ok(self, name: str, condition: bool, category: str | None = None) -> None:
        require(condition, category or ("CHECK_" + re.sub(r"[^A-Z0-9]+", "_", name.upper()).strip("_")))
        self.names.append(name)


def key() -> str:
    return secrets.token_hex(16)


def scalar(state: dict[str, object], sql: str) -> str:
    return mysql_root(state, sql)


def epoch(state: dict[str, object]) -> int:
    return int(scalar(state, f"SELECT epoch FROM `{state['meta']['publicSchema']}`.public_work_projection_epoch WHERE singleton_id=1;"))


def put_gate(state: dict[str, object], work_id: str, scope: str, digest: str, *, valid_seconds: int,
             provenance: str = "TEST_SYNTHETIC", gate_state: str = "VALID") -> None:
    admin = state["meta"]["schema"]
    revision = "s2b-rev-" + secrets.token_hex(6)
    fact = hashlib.sha256(f"{work_id}|{revision}|{digest}".encode()).hexdigest()
    # Use the public JVM clock, matching the policy evaluation clock across Windows time-zone
    # configurations; never derive test authority windows from MySQL or Python host wall time.
    status, _, public_home = public_get(state, "/api/v1/public/home")
    require(status == 200 and isinstance(public_home.get("meta", {}).get("serverTime"), str),
            "PUBLIC_CLOCK_UNAVAILABLE")
    now = datetime.fromisoformat(public_home["meta"]["serverTime"].replace("Z", "+00:00"))
    valid_from = q((now - timedelta(seconds=60)).strftime("%Y-%m-%d %H:%M:%S.%f"))
    valid_until = q((now + timedelta(seconds=int(valid_seconds))).strftime("%Y-%m-%d %H:%M:%S.%f"))
    recorded_at = q(now.strftime("%Y-%m-%d %H:%M:%S.%f"))
    mysql_root(state, (
        f"REPLACE INTO `{admin}`.admin_work_authorization_gate (work_id,authority_revision,state,subject_scope_ref,"
        f"media_scope_sha256,purpose,channel,valid_from,valid_until,provenance,fact_sha256,recorded_at) VALUES ("
        f"{q(work_id)},{q(revision)},{q(gate_state)},{q(scope)},{q(digest)},'PUBLIC_WORK_DISPLAY','WECHAT_MINI_PROGRAM',"
        f"{valid_from},{valid_until},{q(provenance)},{q(fact)},{recorded_at});"))


def run_node(tools: dict[str, Path], script: Path, env_extra: dict[str, str], marker: str, category: str) -> str:
    try:
        result = subprocess.run([str(tools["node"]), str(script)], cwd=str(script.parent.parent),
                                env=s1d.child_env(env_extra), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=90, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise Failure(f"{category}_PROCESS") from None
    output = result.stdout.decode("utf-8", errors="replace")
    require(result.returncode == 0 and marker in output, category)
    return output.strip().splitlines()[-1]


def chain(state, tools, work_id: str, expect: str, title: str | None = None) -> str:
    env = {"SOUTHLEAF_API_ORIGIN": f"http://127.0.0.1:{state['meta']['publicPort']}",
           "SOUTHLEAF_S2B_WORK_ID": work_id, "SOUTHLEAF_S2B_EXPECT": expect}
    if title:
        env["SOUTHLEAF_S2B_TITLE"] = title
    return run_node(tools, CHAIN_TEST, env, f"S2B_MINIPROGRAM_CHAIN_PASS:{expect}", f"MINIPROGRAM_CHAIN_{expect.upper()}_FAILED")


def run_browser(state, tools, account: str, pw: dict[str, str], work_id: str, title: str) -> int:
    meta = state["meta"]
    env = s1d.child_env({
        "SOUTHLEAF_S2B_FIXTURE_KIND": FIXTURE_KIND,
        "S2B_L5_BASE_URL": f"https://localhost:{meta['webPort']}",
        "S2B_L5_ACCOUNT": account,
        "S2B_L5_ACCOUNT_PASSWORD": pw["account"],
        "S2B_L5_FIXTURE_ROOT": str(meta["root"]),
        "S2B_L5_PROFILE_DIR": str(Path(str(meta["root"])) / "browser-profile"),
        "S2B_L5_WORK_ID": work_id,
        "S2B_L5_NEW_TITLE": title,
    })
    process = subprocess.Popen([str(tools["node"]), str(BROWSER_TEST)], cwd=str(WEB), env=env,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               creationflags=subprocess.CREATE_NEW_PROCESS_GROUP)
    state["browserProcess"] = process
    try:
        stdout, stderr = process.communicate(timeout=150)
    except subprocess.TimeoutExpired:
        subprocess.run(["taskkill.exe", "/PID", str(process.pid), "/T", "/F"], stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, timeout=20, check=False)
        raise Failure("BROWSER_TIMEOUT") from None
    finally:
        if process.poll() is not None:
            state["browserProcess"] = None
    marker = re.search(rb"S2B_BROWSER_PASS:([0-9]+)", stdout)
    if process.returncode != 0 or marker is None:
        stage = re.search(rb"S2B_BROWSER_FAILED:([A-Za-z0-9_-]+)", stderr)
        code = re.sub(r"[^A-Z0-9]+", "_", stage.group(1).decode().upper()) if stage else "UNKNOWN"
        raise Failure(f"BROWSER_CHECK_FAILED_{code}")
    return int(marker.group(1))


def denied(result: subprocess.CompletedProcess[bytes]) -> bool:
    text = result.stderr.decode("utf-8", errors="replace")
    return result.returncode != 0 and bool(re.search(r"ERROR (1142|1143|1044|1227)", text))


def grant_checks(state: dict[str, object], c: Checks) -> None:
    admin, public = state["meta"]["schema"], state["meta"]["publicSchema"]
    positive = [
        ("s2b_content", f"SELECT COUNT(*) FROM `{admin}`.admin_work_case;"),
        ("s2b_publisher", f"SELECT epoch FROM `{public}`.public_work_projection_epoch;"),
        ("s2b_reader", f"SELECT COUNT(*) FROM `{public}`.public_work_release;"),
    ]
    for user, sql in positive:
        result = mysql_as(state, user, sql)
        c.ok(f"grant-positive-{user}", result.returncode == 0, "GRANT_POSITIVE_FAILED")
    negative = [
        ("s2b_content", f"SELECT COUNT(*) FROM `{public}`.public_work_release;"),
        ("s2b_content", f"INSERT INTO `{admin}`.admin_work_publication_state (work_id) VALUES ('x');"),
        ("s2b_content", f"DELETE FROM `{admin}`.admin_media_asset WHERE 1=0;"),
        ("s2b_content", f"SELECT COUNT(*) FROM `{admin}`.admin_session;"),
        ("s2b_content", f"SELECT COUNT(*) FROM `{admin}`.admin_work_authorization_gate;"),
        ("s2b_publisher", f"SELECT COUNT(*) FROM `{public}`.public_work_release;"),
        ("s2b_publisher", f"DELETE FROM `{public}`.active_work_publication WHERE 1=0;"),
        ("s2b_publisher", f"UPDATE `{admin}`.admin_work_case SET work_id=work_id WHERE 1=0;"),
        ("s2b_publisher", f"INSERT INTO `{admin}`.admin_work_version (work_id) VALUES ('x');"),
        ("s2b_publisher", f"UPDATE `{admin}`.admin_work_authorization_gate SET state='VALID' WHERE 1=0;"),
        ("s2b_publisher", f"SELECT COUNT(*) FROM `{admin}`.admin_account;"),
        ("s2b_publisher", f"DELETE FROM `{public}`.public_work_release WHERE 1=0;"),
        ("s2b_reader", f"INSERT INTO `{public}`.active_work_publication (work_id) VALUES ('x');"),
        ("s2b_reader", f"UPDATE `{public}`.public_work_projection_epoch SET epoch=epoch+1 WHERE 1=0;"),
        ("s2b_reader", f"SELECT COUNT(*) FROM `{admin}`.admin_work_case;"),
        ("s2b_runtime", f"SELECT COUNT(*) FROM `{admin}`.admin_work_case;"),
        ("s2b_runtime", f"SELECT COUNT(*) FROM `{public}`.active_work_publication;"),
    ]
    for index, (user, sql) in enumerate(negative):
        c.ok(f"grant-negative-{index:02d}-{user}", denied(mysql_as(state, user, sql)), f"GRANT_NEGATIVE_{index:02d}_NOT_DENIED")
    c.facts["grantNegativeCount"] = len(negative)


def s2b_flow(state, tools, build, pw, account: str, c: Checks) -> None:
    meta = state["meta"]
    admin, public = meta["schema"], meta["publicSchema"]
    client = AdminClient(int(meta["webPort"]), str(state["tls"]["caCert"]))
    client.login(account, pw["account"])
    c.ok("admin-login-with-content-capabilities", True)

    # ---------------------------------------------------------------- rejected uploads
    before_assets = scalar(state, f"SELECT COUNT(*) FROM `{admin}`.admin_media_asset;")
    good = [png_bytes(640, 480, seed) for seed in (1, 2, 3)]
    status, body = client.upload(good[0], "image/png", None)
    c.ok("upload-requires-idempotency-key", status == 400, "UPLOAD_KEY_NOT_REQUIRED")
    status, body = client.upload(b"definitely not an image" * 10, "image/png", key())
    c.ok("upload-rejects-non-image", status in (415, 422) and error_code(body) in ("MEDIA_REJECTED", "MEDIA_UNSUPPORTED_TYPE"),
         "UPLOAD_GARBAGE_ACCEPTED")
    c.facts["garbageUpload"] = [status, error_code(body)]
    status, body = client.upload(good[0], "text/plain", key())
    c.ok("upload-rejects-undeclared-type", status in (415, 422), "UPLOAD_TEXT_TYPE_ACCEPTED")
    c.facts["textTypeUpload"] = [status, error_code(body)]
    status, body = client.upload(png_bytes(8193, 1, 9, metadata=False), "image/png", key())
    c.ok("upload-rejects-oversized-dimensions", status == 422 and error_code(body) == "MEDIA_REJECTED", "UPLOAD_DIMENSION_ACCEPTED")
    try:
        status, body = client.upload(b"\0" * (4 * 1024 * 1024 + 70 * 1024), "image/png", key())
    except Failure as error:
        # The server may close the connection after sending 413 before the body is drained.
        require(error.category == "ADMIN_HTTP_TRANSPORT", error.category)
        status, body = -1, {}
    c.facts["oversizeUpload"] = status
    c.ok("upload-rejects-oversized-request", status in (413, -1), "UPLOAD_OVERSIZE_ACCEPTED")
    c.ok("rejected-uploads-create-no-assets",
         scalar(state, f"SELECT COUNT(*) FROM `{admin}`.admin_media_asset;") == before_assets, "REJECTED_UPLOAD_PERSISTED")

    # ---------------------------------------------------------------- accepted uploads + idempotency
    media_ids = []
    upload_key = key()
    status, first = client.upload(good[0], "image/png", upload_key)
    c.ok("upload-accepts-png", status == 200 and first["data"]["replayed"] is False, f"UPLOAD_HTTP_{status}")
    media_ids.append(first["data"]["media"]["mediaId"])
    status, replay = client.upload(good[0], "image/png", upload_key)
    c.ok("upload-replay-same-key-same-bytes",
         status == 200 and replay["data"]["replayed"] is True and replay["data"]["media"]["mediaId"] == media_ids[0],
         "UPLOAD_REPLAY_FAILED")
    status, body = client.upload(good[1], "image/png", upload_key)
    c.ok("upload-same-key-different-bytes-409", status == 409 and error_code(body) == "IDEMPOTENCY_KEY_REUSE",
         "UPLOAD_KEY_REUSE_NOT_REJECTED")
    for data in good[1:]:
        status, body = client.upload(data, "image/png", key())
        require(status == 200, f"UPLOAD_HTTP_{status}")
        media_ids.append(body["data"]["media"]["mediaId"])
    status, headers, derivative = client.request("GET", f"/api/admin/v1/media/{media_ids[0]}/preview/content")
    c.ok("derivative-served-no-store", status == 200 and "no-store" in headers.get("cache-control", ""), "DERIVATIVE_FETCH_FAILED")
    chunk_types = set(png_chunk_types(derivative)) if derivative.startswith(b"\x89PNG") else set()
    c.ok("derivative-metadata-stripped",
         SECRET_METADATA not in derivative and not ({b"tEXt", b"iTXt", b"zTXt", b"eXIf"} & chunk_types),
         "DERIVATIVE_METADATA_SURVIVED")
    original_sha = hashlib.sha256(good[0]).hexdigest()
    media_files = [p.name for p in (Path(str(meta["root"])) / "media").iterdir()]
    c.ok("original-not-retained", not any(name.startswith(original_sha) for name in media_files), "ORIGINAL_RETAINED")

    # ---------------------------------------------------------------- work + drafts
    create_key = key()
    status, headers, body = client.json("POST", "/api/admin/v1/works", {}, {"Idempotency-Key": create_key})
    require(status == 200, f"WORK_CREATE_HTTP_{status}")
    work_id = body["data"]["work"]["workId"]
    version = body["data"]["work"]["version"]
    c.ok("work-create-strong-etag", headers.get("etag") == f'"v{version}"', "WORK_ETAG_INVALID")
    status, _, replay = client.json("POST", "/api/admin/v1/works", {}, {"Idempotency-Key": create_key})
    c.ok("work-create-replay", status == 200 and replay["data"]["replayed"] is True and replay["data"]["work"]["workId"] == work_id,
         "WORK_CREATE_REPLAY_FAILED")
    scope = "s2b-subject-" + meta["runId"]
    title_v1 = "S2B 合成作品 " + meta["runId"]
    draft = {"title": title_v1, "summary": "仅用于一次性 S2-B 本地验证的合成作品。", "subjectScopeRef": scope,
             "businessIds": [DEMO_BUSINESS], "media": [{"mediaId": m, "altText": f"合成图 {i}"} for i, m in enumerate(media_ids[:2])],
             "coverMediaId": media_ids[0]}
    status, _, body = client.json("PUT", f"/api/admin/v1/works/{work_id}", draft)
    c.ok("draft-save-requires-if-match", status == 428, "IF_MATCH_NOT_REQUIRED")
    status, headers, body = client.json("PUT", f"/api/admin/v1/works/{work_id}", draft, {"If-Match": f'"v{version}"'})
    require(status == 200, f"DRAFT_SAVE_HTTP_{status}")
    stale = version
    version = body["data"]["version"]
    c.ok("draft-save-increments-version", version == stale + 1 and headers.get("etag") == f'"v{version}"', "DRAFT_VERSION_INVALID")
    status, _, body = client.json("PUT", f"/api/admin/v1/works/{work_id}", draft, {"If-Match": f'"v{stale}"'})
    c.ok("stale-if-match-409", status == 409 and error_code(body) == "VERSION_CONFLICT", "STALE_IF_MATCH_ACCEPTED")
    digest = media_digest(media_ids[:2])

    def publish(op: str, ver: int, k: str) -> tuple[int, dict]:
        s, _, b = client.json("POST", f"/api/admin/v1/works/{work_id}/{op}", {}, {"If-Match": f'"v{ver}"', "Idempotency-Key": k})
        return s, b

    # ---------------------------------------------------------------- gates
    status, _, body = client.json("GET", f"/api/admin/v1/works/{work_id}/preview")
    c.ok("preview-blocks-missing-authorization",
         status == 200 and body["data"]["publishable"] is False and body["data"]["blockingReason"] == "AUTHORIZATION_MISSING",
         "PREVIEW_MISSING_GATE_NOT_BLOCKED")
    status, body = publish("publish", version, key())
    c.ok("publish-blocked-without-authorization", status == 422 and error_code(body) == "PUBLICATION_BLOCKED", "PUBLISH_WITHOUT_GATE")
    put_gate(state, work_id, scope, media_digest(media_ids), valid_seconds=3600)
    status, body = publish("publish", version, key())
    c.ok("publish-blocked-media-scope-mismatch", status == 422 and body.get("reason") == "AUTHORIZATION_MEDIA_SCOPE",
         "PUBLISH_MEDIA_SCOPE_NOT_BLOCKED")
    put_gate(state, work_id, scope, digest, valid_seconds=3600, provenance="VERIFIED_AUTHORITY")
    status, body = publish("publish", version, key())
    c.ok("demo-rejects-verified-provenance", status == 422 and body.get("reason") == "AUTHORIZATION_PROVENANCE",
         "PUBLISH_PROVENANCE_NOT_BLOCKED")
    put_gate(state, work_id, scope, digest, valid_seconds=3600)
    c.ok("epoch-unchanged-by-blocked-publishes", epoch(state) == 0, "EPOCH_CHANGED_BY_BLOCKED")

    # ---------------------------------------------------------------- capability
    set_fixture_publish_permission(state, account, False)
    client.login(account, pw["account"])
    status, body = publish("publish", version, key())
    c.ok("publish-requires-content-publish-capability", status == 403 and error_code(body) == "CAPABILITY_REQUIRED",
         "CAPABILITY_NOT_ENFORCED")
    set_fixture_publish_permission(state, account, True)
    client.login(account, pw["account"])

    # ---------------------------------------------------------------- rollback
    counts_sql = (f"SELECT (SELECT COUNT(*) FROM `{public}`.public_work_release),(SELECT COUNT(*) FROM `{public}`.active_work_publication),"
                  f"(SELECT COUNT(*) FROM `{admin}`.admin_publication_command),(SELECT COUNT(*) FROM `{admin}`.admin_work_publication_state);")
    before = scalar(state, counts_sql)
    mysql_root(state, f"REVOKE INSERT ON `{public}`.`public_work_release_variant` FROM 's2b_publisher'@'localhost';")
    rollback_key = key()
    try:
        status, body = publish("publish", version, rollback_key)
    finally:
        mysql_root(state, f"GRANT INSERT ON `{public}`.`public_work_release_variant` TO 's2b_publisher'@'localhost';")
    c.ok("mid-transaction-failure-503", status == 503 and error_code(body) == "CONTENT_SERVICE_UNAVAILABLE", f"ROLLBACK_HTTP_{status}")
    c.ok("mid-transaction-failure-rolls-back", scalar(state, counts_sql) == before and epoch(state) == 0, "ROLLBACK_LEFT_ROWS")
    status, body = public_get(state, f"/api/v1/public/works/{work_id}")[0], {}
    c.ok("rolled-back-work-not-public", status == 404, "ROLLBACK_WORK_VISIBLE")

    # ---------------------------------------------------------------- publish + replay + lookup
    status, body = publish("publish", version, rollback_key)
    c.ok("publish-succeeds-after-rollback-same-key", status == 200 and body["data"]["replayed"] is False, f"PUBLISH_HTTP_{status}")
    command_id = body["data"]["commandId"]
    status, body = publish("publish", version, rollback_key)
    c.ok("publish-replay-returns-same-command", status == 200 and body["data"]["replayed"] is True and body["data"]["commandId"] == command_id,
         "PUBLISH_REPLAY_FAILED")
    status, _, body = client.json("GET", f"/api/admin/v1/commands/{command_id}")
    c.ok("unknown-commit-lookup-by-command", status == 200 and body["data"]["status"] == "SUCCEEDED", "COMMAND_LOOKUP_FAILED")
    status, _, body = client.json("GET", f"/api/admin/v1/commands/{uuid.uuid4()}")
    c.ok("unknown-command-404", status == 404, "UNKNOWN_COMMAND_NOT_404")
    c.ok("publish-bumps-epoch-once", epoch(state) == 1, "EPOCH_NOT_ONE")
    status, _, body = public_get(state, f"/api/v1/public/works/{work_id}")
    c.ok("public-get-after-publish", status == 200 and body.get("data", {}).get("title") == title_v1, f"PUBLIC_GET_{status}")
    c.ok("public-head-after-publish", public_get(state, f"/api/v1/public/works/{work_id}", "HEAD")[0] == 200, "PUBLIC_HEAD_FAILED")
    c.ok("public-content-version-carries-epoch", str(body.get("meta", {}).get("contentVersion", "")).endswith("+e1"),
         "CONTENT_VERSION_NO_EPOCH")
    variant = scalar(state, f"SELECT sha256 FROM `{public}`.public_work_release_variant v JOIN `{public}`.active_work_publication a "
                            f"ON a.release_id=v.release_id WHERE a.work_id={q(work_id)} AND v.media_id={q(media_ids[0])} AND v.variant='preview';")
    status, _, media_body = public_get(state, f"/api/v1/public/media/{media_ids[0]}/preview/content")
    c.ok("public-media-bytes-match-release", status == 200 and media_body.get("_bytes") == variant, "PUBLIC_MEDIA_MISMATCH")
    c.facts["chainVisible"] = chain(state, tools, work_id, "visible", title_v1)
    c.ok("miniprogram-chain-visible", True)

    # ---------------------------------------------------------------- cursor invalidation by epoch
    status, _, page = public_get(state, "/api/v1/public/works?limit=2&sort=newest")
    cursor = page.get("meta", {}).get("nextCursor")
    c.ok("cursor-issued-under-epoch-1", status == 200 and bool(cursor), "CURSOR_NOT_ISSUED")

    # ---------------------------------------------------------------- key reuse with different payload
    draft_v2 = dict(draft, title=title_v1 + " 修订")
    status, headers, body = client.json("PUT", f"/api/admin/v1/works/{work_id}", draft_v2, {"If-Match": f'"v{version}"'})
    require(status == 200, f"DRAFT_SAVE2_HTTP_{status}")
    published_version, version = version, body["data"]["version"]
    status, body = publish("publish", version, rollback_key)
    c.ok("publish-key-reuse-different-payload-409", status == 409 and error_code(body) == "IDEMPOTENCY_KEY_REUSE", "KEY_REUSE_NOT_REJECTED")
    status, body = publish("publish", published_version, key())
    c.ok("publish-stale-version-409", status == 409 and error_code(body) == "VERSION_CONFLICT", "STALE_PUBLISH_ACCEPTED")

    # ---------------------------------------------------------------- 5 concurrent same-key publishes
    same_key = key()
    epoch_before = epoch(state)
    with ThreadPoolExecutor(max_workers=5) as pool:
        results = list(pool.map(lambda _: publish("publish", version, same_key), range(5)))
    statuses = [s for s, _ in results]
    commands = {b.get("data", {}).get("commandId") for s, b in results if s == 200}
    fresh = [b for s, b in results if s == 200 and b["data"]["replayed"] is False]
    c.facts["concurrentSameKeyStatuses"] = statuses
    c.ok("concurrent-same-key-single-effect",
         all(s == 200 for s in statuses) and len(commands) == 1 and len(fresh) == 1 and epoch(state) == epoch_before + 1
         and scalar(state, f"SELECT COUNT(*) FROM `{admin}`.admin_publication_command WHERE idempotency_key={q(same_key)};") == "1",
         "CONCURRENT_SAME_KEY_NOT_SINGLE")
    status, _, body = public_get(state, f"/api/v1/public/works?limit=2&sort=newest&cursor={urllib.request.quote(cursor)}")
    c.ok("epoch-change-expires-cursor", status == 409 and error_code(body) == "PUBLIC_CURSOR_EXPIRED", f"CURSOR_NOT_EXPIRED_{status}")

    # ---------------------------------------------------------------- draft save vs publish race
    status, _, current = client.json("GET", f"/api/admin/v1/works/{work_id}")
    version = current["data"]["version"]
    race_title = title_v1 + " 竞态"
    with ThreadPoolExecutor(max_workers=2) as pool:
        save_future = pool.submit(client.json, "PUT", f"/api/admin/v1/works/{work_id}", dict(draft, title=race_title), {"If-Match": f'"v{version}"'})
        pub_future = pool.submit(publish, "publish", version, key())
        save_status = save_future.result()[0]
        pub_status, pub_body = pub_future.result()
    c.facts["saveVsPublish"] = [save_status, pub_status]
    released_version = scalar(state, f"SELECT r.work_version FROM `{public}`.public_work_release r JOIN `{public}`.active_work_publication a "
                                     f"ON a.release_id=r.release_id WHERE a.work_id={q(work_id)} AND a.visible=1;")
    c.ok("save-publish-race-publishes-exact-revision",
         save_status == 200 and pub_status in (200, 409) and released_version == str(version), "SAVE_PUBLISH_RACE_INCONSISTENT")

    # ---------------------------------------------------------------- publish vs withdraw race
    status, _, current = client.json("GET", f"/api/admin/v1/works/{work_id}")
    version = current["data"]["version"]
    epoch_before = epoch(state)
    race_keys = [key(), key()]
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(publish, "publish", version, race_keys[0]),
                   pool.submit(publish, "withdraw", version, race_keys[1])]
        race = [f.result() for f in futures]
    successes = sum(1 for s, _ in race if s == 200)
    state_row = scalar(state, f"SELECT state FROM `{admin}`.admin_work_publication_state WHERE work_id={q(work_id)};")
    visible = scalar(state, f"SELECT visible FROM `{public}`.active_work_publication WHERE work_id={q(work_id)};")
    public_status = public_get(state, f"/api/v1/public/works/{work_id}")[0]
    command_effects = scalar(state, f"SELECT COUNT(*) FROM `{admin}`.admin_publication_command WHERE work_id={q(work_id)} AND idempotency_key IN ({q(race_keys[0])},{q(race_keys[1])});")
    pointer_rows = scalar(state, f"SELECT COUNT(*) FROM `{public}`.active_work_publication WHERE work_id={q(work_id)};")
    state_release = scalar(state, f"SELECT release_id FROM `{admin}`.admin_work_publication_state WHERE work_id={q(work_id)};")
    pointer_release = scalar(state, f"SELECT release_id FROM `{public}`.active_work_publication WHERE work_id={q(work_id)};")
    epoch_after_race = epoch(state)
    c.facts["publishVsWithdraw"] = {
        "statuses": [status for status, _ in race],
        "errorCodes": [error_code(body) for _, body in race],
        "errorReasons": [body.get("reason") for _, body in race],
        "commandEffects": command_effects,
        "finalState": state_row,
        "pointerRows": pointer_rows,
        "stateRelease": state_release,
        "pointerRelease": pointer_release,
        "visible": visible,
        "publicStatus": public_status,
        "epochBefore": epoch_before,
        "epochAfter": epoch_after_race,
    }
    race_responses_safe = all(
        status in (200, 409) or (status == 503 and error_code(body) == "CONTENT_SERVICE_UNAVAILABLE")
        for status, body in race
    )
    c.ok("publish-withdraw-race-consistent",
         race_responses_safe and command_effects == str(successes)
         and epoch(state) == epoch_before + successes
         and ((state_row == "PUBLISHED") == (visible == "1") == (public_status == 200)), "PUBLISH_WITHDRAW_RACE_INCONSISTENT")

    # ---------------------------------------------------------------- withdraw
    if state_row != "PUBLISHED":
        status, body = publish("publish", version, key())
        require(status == 200, f"REPUBLISH_HTTP_{status}")
    epoch_before = epoch(state)
    status, body = publish("withdraw", version, key())
    c.facts["withdrawAfterRace"] = {"status": status, "errorCode": error_code(body), "reason": body.get("reason"),
                                     "epochBefore": epoch_before, "epochAfter": epoch(state),
                                     "state": scalar(state, f"SELECT state FROM `{admin}`.admin_work_publication_state WHERE work_id={q(work_id)};"),
                                     "stateRelease": scalar(state, f"SELECT release_id FROM `{admin}`.admin_work_publication_state WHERE work_id={q(work_id)};"),
                                     "pointerRelease": scalar(state, f"SELECT release_id FROM `{public}`.active_work_publication WHERE work_id={q(work_id)};")}
    c.ok("withdraw-succeeds", status == 200 and epoch(state) == epoch_before + 1, f"WITHDRAW_HTTP_{status}_{error_code(body)}")
    c.ok("public-get-404-after-withdraw", public_get(state, f"/api/v1/public/works/{work_id}")[0] == 404, "WITHDRAWN_GET_VISIBLE")
    c.ok("public-head-404-after-withdraw", public_get(state, f"/api/v1/public/works/{work_id}", "HEAD")[0] == 404, "WITHDRAWN_HEAD_VISIBLE")
    c.ok("public-media-404-after-withdraw",
         public_get(state, f"/api/v1/public/media/{media_ids[0]}/preview/content")[0] == 404, "WITHDRAWN_MEDIA_VISIBLE")
    status, body = publish("withdraw", version, key())
    c.ok("withdraw-twice-409-not-published", status == 409 and error_code(body) == "NOT_PUBLISHED", "DOUBLE_WITHDRAW_ACCEPTED")
    c.facts["chainGone"] = chain(state, tools, work_id, "gone")
    c.ok("miniprogram-chain-gone", True)
    c.facts["contract"] = run_node(tools, CONTRACT_TEST, {"SOUTHLEAF_API_ORIGIN": f"http://127.0.0.1:{meta['publicPort']}"},
                                   "LIVE_NATIVE_CONTRACT_PASS", "V1_CONTRACT_AFTER_OVERLAY_FAILED")
    c.ok("v1-native-contract-unchanged-with-epoch", True)
    audits = scalar(state, f"SELECT COUNT(*) FROM `{admin}`.admin_publication_audit a JOIN `{admin}`.admin_publication_command p "
                           f"ON p.command_id=a.command_id WHERE a.work_id={q(work_id)};")
    commands_total = scalar(state, f"SELECT COUNT(*) FROM `{admin}`.admin_publication_command WHERE work_id={q(work_id)};")
    c.ok("every-command-audited-once", audits == commands_total and int(audits) >= 3, "AUDIT_MISMATCH")

    # ---------------------------------------------------------------- browser (UI save + publish)
    browser_title = title_v1 + " 浏览器"
    c.facts["browserChecks"] = run_browser(state, tools, account, pw, work_id, browser_title)
    c.ok("browser-ui-save-and-publish", c.facts["browserChecks"] >= 12, "BROWSER_CHECK_COUNT_LOW")
    status, _, body = public_get(state, f"/api/v1/public/works/{work_id}")
    c.ok("ui-publish-visible-in-public-api", status == 200 and body.get("data", {}).get("title") == browser_title, "UI_PUBLISH_NOT_PUBLIC")
    chain(state, tools, work_id, "visible", browser_title)
    c.ok("miniprogram-chain-after-ui-publish", True)

    # ---------------------------------------------------------------- natural expiry fail-closed
    client.login(account, pw["account"])
    status, _, current = client.json("GET", f"/api/admin/v1/works/{work_id}")
    version = current["data"]["version"]
    status, body = publish("withdraw", version, key())
    require(status == 200, f"PRE_EXPIRY_WITHDRAW_HTTP_{status}")
    put_gate(state, work_id, scope, digest, valid_seconds=20)
    status, body = publish("publish", version, key())
    require(status == 200, f"EXPIRY_PUBLISH_HTTP_{status}")
    c.ok("short-window-publish-visible", public_get(state, f"/api/v1/public/works/{work_id}")[0] == 200, "SHORT_WINDOW_NOT_VISIBLE")
    # The fixture window is based on the public JVM clock used by visibility evaluation.
    time.sleep(23.0)
    epoch_at_expiry = epoch(state)
    c.ok("natural-expiry-fail-closed-get", public_get(state, f"/api/v1/public/works/{work_id}")[0] == 404, "EXPIRED_WORK_VISIBLE")
    c.ok("natural-expiry-fail-closed-head", public_get(state, f"/api/v1/public/works/{work_id}", "HEAD")[0] == 404, "EXPIRED_HEAD_VISIBLE")
    c.ok("natural-expiry-needs-no-write", epoch(state) == epoch_at_expiry, "EXPIRY_CHANGED_EPOCH")
    chain(state, tools, work_id, "gone")
    c.ok("miniprogram-chain-gone-after-expiry", True)
    status, _, body = client.json("GET", f"/api/admin/v1/works/{work_id}/preview")
    c.ok("admin-preview-reports-expiry", status == 200 and body["data"]["blockingReason"] == "AUTHORIZATION_EXPIRED", "PREVIEW_EXPIRY_NOT_REPORTED")
    c.facts["finalEpoch"] = epoch(state)


def run_m3_catalog_browser(state: dict[str, object], tools: dict[str, Path], build: dict[str, Path],
                            account: str, pw: dict[str, str]) -> int:
    meta = state["meta"]

    def run_mode(mode: str) -> int:
        profile = Path(str(meta["root"])) / f"catalog-browser-{mode}"
        env = s1d.child_env({
            "SOUTHLEAF_S2B_FIXTURE_KIND": FIXTURE_KIND,
            "S2B_L5_BASE_URL": f"https://localhost:{meta['webPort']}",
            "S2B_L5_ACCOUNT": account,
            "S2B_L5_ACCOUNT_PASSWORD": pw["account"],
            "S2B_L5_FIXTURE_ROOT": str(meta["root"]),
            "S2B_L5_CATALOG_PROFILE_DIR": str(profile),
            "S2B_L5_CATALOG_MODE": mode,
        })
        process = subprocess.Popen([str(tools["node"]), str(M3_BROWSER_TEST)], cwd=str(WEB), env=env,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   creationflags=subprocess.CREATE_NEW_PROCESS_GROUP)
        state["browserProcess"] = process
        try:
            stdout, stderr = process.communicate(timeout=150)
        except subprocess.TimeoutExpired:
            subprocess.run(["taskkill.exe", "/PID", str(process.pid), "/T", "/F"], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, timeout=20, check=False)
            raise Failure("M3_CATALOG_BROWSER_TIMEOUT") from None
        finally:
            if process.poll() is not None:
                state["browserProcess"] = None
        marker = re.search(rb"M3_CATALOG_BROWSER_PASS:([0-9]+)", stdout)
        if process.returncode != 0 or marker is None:
            stage = re.search(rb"M3_CATALOG_BROWSER_FAILED:([A-Za-z0-9_-]+)", stderr)
            code = re.sub(r"[^A-Z0-9]+", "_", stage.group(1).decode().upper()) if stage else "UNKNOWN"
            # Diagnostics: the browser child may die hard (crash/OOM/timeout) without writing the
            # structured marker, which used to surface only as "_UNKNOWN". Keep a bounded,
            # secret-redacted tail of both streams so a failure is always diagnosable.
            secret = pw.get("account", "")
            def tail(blob: bytes, limit: int) -> str:
                text = blob.decode("utf-8", errors="replace")
                if secret:
                    text = text.replace(secret, "<REDACTED>")
                return " | ".join(line.strip() for line in text.strip().splitlines()[-40:])[-limit:]
            print("M3_BROWSER_RC:" + str(process.returncode), flush=True)
            print("M3_BROWSER_STDOUT_TAIL:" + tail(stdout, 1500), flush=True)
            print("M3_BROWSER_STDERR_TAIL:" + tail(stderr, 3000), flush=True)
            raise Failure(f"M3_CATALOG_BROWSER_FAILED_{mode}_{code}")
        return int(marker.group(1))

    full_permissions = ["CONTENT_READ", "CONTENT_EDIT", "CONTENT_PUBLISH", "STAFF_ACCESS_MANAGE"]
    set_permissions(state, tools, build, pw, account, ["CONTENT_READ", "STAFF_ACCESS_MANAGE"])
    try:
        readonly_checks = run_mode("readonly")
    finally:
        set_permissions(state, tools, build, pw, account, full_permissions)
        set_fixture_publish_permission(state, account, True)
    return readonly_checks + run_mode("full")


def m3_catalog_flow(state: dict[str, object], tools: dict[str, Path], build: dict[str, Path], pw: dict[str, str], account: str, c: Checks) -> None:
    """M3 catalog lifecycle on the same owned dual-schema fixture; synthetic values only."""
    admin, public = state["meta"]["schema"], state["meta"]["publicSchema"]
    client = AdminClient(int(state["meta"]["webPort"]), str(state["tls"]["caCert"]))
    client.login(account, pw["account"])
    path = "/api/admin/v1/content/catalog"
    status, _, initial = client.json("GET", path)
    initial_detail = "status=" + str(status) + ";type=" + type(initial).__name__ + ";body=" + repr(initial)[:400]
    require(status == 200 and isinstance(initial, dict)
            and isinstance(initial.get("data"), dict)
            and initial["data"].get("revision") == 0,
            "M3_INITIAL_DRAFT_READ_FAILED:" + initial_detail)
    base = initial["data"]["draft"]
    c.ok("m3-empty-draft-has-fixed-six-business-identities",
         [x["id"] for x in base["businesses"]] == ["child", "family", "wedding", "portrait", "maternity", "id-photo"]
         and base["packages"] == [], "M3_INITIAL_CATALOG_INVALID")

    def payload(media_id: str | None = None, featured_work_ids: list[str] | None = None) -> dict:
        businesses = []
        for position, identity in enumerate(("child", "family", "wedding", "portrait", "maternity", "id-photo")):
            businesses.append({"id": identity, "name": "Synthetic " + identity,
                "summary": "Synthetic catalog summary", "coverMediaId": media_id if identity == "child" else None,
                "position": position, "published": identity == "child"})
        alt = {media_id: "Synthetic child photography cover"} if media_id else {}
        return {
            "brand": {"name": "Synthetic M3 Studio", "englishName": "Synthetic Studio",
                "discoveryHeading": "Synthetic discovery", "shareTitle": "Synthetic share title",
                "tagline": "Synthetic tagline", "description": "Synthetic-only catalog acceptance",
                "logoMediaId": None},
            "contact": {"phone": "", "address": "Synthetic location", "workingHours": ""},
            "team": [{"id": "m3-team-synthetic", "name": "Synthetic photographer",
                "role": "Photographer", "bio": "Synthetic-only team profile",
                "specialties": ["Synthetic portrait"], "portraitMediaId": None,
                "position": 0, "published": True}],
            "channels": [{"id": "m3-channel-synthetic", "platform": "wechat",
                "label": "Synthetic contact", "account": "synthetic-only",
                "qrMediaId": None, "qrStatus": "CONFIRMED", "position": 0, "published": True}],
            "businesses": businesses,
            "packages": [{"id": "m3-package-synthetic", "businessId": "child",
                "name": "Synthetic package", "summary": "Synthetic package summary",
                "suitableFor": "Synthetic participants", "durationMinutes": 60,
                "displayPrice": "", "priceType": "INQUIRY",
                "serviceItems": ["Synthetic service item"], "tips": ["Synthetic tip"],
                "notices": ["Synthetic notice"], "published": True,
                "coverMediaId": None, "position": 0}],
            "featuredBusinessIds": ["child"], "featuredPackageIds": ["m3-package-synthetic"],
            "featuredWorkIds": featured_work_ids or [], "mediaAltTexts": alt,
        }

    status, headers, saved = client.json("PUT", path, payload("m3-unready-" + secrets.token_hex(5)),
                                        {"If-Match": '"c0"'})
    require(status == 200 and saved["data"]["revision"] == 1 and headers.get("etag") == '"c1"',
            "M3_DRAFT_SAVE_FAILED")
    c.ok("m3-draft-save-increments-version", True)
    status, _, history = client.json("GET", path + "/versions")
    c.ok("m3-history-lists-immutable-revisions", status == 200
         and [v["revision"] for v in history.get("data", [])] == [1], "M3_HISTORY_LIST_INVALID")
    status, _, historical = client.json("GET", path + "/versions/1")
    c.ok("m3-history-reads-saved-snapshot", status == 200
         and historical.get("data", {}).get("revision") == 1
         and historical["data"]["draft"]["businesses"][0]["name"] == "Synthetic child",
         "M3_HISTORY_SNAPSHOT_INVALID")
    status, _, stale = client.json("PUT", path, payload(), {"If-Match": '"c0"'})
    c.ok("m3-stale-draft-write-is-conflict", status == 409 and error_code(stale) == "VERSION_CONFLICT",
         "M3_STALE_WRITE_ACCEPTED")
    status, _, preview = client.json("GET", path + "/preview")
    c.ok("m3-preview-blocks-unready-media", status == 200 and preview["data"]["publishable"] is False
         and preview["data"]["blockingReason"] == "MEDIA_NOT_READY", "M3_UNREADY_MEDIA_NOT_BLOCKED")

    upload_key = key()
    status, uploaded = client.upload(png_bytes(320, 240, 17), "image/png", upload_key, "m3-synthetic.png")
    require(status == 200 and uploaded.get("data", {}).get("media", {}).get("status") == "READY",
            "M3_SYNTHETIC_MEDIA_UPLOAD_FAILED")
    media_id = uploaded["data"]["media"]["mediaId"]
    status, _, unpub = client.json("POST", "/api/admin/v1/works", {}, {"Idempotency-Key": key()})
    require(status == 200, "M3_UNPUBLISHED_WORK_CREATE_FAILED")
    unpublished_work_id = unpub["data"]["work"]["workId"]
    status, _, saved = client.json("PUT", path, payload(media_id, [unpublished_work_id]),
                                  {"If-Match": '"c1"'})
    require(status == 200 and saved["data"]["revision"] == 2, "M3_MEDIA_DRAFT_SAVE_FAILED")
    status, _, preview = client.json("GET", path + "/preview")
    c.ok("m3-preview-blocks-unpublished-featured-work", status == 200
         and preview["data"]["publishable"] is False
         and preview["data"]["blockingReason"] == "WORK_NOT_PUBLISHABLE",
         "M3_UNPUBLISHED_WORK_NOT_BLOCKED")

    work_upload_status, work_uploaded = client.upload(png_bytes(320, 240, 18), "image/png", key(), "m3-featured-work.png")
    require(work_upload_status == 200, "M3_FEATURED_WORK_MEDIA_UPLOAD_FAILED")
    work_media_id = work_uploaded["data"]["media"]["mediaId"]
    status, _, work_created = client.json("POST", "/api/admin/v1/works", {}, {"Idempotency-Key": key()})
    require(status == 200, "M3_FEATURED_WORK_CREATE_FAILED")
    work_id = work_created["data"]["work"]["workId"]
    work_version = work_created["data"]["work"]["version"]
    work_scope = "m3-featured-" + str(state["meta"]["runId"])
    work_draft = {"title": "Synthetic M3 featured work", "summary": "Synthetic-only public work",
        "subjectScopeRef": work_scope, "businessIds": ["child"],
        "media": [{"mediaId": work_media_id, "altText": "Synthetic featured work image"}],
        "coverMediaId": work_media_id}
    status, _, work_saved = client.json("PUT", f"/api/admin/v1/works/{work_id}", work_draft,
                                       {"If-Match": f'"v{work_version}"'})
    require(status == 200, "M3_FEATURED_WORK_DRAFT_FAILED")
    work_version = work_saved["data"]["version"]
    put_gate(state, work_id, work_scope, media_digest([work_media_id]), valid_seconds=7200)
    status, _, work_published = client.json("POST", f"/api/admin/v1/works/{work_id}/publish", {},
        {"If-Match": f'"v{work_version}"', "Idempotency-Key": key()})
    require(status == 200, "M3_FEATURED_WORK_PUBLISH_FAILED")
    c.ok("m3-featured-work-uses-s2-publication-authority", True)

    status, _, saved = client.json("PUT", path, payload(media_id, [work_id]), {"If-Match": '"c2"'})
    require(status == 200 and saved["data"]["revision"] == 3, "M3_FEATURED_WORK_DRAFT_SAVE_FAILED")
    status, _, history = client.json("GET", path + "/versions")
    c.ok("m3-history-keeps-prior-revisions", status == 200
         and [v["revision"] for v in history.get("data", [])] == [3, 2, 1], "M3_HISTORY_DID_NOT_APPEND")
    status, _, historical = client.json("GET", path + "/versions/3")
    require(status == 200 and historical.get("data", {}).get("revision") == 3,
            "M3_HISTORY_CURRENT_VERSION_LOAD_FAILED")
    status, _, restored = client.json("PUT", path, historical["data"]["draft"], {"If-Match": '"c3"'})
    c.ok("m3-history-restore-appends-new-revision", status == 200
         and restored.get("data", {}).get("revision") == 4, "M3_HISTORY_RESTORE_FAILED")
    status, _, preview = client.json("GET", path + "/preview")
    c.ok("m3-ready-media-and-alt-text-qualify-preview",
         status == 200 and preview.get("data", {}).get("publishable") is True,
         "M3_READY_MEDIA_PREVIEW_BLOCKED:" + str(status) + ":" + repr(preview)[:400])

    publish_key = key()
    publish_path = "/api/admin/v1/content/catalog/publish"
    trigger_name = "m3_fail_catalog_command_" + str(state["meta"]["runId"])
    mysql_root(state, f"CREATE TRIGGER `{admin}`.`{trigger_name}` BEFORE INSERT ON `{admin}`.admin_catalog_command "
                      "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='M3_TEST_INJECTED_FAILURE';")
    try:
        injected_status, _, injected_body = client.json("POST", publish_path, {},
            {"If-Match": '"c4"', "Idempotency-Key": publish_key})
    finally:
        mysql_root(state, f"DROP TRIGGER `{admin}`.`{trigger_name}`;")
    failed_pointer = scalar(state, f"SELECT CONCAT(visible,':',epoch,':',COALESCE(release_id,'')) "
                                    f"FROM `{public}`.active_catalog_publication WHERE singleton_id=1;")
    c.ok("m3-publish-mid-transaction-failure-rolls-back",
         injected_status == 503 and error_code(injected_body) == "CONTENT_SERVICE_UNAVAILABLE"
         and scalar(state, f"SELECT COUNT(*) FROM `{public}`.public_catalog_release;") == "0"
         and scalar(state, f"SELECT COUNT(*) FROM `{admin}`.admin_catalog_command;") == "0"
         and scalar(state, f"SELECT COUNT(*) FROM `{admin}`.admin_catalog_audit;") == "0"
         and failed_pointer == "0:0:", "M3_PUBLISH_TRANSACTION_ROLLBACK_FAILED")
    status, _, published = client.json("POST", publish_path, {},
        {"If-Match": '"c4"', "Idempotency-Key": publish_key})
    require(status == 200, f"M3_PUBLISH_HTTP_{status}_{error_code(published)}")
    c.ok("m3-publish-same-key-recovers-after-rollback", published["data"]["revision"] == 4
         and bool(published["data"]["releaseId"]))
    c.ok("m3_publish-creates-immutable-release", published["data"]["revision"] == 4
         and bool(published["data"]["releaseId"]))
    status, _, replay = client.json("POST", publish_path, {},
        {"If-Match": '"c4"', "Idempotency-Key": publish_key})
    c.ok("m3_publish-replay-same-command", status == 200 and replay["data"]["replayed"] is True,
         "M3_PUBLISH_REPLAY_FAILED")
    status, _, reused = client.json("POST", publish_path, {},
        {"If-Match": '"c5"', "Idempotency-Key": publish_key})
    c.ok("m3_publish-key-reuse-different-revision-conflicts", status == 409
         and error_code(reused) == "IDEMPOTENCY_KEY_REUSE", "M3_PUBLISH_KEY_REUSE_ACCEPTED")

    home_status, _, home = public_get(state, "/api/v1/public/home")
    home_data = home.get("data", {}) if isinstance(home, dict) else {}
    public_diag = None
    try:
        public_diag = state["publicDiagnostics"].get_nowait()
    except Exception:
        pass
    require(home_status == 200 and isinstance(home_data, dict),
            "M3_PUBLIC_HOME_INVALID:" + str(home_status) + ":diag=" + str(public_diag) + ":" + repr(home)[:600])
    brand_data = home_data.get("brand")
    businesses_data = home_data.get("businesses") or []
    require(isinstance(brand_data, dict) and isinstance(businesses_data, list)
            and (not businesses_data or isinstance(businesses_data[0], dict)),
            "M3_PUBLIC_HOME_SHAPE_INVALID:" + repr(home_data)[:600])
    cover = (businesses_data[0].get("coverMedia") or {}) if businesses_data else {}
    featured_works = home_data.get("featuredWorks") or []
    heroes = home_data.get("heroItems") or []
    c.ok("m3-public-v3-catalog-current", brand_data.get("name") == "Synthetic M3 Studio"
         and cover.get("id") == media_id and cover.get("altText") == "Synthetic child photography cover"
         and featured_works and featured_works[0].get("id") == work_id
         and heroes and heroes[0].get("targetId") == work_id,
         "M3_PUBLIC_V3_NOT_CURRENT")
    c.ok("m3-public-team-and-contact-channel-projected",
         brand_data.get("team") and brand_data["team"][0].get("id") == "m3-team-synthetic"
         and home_data.get("contact", {}).get("channels")
         and home_data["contact"]["channels"][0].get("id") == "m3-channel-synthetic",
         "M3_PUBLIC_TEAM_CHANNEL_MISSING")
    c.ok("m3-featured-package-projected-on-home",
         home_data.get("packages") and home_data["packages"][0].get("id") == "m3-package-synthetic",
         "M3_FEATURED_PACKAGE_NOT_PROJECTED")
    package_status, _, package_detail = public_get(state, "/api/v1/public/packages/m3-package-synthetic")
    package_data = package_detail.get("data", {}) if isinstance(package_detail, dict) else {}
    c.ok("m3-package-service-items-reachable-publicly",
         package_status == 200 and package_data.get("id") == "m3-package-synthetic"
         and package_data.get("serviceItems") == ["Synthetic service item"]
         and package_data.get("tips") == ["Synthetic tip"]
         and package_data.get("notices") == ["Synthetic notice"],
         "M3_PACKAGE_DETAIL_PROJECTION_INVALID")
    public_snapshot = scalar(state, f"SELECT CAST(snapshot_json AS CHAR) FROM `{public}`.public_catalog_release WHERE release_id={q(published['data']['releaseId'])};")
    c.ok("m3-public-release-excludes-unpublished-draft-content",
         "Synthetic family" not in public_snapshot and "mediaAltTexts" not in public_snapshot,
         "M3_UNPUBLISHED_DRAFT_LEAKED_TO_PUBLIC_SCHEMA")
    media_status, _, media_body = public_get(state,
        f"/api/v1/public/media/{media_id}/preview/content")
    c.ok("m3-public-media-uses-copied-ready-variant", media_status == 200
         and bool(media_body.get("_bytes")), "M3_PUBLIC_MEDIA_UNAVAILABLE")
    c.ok("m3-public-media-does-not-expose-object-key", "objectKey" not in str(home_data))
    c.ok("m3-v3-release-media-binding-row",
         scalar(state, f"SELECT COUNT(*) FROM `{public}`.public_catalog_release_binding WHERE release_id={q(published['data']['releaseId'])};") == "1",
         "M3_PUBLIC_MEDIA_BINDING_MISSING")

    withdraw_key = key()
    status, _, withdrawn = client.json("POST", "/api/admin/v1/content/catalog/withdraw", {},
        {"If-Match": '"c4"', "Idempotency-Key": withdraw_key})
    c.ok("m3-withdraw-succeeds", status == 200 and withdrawn.get("data", {}).get("status") == "WITHDRAWN",
         "M3_WITHDRAW_FAILED")
    c.facts["m3CatalogBrowserChecks"] = run_m3_catalog_browser(state, tools, build, account, pw)
    after_status, _, after = public_get(state, "/api/v1/public/home")
    after_data = after.get("data", {})
    c.ok("m3-withdraw-does-not-fallback-to-v1",
         after_status == 200 and after_data.get("brand", {}).get("name") == ""
         and after_data.get("businesses") == [], "M3_WITHDRAW_FELL_BACK_TO_V1")
    c.ok("m3-withdraw-revokes-catalog-media",
         public_get(state, f"/api/v1/public/media/{media_id}/preview/content")[0] == 404,
         "M3_WITHDRAW_MEDIA_STILL_PUBLIC")
    c.ok("m3-command-and-audit-recorded-once",
         scalar(state, f"SELECT COUNT(*) FROM `{admin}`.admin_catalog_command;") == "4"
         and scalar(state, f"SELECT COUNT(*) FROM `{admin}`.admin_catalog_audit;") == "4",
         "M3_CATALOG_AUDIT_MISMATCH")

    race_clients = [
        AdminClient(int(state["meta"]["webPort"]), str(state["tls"]["caCert"]))
        for _ in range(2)
    ]
    for race_client in race_clients:
        race_client.login(account, pw["account"])
    read_status, _, race_current = race_clients[0].json("GET", path)
    require(read_status == 200 and isinstance(race_current.get("data"), dict),
            "M3_CONCURRENT_SAVE_BASE_READ_FAILED")
    race_revision = race_current["data"]["revision"]
    race_drafts = [json.loads(json.dumps(race_current["data"]["draft"])) for _ in range(2)]
    race_drafts[0]["brand"]["tagline"] = "Synthetic concurrent catalog edit A"
    race_drafts[1]["brand"]["tagline"] = "Synthetic concurrent catalog edit B"
    race_barrier = threading.Barrier(2)

    def save_same_catalog_revision(index: int):
        race_barrier.wait(timeout=10)
        return race_clients[index].json(
            "PUT", path, race_drafts[index], {"If-Match": f'"c{race_revision}"'})

    with ThreadPoolExecutor(max_workers=2) as executor:
        race_futures = [executor.submit(save_same_catalog_revision, index) for index in range(2)]
        race_results = [future.result(timeout=20) for future in race_futures]
    race_statuses = sorted(result[0] for result in race_results)
    race_conflicts = [result[2] for result in race_results if result[0] == 409]
    final_status, _, race_final = race_clients[0].json("GET", path)
    final_draft = race_final.get("data", {}).get("draft", {}) if isinstance(race_final, dict) else {}
    c.ok("m3-concurrent-if-match-save-has-one-winner",
         race_statuses == [200, 409]
         and len(race_conflicts) == 1
         and error_code(race_conflicts[0]) == "VERSION_CONFLICT"
         and final_status == 200
         and race_final.get("data", {}).get("revision") == race_revision + 1
         and final_draft.get("brand", {}).get("tagline") in {
             "Synthetic concurrent catalog edit A", "Synthetic concurrent catalog edit B"},
         "M3_CONCURRENT_SAVE_CONFLICT_INVALID")
    c.facts["m3CatalogConcurrentSaveStatuses"] = race_statuses


# --------------------------------------------------------------------------- orchestration


def run_fixture(tools: dict[str, Path], build: dict[str, Path]) -> int:
    require(not EVIDENCE.exists(), "EVIDENCE_ALREADY_EXISTS")
    run_id = secrets.token_hex(4)
    base = tools["localAppData"] / "SouthLeaf" / BASE_NAME
    base.mkdir(parents=True, exist_ok=True)
    root = base / run_id
    require(not root.exists(), "FIXTURE_RUN_ID_COLLISION")
    root.mkdir()
    s1d.secure_fixture_directory(root)
    mysql_port = s1d.allocate_loopback_port(set())
    web_port = s1d.allocate_loopback_port({mysql_port})
    public_port = s1d.allocate_loopback_port({mysql_port, web_port})
    meta: dict[str, object] = {
        "kind": FIXTURE_KIND, "runId": run_id, "root": str(root.resolve()), "host": "127.0.0.1",
        "schema": "southleaf_admin_s2b" + run_id, "publicSchema": "southleaf_public_s2b" + run_id,
        "mysqlPort": mysql_port, "webPort": web_port, "publicPort": public_port,
        "datadir": str((root / "mysql-data").resolve()), "mysqlExecutable": str(s1d.MYSQLD.resolve()),
        "mysqlStartMode": "no-monitor", "mysqlPid": None, "serverUuid": None, "runtimePid": None,
        "publicPid": None, "candidateSourceSha256": source_sha256(), "status": "manifest-created",
    }
    save_manifest(meta)
    state: dict[str, object] = {"root": root, "meta": meta, "mysqlProcess": None, "runtimeProcess": None,
                                "publicProcess": None, "browserProcess": None, "tls": None}
    pw = {name: secrets.token_urlsafe(32) for name in
          ("adminMigrator", "publicMigrator", "cliDb", "runtime", "content", "publisher", "reader")}
    pw["account"] = "S2B!a9_" + secrets.token_urlsafe(32)
    c = Checks()
    status, failure, stage = "FAIL", None, "init"
    cleanup: dict[str, bool] = {}
    try:
        stage = "tls"; print("S2B_L5_STAGE:tls-fixture", flush=True)
        state["tls"] = s1d.create_tls_fixture(root, tools)
        stage = "mysql"; print("S2B_L5_STAGE:owned-mysql", flush=True)
        config = s1d.write_mysql_config(root, meta, state["tls"])
        config_text = config.read_text(encoding="utf-8")
        config.write_text(config_text.replace("[mysqld]\n", "[mysqld]\ndefault-time-zone=+00:00\n", 1),
                          encoding="utf-8", newline="\n")
        meta["mysqlConfigSha256"] = s1d.sha256_file(config)
        save_manifest(meta)
        s1d.checked_run([str(s1d.MYSQLD), f"--defaults-file={config}", "--initialize-insecure"],
                        cwd=s1d.MYSQL_HOME, timeout=120, category="MYSQL_INITIALIZE_FAILED")
        process = s1d.start_mysql(state, config)
        require(process.pid == meta["mysqlPid"] and verify_manifest(meta), "MYSQL_MANIFEST_PID_MISMATCH")
        engine = mysql_root(state, "SELECT @@default_storage_engine, @@version;")
        require(engine.startswith("InnoDB\t8.4"), "MYSQL_NOT_INNODB_8_4")
        c.ok("owned-mysql-8.4-innodb-tls-loopback", True)
        stage = "schemas"; print("S2B_L5_STAGE:dual-schema-migration", flush=True)
        create_schemas_and_migrators(state, pw)
        run_migrations(state, tools, build, pw)
        c.ok("admin-v5-and-public-v2-explicit-migrations", True)
        create_scoped_users(state, pw)
        stage = "account"; print("S2B_L5_STAGE:c3-account", flush=True)
        account = "s2b." + run_id
        account_cli(state, tools, build, pw, ["bootstrap", "--command-id", str(uuid.uuid4()), "--account", account,
                                             "--permission", "CONTENT_READ", "--permission", "CONTENT_EDIT",
                                             "--permission", "CONTENT_PUBLISH", "--permission", "STAFF_ACCESS_MANAGE"],
                    "CLI_BOOTSTRAP_FAILED")
        set_fixture_publish_permission(state, account, True)
        stage = "grants"; print("S2B_L5_STAGE:exact-grants", flush=True)
        grant_checks(state, c)
        stage = "runtimes"; print("S2B_L5_STAGE:runtimes", flush=True)
        candidate = s1d.static_candidate(root, build, meta)
        start_admin_runtime(state, tools, candidate, pw)
        c.ok("admin-runtime-verified-dual-pools-at-startup", True)
        start_public_runtime(state, tools, pw)
        c.ok("public-runtime-select-only-reader", True)
        stage = "flow"; print("S2B_L5_STAGE:s2b-flow", flush=True)
        s2b_flow(state, tools, build, pw, account, c)
        stage = "m3-catalog"; print("S2B_L5_STAGE:m3-catalog", flush=True)
        m3_catalog_flow(state, tools, build, pw, account, c)
        status = "PASS"
    except Failure as error:
        failure = error.category
    except Exception as error:  # noqa: BLE001 - classified, never reported as PASS
        detail = re.sub(r"[^A-Za-z0-9 _.:/-]", "_", str(error))[:160]
        print("S2B_L5_INTERNAL_DIAGNOSTIC:" + type(error).__name__ + ":" + detail, flush=True)
        failure = "INTERNAL_" + s1d.safe_exception_type_chain(error)
    finally:
        cleanup = remove_owned_fixture(state)
        pw.clear()
    if status == "PASS" and not cleanup.get("fixtureRemoved"):
        status, failure = "FAIL", "CLEANUP"
    evidence = {
        "kind": "S2B_ISOLATED_L5", "status": status, "runId": run_id,
        "checkedAtUtc": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "candidateSourceSha256": meta.get("candidateSourceSha256"), "candidateJarSha256": meta.get("candidateJarSha256"),
        "failureCategory": failure, "failedStage": None if status == "PASS" else stage,
        "checkCount": len(c.names), "checks": c.names, "facts": c.facts,
        "cleanup": {**cleanup, "publicGraceful": bool(meta.get("publicGracefulShutdown")),
                    "runtimeGraceful": bool(meta.get("runtimeGracefulShutdown")),
                    "mysqlGraceful": bool(meta.get("mysqlGracefulShutdown"))},
        "database": "owned MySQL 8.4 InnoDB, TLS VERIFY_IDENTITY, dual schema, five scoped users; admin V7/public V3",
        "m3CatalogChecks": [name for name in c.names if name.startswith("m3-")],
        "secretsRecorded": False, "realOrProductionDataUsed": False, "c2FixtureUsed": False,
        "limitations": ["Local disposable fixture only; not production acceptance",
                        "Authorization gates are TEST_SYNTHETIC rows written by the fixture (A15 authority process not in S2-B scope)"],
    }
    try:
        EVIDENCE.parent.mkdir(parents=True, exist_ok=True)
        with EVIDENCE.open("x", encoding="utf-8", newline="\n") as stream:
            json.dump(evidence, stream, ensure_ascii=False, sort_keys=True, indent=2)
            stream.write("\n")
    except OSError:
        print("S2B_L5_EVIDENCE_WRITE_FAILED")
        return 2
    if status == "PASS":
        print(f"S2B_L5_PASS:{run_id}:checks={len(c.names)}:cleanup=verified")
        return 0
    print(f"S2B_L5_FAIL:{failure}:stage={stage}:checks_passed={len(c.names)}:"
          f"cleanup={'verified' if cleanup.get('fixtureRemoved') else 'incomplete'}")
    return 2


def main() -> int:
    global EVIDENCE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-disposable-s2b-l5", action="store_true")
    parser.add_argument("--evidence-name", help="证据目录内新文件名（stage-two-s2b-isolated-l5*.json）")
    args = parser.parse_args()
    if not args.confirm_disposable_s2b_l5:
        print("S2B_L5_NOT_RUN:EXPLICIT_FIXTURE_CONFIRMATION_REQUIRED")
        return 3
    if args.evidence_name:
        name = args.evidence_name
        if Path(name).name != name or not re.fullmatch(r"stage-two-s2b-isolated-l5[A-Za-z0-9_.-]*\.json", name):
            print("S2B_L5_NOT_RUN:EVIDENCE_NAME_UNSAFE")
            return 3
        EVIDENCE = EVIDENCE_DIR / name
    try:
        tools = s1d.discover_tools()
        build = check_build_inputs()
        return run_fixture(tools, build)
    except Failure as error:
        print(f"S2B_L5_NOT_RUN:{error.category}")
        return 3


if __name__ == "__main__":
    raise SystemExit(main())