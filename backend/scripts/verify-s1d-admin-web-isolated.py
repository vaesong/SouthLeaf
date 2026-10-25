#!/usr/bin/env python3
"""S1-D 一次性本地 L5 runner；只操作本次创建并登记的 disposable fixture。"""
from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import os
import re
import secrets
import shutil
import signal
import socket
import ssl
import subprocess
import sys
import threading
import time
import urllib.request
import uuid
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from queue import Empty, Queue

REPO = Path(__file__).resolve().parents[2]
BACKEND = REPO / "backend"
WEB = REPO / "admin-web"
MYSQL_HOME = Path(r"D:\SoftWare\MySQL")
MYSQL_BIN = MYSQL_HOME / "bin"
MYSQLD = MYSQL_BIN / "mysqld.exe"
MYSQL = MYSQL_BIN / "mysql.exe"
RUNTIME_JAR = BACKEND / "admin-runtime" / "target" / "admin-runtime-0.1.0-SNAPSHOT.jar"
MIGRATION_MODULE = BACKEND / "admin-migration-cli"
CLI_MODULE = BACKEND / "admin-account-cli"
RUNTIME_MODULE = BACKEND / "admin-runtime"
BROWSER_TEST = WEB / "e2e" / "s1d-l5.mjs"
EVIDENCE = REPO / "docs" / "开发与演示" / "证据" / "stage-two-s1d-isolated-l5.json"
FIXTURE_KIND = "S1D_DISPOSABLE_L5"


class S1DFailure(Exception):
    def __init__(self, category: str):
        super().__init__(category)
        self.category = category


def require(condition: bool, category: str) -> None:
    if not condition:
        raise S1DFailure(category)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def child_env(extra: dict[str, str] | None = None) -> dict[str, str]:
    env = os.environ.copy()
    blocked_exact = {
        "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "MYSQL_PWD",
        "MYSQL_TEST_LOGIN_FILE", "NODE_OPTIONS", "SPRING_APPLICATION_JSON",
        "SOUTHLEAF_TEST_DB_URL", "SOUTHLEAF_TEST_DB_USERNAME", "SOUTHLEAF_TEST_DB_PASSWORD",
    }
    for key in list(env):
        upper = key.upper()
        if (
            upper in blocked_exact
            or upper.startswith(("SPRING_", "SOUTHLEAF_", "SERVER_", "LOGGING_", "LOGBACK_"))
        ):
            env.pop(key, None)
    if extra:
        env.update(extra)
    return env


def checked_run(
    args: list[str] | tuple[str, ...],
    *,
    env: dict[str, str] | None = None,
    cwd: Path | None = None,
    input_bytes: bytes | None = None,
    timeout: int = 60,
    category: str,
) -> subprocess.CompletedProcess[bytes]:
    try:
        result = subprocess.run(
            [str(arg) for arg in args],
            cwd=str(cwd) if cwd else None,
            env=env or child_env(),
            input=input_bytes,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        raise S1DFailure(category) from None
    if result.returncode != 0:
        raise S1DFailure(category)
    return result


def batch_run(command: str, *, cwd: Path, timeout: int, category: str) -> bytes:
    comspec = os.environ.get("COMSPEC", r"C:\Windows\System32\cmd.exe")
    result = checked_run(
        [comspec, "/d", "/c", command],
        cwd=cwd,
        timeout=timeout,
        category=category,
    )
    return result.stdout


def discover_tools() -> dict[str, Path]:
    require(os.name == "nt", "WINDOWS_REQUIRED")
    local_app_data = os.environ.get("LOCALAPPDATA")
    require(bool(local_app_data), "LOCALAPPDATA_REQUIRED")
    require(MYSQLD.is_file() and MYSQL.is_file(), "MYSQL_8_4_FIXTURE_TOOLS_REQUIRED")
    node = shutil.which("node.exe") or shutil.which("node")
    npm = shutil.which("npm.cmd") or shutil.which("npm")
    keytool = shutil.which("keytool.exe") or shutil.which("keytool")
    require(node is not None and npm is not None and keytool is not None, "NODE_JDK_TOOLS_REQUIRED")
    jdk_bin = Path(keytool).resolve().parent
    java = jdk_bin / "java.exe"
    javac = jdk_bin / "javac.exe"
    require(java.is_file() and javac.is_file(), "JDK_21_TOOLS_REQUIRED")
    edge_candidates = [
        Path(os.environ.get("PROGRAMFILES(X86)", r"C:\Program Files (x86)"))
        / "Microsoft" / "Edge" / "Application" / "msedge.exe",
        Path(os.environ.get("PROGRAMFILES", r"C:\Program Files"))
        / "Microsoft" / "Edge" / "Application" / "msedge.exe",
    ]
    edge = next((candidate for candidate in edge_candidates if candidate.is_file()), None)
    require(edge is not None, "MICROSOFT_EDGE_REQUIRED")
    require((BACKEND / "mvnw.cmd").is_file(), "MAVEN_WRAPPER_REQUIRED")
    require(BROWSER_TEST.is_file(), "BROWSER_TEST_REQUIRED")
    return {
        "node": Path(node).resolve(),
        "npm": Path(npm).resolve(),
        "java": java,
        "javac": javac,
        "keytool": Path(keytool).resolve(),
        "edge": edge,
        "localAppData": Path(local_app_data).resolve(),
    }


def check_build_inputs() -> dict[str, Path]:
    migration_deps = MIGRATION_MODULE / "target" / "dependency"
    cli_deps = CLI_MODULE / "target" / "dependency"
    migration_classes = MIGRATION_MODULE / "target" / "classes"
    cli_classes = CLI_MODULE / "target" / "classes"
    cli_test_classes = CLI_MODULE / "target" / "test-classes"
    security_classes = BACKEND / "admin-security-core" / "target" / "classes"
    credential_classes = BACKEND / "admin-credential-crypto" / "target" / "classes"
    db_policy_classes = BACKEND / "admin-db-policy" / "target" / "classes"
    dist = WEB / "dist"
    required_paths = [
        RUNTIME_JAR,
        migration_classes / "cn" / "zhinan" / "adminmigration" / "AdminDatabaseMigrationMain.class",
        migration_classes / "db" / "admin-migration",
        cli_classes / "cn" / "zhinan" / "adminaccountcli" / "AccountCliMain.class",
        cli_test_classes / "cn" / "zhinan" / "adminaccountcli" / "S1DAccountCliFixtureMain.class",
        security_classes / "cn" / "zhinan" / "adminsecurity" / "domain" / "AccountLifecycleException.class",
        credential_classes / "cn" / "zhinan" / "admincredentialcrypto" / "Argon2PasswordService.class",
        db_policy_classes / "cn" / "zhinan" / "admindbpolicy" / "AdminAccountCliGrantPolicy.class",
        dist / "index.html",
        migration_deps,
        cli_deps,
        WEB / "node_modules" / "playwright-core",
    ]
    require(all(path.exists() for path in required_paths), "BUILD_ARTIFACTS_REQUIRED")
    require(any(migration_deps.glob("*.jar")) and any(cli_deps.glob("*.jar")), "DEPENDENCY_CLASSPATH_REQUIRED")
    java_sources: list[Path] = []
    for module in (
        RUNTIME_MODULE,
        MIGRATION_MODULE,
        CLI_MODULE,
        BACKEND / "admin-security-core",
        BACKEND / "admin-credential-crypto",
        BACKEND / "admin-db-policy",
    ):
        java_sources += list((module / "src" / "main").rglob("*.java"))
    java_sources += list((RUNTIME_MODULE / "src" / "test").rglob("*.java"))
    java_sources += list((CLI_MODULE / "src" / "test").rglob("S1DAccountCliFixtureMain.java"))
    require(java_sources and max(path.stat().st_mtime for path in java_sources) <= RUNTIME_JAR.stat().st_mtime,
            "STALE_BACKEND_BUILD")
    frontend_inputs = list((WEB / "src").rglob("*"))
    frontend_inputs = [path for path in frontend_inputs if path.is_file()]
    frontend_inputs += [WEB / "index.html", WEB / "vite.config.ts", WEB / "package.json", WEB / "package-lock.json"]
    require(all(path.exists() for path in frontend_inputs), "FRONTEND_BUILD_INPUTS_MISSING")
    dist_index = dist / "index.html"
    require(max(path.stat().st_mtime for path in frontend_inputs) <= dist_index.stat().st_mtime,
            "STALE_FRONTEND_BUILD")
    return {
        "migrationClasses": migration_classes,
        "migrationDeps": migration_deps,
        "cliClasses": cli_classes,
        "cliTestClasses": cli_test_classes,
        "cliDeps": cli_deps,
        "securityClasses": security_classes,
        "credentialClasses": credential_classes,
        "dbPolicyClasses": db_policy_classes,
        "dist": dist,
    }


def source_sha256() -> str:
    candidates = [
        BACKEND / "pom.xml",
        RUNTIME_MODULE / "pom.xml",
        CLI_MODULE / "pom.xml",
        WEB / "package.json",
        WEB / "package-lock.json",
        WEB / "index.html",
        WEB / "vite.config.ts",
        BROWSER_TEST,
        CLI_MODULE / "src" / "test" / "java" / "cn" / "zhinan" / "adminaccountcli" / "S1DAccountCliFixtureMain.java",
    ]
    for module in (
        RUNTIME_MODULE,
        MIGRATION_MODULE,
        CLI_MODULE,
        BACKEND / "admin-security-core",
        BACKEND / "admin-credential-crypto",
        BACKEND / "admin-db-policy",
    ):
        candidates += list((module / "src" / "main").rglob("*"))
    candidates += list((RUNTIME_MODULE / "src" / "test").rglob("*"))
    candidates += list((CLI_MODULE / "src" / "test").rglob("*"))
    candidates += list((WEB / "src").rglob("*"))
    files = sorted({path for path in candidates if path.is_file()}, key=lambda item: item.as_posix())
    digest = hashlib.sha256()
    for path in files:
        digest.update(path.relative_to(REPO).as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def secure_fixture_directory(root: Path) -> None:
    who = checked_run(["whoami"], timeout=10, category="FIXTURE_OWNER_LOOKUP")
    account = who.stdout.decode("utf-8", errors="replace").strip()
    require(bool(account) and "\n" not in account, "FIXTURE_OWNER_LOOKUP")
    checked_run(
        ["icacls.exe", str(root), "/inheritance:r", "/grant:r", f"{account}:(OI)(CI)F"],
        timeout=15,
        category="FIXTURE_ACL_SETUP",
    )


def save_manifest(meta: dict[str, object]) -> None:
    root = Path(str(meta["root"]))
    temp = root / "manifest.tmp"
    temp.write_text(json.dumps(meta, sort_keys=True, indent=2), encoding="utf-8")
    temp.replace(root / "manifest.json")


def verify_manifest(meta: dict[str, object]) -> bool:
    try:
        root = Path(str(meta["root"]))
        disk = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
        base = root.parent.resolve()
        return (
            disk == meta
            and disk.get("kind") == FIXTURE_KIND
            and disk.get("runId") == root.name
            and root.resolve().parent == base
            and disk.get("datadir") == str((root / "mysql-data").resolve())
            and disk.get("host") == "127.0.0.1"
            and disk.get("schema") == "southleaf_admin_s1d" + str(disk.get("runId"))
            and disk.get("mysqlExecutable") == str(MYSQLD.resolve())
            and disk.get("mysqlStartMode") == "no-monitor"
        )
    except (OSError, ValueError, TypeError):
        return False


def allocate_loopback_port(excluded: set[int]) -> int:
    for _ in range(100):
        port = 49152 + secrets.randbelow(16384)
        if port in excluded:
            continue
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            sock.bind(("127.0.0.1", port))
            return port
        except OSError:
            pass
        finally:
            sock.close()
    raise S1DFailure("LOOPBACK_PORT_UNAVAILABLE")


def pem_exporter_source() -> str:
    return r"""import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.Base64;

public final class FixturePemExporter {
  private FixturePemExporter() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException();
    String secret = System.getenv("S1D_STORE_PASSWORD");
    if (secret == null || secret.isBlank()) throw new IllegalStateException();
    char[] password = secret.toCharArray();
    try {
      KeyStore store = KeyStore.getInstance("PKCS12");
      try (InputStream input = Files.newInputStream(Path.of(args[0]))) {
        store.load(input, password);
      }
      PrivateKey key = (PrivateKey) store.getKey("localhost", password);
      Certificate[] chain = store.getCertificateChain("localhost");
      if (key == null || chain == null || chain.length < 2) throw new IllegalStateException();
      Files.writeString(Path.of(args[1]), pem("PRIVATE KEY", key.getEncoded()), StandardCharsets.US_ASCII);
      StringBuilder certificates = new StringBuilder();
      for (Certificate certificate : chain) certificates.append(pem("CERTIFICATE", certificate.getEncoded()));
      Files.writeString(Path.of(args[2]), certificates.toString(), StandardCharsets.US_ASCII);
    } finally {
      Arrays.fill(password, '\0');
    }
  }

  private static String pem(String label, byte[] content) {
    return "-----BEGIN " + label + "-----\n"
        + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(content)
        + "\n-----END " + label + "-----\n";
  }
}
"""


def keytool_run(tools: dict[str, Path], args: list[str], env: dict[str, str], root: Path) -> None:
    checked_run([str(tools["keytool"]), *args], cwd=root, env=env, timeout=45, category="TLS_FIXTURE_SETUP")


def create_tls_fixture(root: Path, tools: dict[str, Path]) -> dict[str, str]:
    store_password = secrets.token_urlsafe(32) + "A9!"
    key_env = child_env({"S1D_STORE_PASSWORD": store_password, "S1D_TRUSTSTORE_PASSWORD": "changeit"})
    ca_store = root / "fixture-ca.p12"
    server_store = root / "fixture-server.p12"
    csr = root / "localhost.csr"
    ca_cert = root / "fixture-ca.pem"
    signed_cert = root / "localhost-cert.pem"
    server_key = root / "localhost-key.pem"
    server_chain = root / "localhost-chain.pem"
    runtime_store = root / "runtime-keystore.jks"
    trust_store = root / "mysql-truststore.jks"

    keytool_run(tools, [
        "-genkeypair", "-alias", "s1d-fixture-ca", "-keyalg", "RSA", "-keysize", "3072",
        "-dname", "CN=SouthLeaf S1-D Disposable Fixture CA", "-validity", "30",
        "-storetype", "PKCS12", "-keystore", str(ca_store),
        "-storepass:env", "S1D_STORE_PASSWORD", "-keypass:env", "S1D_STORE_PASSWORD",
        "-ext", "bc:critical=ca:true", "-ext", "ku:critical=keyCertSign,cRLSign",
    ], key_env, root)
    keytool_run(tools, [
        "-genkeypair", "-alias", "localhost", "-keyalg", "RSA", "-keysize", "2048",
        "-dname", "CN=localhost", "-validity", "30", "-storetype", "PKCS12",
        "-keystore", str(server_store), "-storepass:env", "S1D_STORE_PASSWORD",
        "-keypass:env", "S1D_STORE_PASSWORD", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
    ], key_env, root)
    keytool_run(tools, [
        "-certreq", "-alias", "localhost", "-keystore", str(server_store),
        "-storetype", "PKCS12", "-storepass:env", "S1D_STORE_PASSWORD",
        "-keypass:env", "S1D_STORE_PASSWORD", "-file", str(csr),
        "-ext", "SAN=dns:localhost,ip:127.0.0.1",
    ], key_env, root)
    keytool_run(tools, [
        "-gencert", "-alias", "s1d-fixture-ca", "-keystore", str(ca_store),
        "-storetype", "PKCS12", "-storepass:env", "S1D_STORE_PASSWORD",
        "-keypass:env", "S1D_STORE_PASSWORD", "-infile", str(csr),
        "-outfile", str(signed_cert), "-rfc", "-validity", "30",
        "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-ext", "KU=digitalSignature,keyEncipherment",
        "-ext", "EKU=serverAuth",
    ], key_env, root)
    keytool_run(tools, [
        "-exportcert", "-alias", "s1d-fixture-ca", "-keystore", str(ca_store),
        "-storetype", "PKCS12", "-storepass:env", "S1D_STORE_PASSWORD",
        "-rfc", "-file", str(ca_cert),
    ], key_env, root)
    keytool_run(tools, [
        "-importcert", "-alias", "s1d-fixture-ca", "-keystore", str(server_store),
        "-storetype", "PKCS12", "-storepass:env", "S1D_STORE_PASSWORD",
        "-file", str(ca_cert), "-noprompt",
    ], key_env, root)
    keytool_run(tools, [
        "-importcert", "-alias", "localhost", "-keystore", str(server_store),
        "-storetype", "PKCS12", "-storepass:env", "S1D_STORE_PASSWORD",
        "-file", str(signed_cert), "-noprompt",
    ], key_env, root)
    keytool_run(tools, [
        "-importkeystore", "-srckeystore", str(server_store), "-srcstoretype", "PKCS12",
        "-srcstorepass:env", "S1D_STORE_PASSWORD", "-srckeypass:env", "S1D_STORE_PASSWORD",
        "-destkeystore", str(runtime_store), "-deststoretype", "JKS",
        "-deststorepass:env", "S1D_STORE_PASSWORD", "-destkeypass:env", "S1D_STORE_PASSWORD",
        "-srcalias", "localhost", "-destalias", "localhost", "-noprompt",
    ], key_env, root)
    keytool_run(tools, [
        "-importcert", "-alias", "s1d-fixture-ca", "-file", str(ca_cert),
        "-keystore", str(trust_store), "-storetype", "JKS",
        "-storepass:env", "S1D_TRUSTSTORE_PASSWORD", "-noprompt",
    ], key_env, root)

    exporter = root / "FixturePemExporter.java"
    exporter.write_text(pem_exporter_source(), encoding="utf-8", newline="\n")
    checked_run([str(tools["javac"]), "-d", str(root), str(exporter)], cwd=root, env=child_env(),
                timeout=30, category="TLS_FIXTURE_SETUP")
    checked_run([
        str(tools["java"]), "-cp", str(root), "FixturePemExporter",
        str(server_store), str(server_key), str(server_chain),
    ], cwd=root, env=key_env, timeout=30, category="TLS_FIXTURE_SETUP")
    for temporary in (ca_store, server_store, csr, signed_cert, exporter, root / "FixturePemExporter.class"):
        temporary.unlink(missing_ok=True)
    require(all(path.is_file() for path in (ca_cert, server_key, server_chain, runtime_store, trust_store)),
            "TLS_FIXTURE_SETUP")
    return {
        "caCert": str(ca_cert),
        "serverKey": str(server_key),
        "serverChain": str(server_chain),
        "runtimeKeyStore": str(runtime_store),
        "trustStore": str(trust_store),
        "keyStorePassword": store_password,
        "javaToolOptions": (
            f'-Djavax.net.ssl.trustStore="{trust_store}" '
            "-Djavax.net.ssl.trustStoreType=JKS -Djava.net.preferIPv4Stack=true"
        ),
    }


def write_mysql_config(root: Path, meta: dict[str, object], tls: dict[str, str]) -> Path:
    data_dir = root / "mysql-data"
    temp_dir = root / "mysql-tmp"
    data_dir.mkdir()
    temp_dir.mkdir()
    config = root / "mysqld-s1d.ini"
    values = [
        "[mysqld]",
        f'basedir="{MYSQL_HOME.as_posix()}"',
        f'datadir="{data_dir.as_posix()}"',
        f'port={meta["mysqlPort"]}',
        "bind-address=127.0.0.1",
        "mysqlx=OFF",
        "require_secure_transport=ON",
        "tls_version=TLSv1.2,TLSv1.3",
        f'ssl_ca="{tls["caCert"].replace(chr(92), "/")}"',
        f'ssl_cert="{tls["serverChain"].replace(chr(92), "/")}"',
        f'ssl_key="{tls["serverKey"].replace(chr(92), "/")}"',
        f'secure_file_priv="{temp_dir.as_posix()}"',
        "local_infile=OFF",
        "general_log=OFF",
        "slow_query_log=OFF",
        "character_set_server=utf8mb4",
        "collation_server=utf8mb4_0900_ai_ci",
        "max_connections=32",
        "innodb_buffer_pool_size=96M",
        f'log_error="{(root / "mysqld-error.log").as_posix()}"',
        f'pid_file="{(root / "mysqld.pid").as_posix()}"',
        "",
    ]
    config.write_text("\n".join(values), encoding="utf-8", newline="\n")
    meta["datadir"] = str(data_dir.resolve())
    meta["mysqlConfigSha256"] = sha256_file(config)
    save_manifest(meta)
    return config


def netstat_listeners(pid: int) -> list[str]:
    try:
        result = subprocess.run(
            ["netstat.exe", "-ano", "-p", "TCP"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=10,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return []
    rows: list[str] = []
    for line in result.stdout.decode("utf-8", errors="replace").splitlines():
        parts = line.split()
        if len(parts) >= 5 and parts[0].upper() == "TCP" and parts[-2].upper() == "LISTENING":
            if parts[-1] == str(pid):
                rows.append(parts[1])
    return sorted(set(rows))


def port_owner(port: int) -> list[tuple[str, str]]:
    try:
        result = subprocess.run(
            ["netstat.exe", "-ano", "-p", "TCP"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=10,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return []
    owners: list[tuple[str, str]] = []
    for line in result.stdout.decode("utf-8", errors="replace").splitlines():
        parts = line.split()
        if len(parts) >= 5 and parts[0].upper() == "TCP" and parts[-2].upper() == "LISTENING":
            if parts[1].rsplit(":", 1)[-1] == str(port):
                owners.append((parts[1], parts[-1]))
    return owners


def is_loopback_listener(address: str, port: int) -> bool:
    host, separator, port_text = address.rpartition(":")
    return separator == ":" and port_text == str(port) and host.strip("[]") in {"127.0.0.1", "::1"}


def mysql_command(root: Path, port: int, ca_cert: str) -> list[str]:
    return [
        str(MYSQL), "--no-beep", "--protocol=TCP", "--host=127.0.0.1", "--port", str(port),
        "--user=root", "--connect-timeout=4", "--ssl-mode=VERIFY_IDENTITY", "--ssl-ca", ca_cert,
        "--batch", "--raw", "--skip-column-names", "--default-character-set=utf8mb4",
    ]


def mysql_query_raw(state: dict[str, object], sql: str, timeout: int = 10) -> subprocess.CompletedProcess[bytes] | None:
    meta = state["meta"]
    tls = state["tls"]
    try:
        return subprocess.run(
            mysql_command(Path(str(meta["root"])), int(meta["mysqlPort"]), str(tls["caCert"])),
            input=sql.encode("utf-8"),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=child_env(),
            timeout=timeout,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None


def mysql_exec(state: dict[str, object], sql: str, timeout: int = 12) -> str:
    result = mysql_query_raw(state, sql, timeout)
    require(result is not None and result.returncode == 0, "MYSQL_CONTROL_QUERY")
    return result.stdout.decode("utf-8", errors="replace").strip()


def start_mysql(state: dict[str, object], config: Path) -> subprocess.Popen[bytes]:
    meta = state["meta"]
    require(sha256_file(config) == meta.get("mysqlConfigSha256"), "MYSQL_CONFIG_CHANGED")
    require(meta.get("mysqlStartMode") == "no-monitor", "MYSQL_START_MODE_UNVERIFIED")
    process = subprocess.Popen(
        [str(MYSQLD), f"--defaults-file={config}", "--no-monitor"],
        cwd=str(MYSQL_HOME),
        env=child_env(),
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    state["mysqlProcess"] = process
    meta["mysqlPid"] = process.pid
    meta["status"] = "mysql-starting"
    save_manifest(meta)
    expected_port = int(meta["mysqlPort"])
    deadline = time.monotonic() + 75
    while time.monotonic() < deadline:
        require(process.poll() is None, "MYSQL_EXITED_BEFORE_READY")
        addresses = netstat_listeners(process.pid)
        if any(address.rsplit(":", 1)[-1] == str(expected_port) for address in addresses):
            raw = mysql_query_raw(state, "SELECT @@server_uuid,@@datadir,@@port,CURRENT_USER();", 5)
            if raw is not None and raw.returncode == 0:
                columns = raw.stdout.decode("utf-8", errors="replace").strip().split("\t")
                require(len(columns) == 4, "MYSQL_IDENTITY_QUERY")
                server_uuid, actual_datadir, actual_port, user = columns
                require(server_uuid and user.lower() in {"root@localhost", "root@127.0.0.1"}, "MYSQL_ROOT_IDENTITY")
                require(int(actual_port) == expected_port, "MYSQL_PORT_MISMATCH")
                require(Path(actual_datadir).resolve() == Path(str(meta["datadir"])).resolve(),
                        "MYSQL_DATADIR_MISMATCH")
                owners = port_owner(expected_port)
                require(
                    len(addresses) == 1
                    and len(owners) == 1
                    and owners[0][1] == str(process.pid)
                    and addresses[0] == owners[0][0]
                    and is_loopback_listener(addresses[0], expected_port),
                    "MYSQL_LISTENER_SCOPE",
                )
                meta["serverUuid"] = server_uuid
                meta["status"] = "mysql-ready"
                save_manifest(meta)
                return process
        time.sleep(0.25)
    raise S1DFailure("MYSQL_READY_TIMEOUT")


def sql_quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def jdbc_url(port: int, schema: str) -> str:
    return (
        f"jdbc:mysql://localhost:{port}/{schema}?sslMode=VERIFY_IDENTITY"
        "&allowPublicKeyRetrieval=false&connectionTimeZone=UTC"
        "&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8"
        "&connectTimeout=5000&socketTimeout=5000"
    )


def create_database_and_migrator(state: dict[str, object], passwords: dict[str, str]) -> None:
    schema = str(state["meta"]["schema"])
    quoted_schema = f"`{schema}`"
    statements = [
        f"CREATE DATABASE {quoted_schema} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;",
        f"CREATE USER 's1d_migrator'@'localhost' IDENTIFIED BY {sql_quote(passwords['migrator'])};",
        f"GRANT ALL PRIVILEGES ON {quoted_schema}.* TO 's1d_migrator'@'localhost';",
    ]
    mysql_exec(state, "\n".join(statements))
    state["meta"]["status"] = "disposable-schema-and-migrator-created"
    save_manifest(state["meta"])


def create_scoped_database_users(state: dict[str, object], passwords: dict[str, str]) -> None:
    schema = str(state["meta"]["schema"])
    quoted_schema = f"`{schema}`"
    statements = [
        f"CREATE USER 's1d_cli'@'localhost' IDENTIFIED BY {sql_quote(passwords['cliDb'])};",
        f"GRANT SELECT, UPDATE ON {quoted_schema}.admin_bootstrap_guard TO 's1d_cli'@'localhost';",
        f"GRANT SELECT, INSERT, UPDATE ON {quoted_schema}.admin_account TO 's1d_cli'@'localhost';",
        f"GRANT SELECT, INSERT, DELETE ON {quoted_schema}.admin_account_permission TO 's1d_cli'@'localhost';",
        f"GRANT SELECT, INSERT ON {quoted_schema}.admin_account_command TO 's1d_cli'@'localhost';",
        f"CREATE USER 's1d_runtime'@'localhost' IDENTIFIED BY {sql_quote(passwords['runtime'])};",
        f"GRANT SELECT ON {quoted_schema}.admin_account TO 's1d_runtime'@'localhost';",
        f"GRANT SELECT ON {quoted_schema}.admin_account_permission TO 's1d_runtime'@'localhost';",
        f"GRANT SELECT, INSERT, UPDATE ON {quoted_schema}.admin_session TO 's1d_runtime'@'localhost';",
        f"GRANT INSERT ON {quoted_schema}.admin_security_audit TO 's1d_runtime'@'localhost';",
        f"GRANT INSERT ON {quoted_schema}.admin_auth_event TO 's1d_runtime'@'localhost';",
    ]
    mysql_exec(state, "\n".join(statements))
    state["meta"]["status"] = "minimal-cli-and-runtime-grants-created"
    save_manifest(state["meta"])


def java_options_env(tls: dict[str, str], extra: dict[str, str]) -> dict[str, str]:
    return child_env({**extra, "JAVA_TOOL_OPTIONS": tls["javaToolOptions"]})


def java_classpath(classes: Path, deps: Path, *extra: Path) -> str:
    paths = [str(path) for path in extra] + [str(classes), str(deps / "*")]
    return ";".join(paths)


def run_java_class(
    tools: dict[str, Path],
    classpath: str,
    main_class: str,
    args: list[str],
    env: dict[str, str],
    timeout: int,
    category: str,
) -> str:
    try:
        result = subprocess.run(
            [str(tools["java"]), "-cp", classpath, main_class, *args],
            env=env,
            cwd=BACKEND,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
        )
    except subprocess.TimeoutExpired:
        raise S1DFailure(f"{category}_TIMEOUT") from None
    except OSError:
        raise S1DFailure(f"{category}_PROCESS_START") from None
    if result.returncode != 0:
        output = result.stdout + result.stderr
        safe_reason = re.search(rb"ACCOUNT_CLI_REJECTED reason=([A-Z_]+)", output)
        if safe_reason:
            reason = safe_reason.group(1).decode("ascii")
            raise S1DFailure(f"{category}_{reason}")
        if b"S1D_CLI_FIXTURE_REFUSED" in output:
            raise S1DFailure(f"{category}_FIXTURE_GUARD")
        if b"Could not find or load main class" in output or b"ClassNotFoundException" in output:
            raise S1DFailure(f"{category}_JAVA_CLASS_NOT_FOUND")
        if b"NoClassDefFoundError" in output:
            raise S1DFailure(f"{category}_JAVA_DEPENDENCY_MISSING")
        if b"Exception in thread" in output or b"Caused by:" in output:
            raise S1DFailure(f"{category}_JAVA_RUNTIME_EXCEPTION")
        if not output.strip():
            raise S1DFailure(f"{category}_EMPTY_EXIT_{abs(result.returncode)}")
        raise S1DFailure(f"{category}_UNCLASSIFIED_EXIT_{abs(result.returncode)}")
    return result.stdout.decode("utf-8", errors="replace")


def run_migrations(state: dict[str, object], tools: dict[str, Path], build: dict[str, Path], passwords: dict[str, str]) -> None:
    meta = state["meta"]
    env = java_options_env(
        state["tls"],
        {
            "SOUTHLEAF_ADMIN_MIGRATION_SCHEMA": str(meta["schema"]),
            "SOUTHLEAF_ADMIN_MIGRATION_URL": jdbc_url(int(meta["mysqlPort"]), str(meta["schema"])),
            "SOUTHLEAF_ADMIN_MIGRATION_USERNAME": "s1d_migrator",
            "SOUTHLEAF_ADMIN_MIGRATION_PASSWORD": passwords["migrator"],
        },
    )
    classpath = java_classpath(build["migrationClasses"], build["migrationDeps"])
    migrated = run_java_class(
        tools, classpath, "cn.zhinan.adminmigration.AdminDatabaseMigrationMain", ["--migrate"],
        env, 90, "ADMIN_MIGRATION_FAILED",
    )
    require("ADMIN_SCHEMA_MIGRATION_PASS" in migrated, "ADMIN_MIGRATION_MARKER_MISSING")
    validated = run_java_class(
        tools, classpath, "cn.zhinan.adminmigration.AdminDatabaseMigrationMain", ["--validate"],
        env, 60, "ADMIN_MIGRATION_VALIDATION_FAILED",
    )
    require("ADMIN_SCHEMA_VALIDATION_PASS" in validated, "ADMIN_VALIDATION_MARKER_MISSING")
    meta["status"] = "migration-and-validation-passed"
    save_manifest(meta)


def bootstrap_fixture_account(
    state: dict[str, object], tools: dict[str, Path], build: dict[str, Path], passwords: dict[str, str]
) -> str:
    meta = state["meta"]
    run_id = str(meta["runId"])
    account_id = "s1d." + run_id
    command_id = str(uuid.uuid4())
    env = java_options_env(
        state["tls"],
        {
            "SOUTHLEAF_S1D_FIXTURE_KIND": FIXTURE_KIND,
            "SOUTHLEAF_S1D_CLI_MODE": "demo",
            "SOUTHLEAF_S1D_CLI_DB_URL": jdbc_url(int(meta["mysqlPort"]), str(meta["schema"])),
            "SOUTHLEAF_S1D_CLI_DB_USERNAME": "s1d_cli",
            "SOUTHLEAF_S1D_CLI_DB_PASSWORD": passwords["cliDb"],
            "SOUTHLEAF_S1D_CLI_DB_SCHEMA": str(meta["schema"]),
            "SOUTHLEAF_S1D_CLI_DB_EXPECTED_USER": "s1d_cli@localhost",
            "SOUTHLEAF_S1D_ACCOUNT_PASSWORD": passwords["account"],
        },
    )
    classpath = java_classpath(
        build["cliClasses"],
        build["cliDeps"],
        build["cliTestClasses"],
        build["securityClasses"],
        build["credentialClasses"],
        build["dbPolicyClasses"],
    )
    output = run_java_class(
        tools,
        classpath,
        "cn.zhinan.adminaccountcli.S1DAccountCliFixtureMain",
        [
            "bootstrap", "--command-id", command_id, "--account", account_id,
            "--permission", "STAFF_ACCESS_MANAGE", "--permission", "AUDIT_READ",
        ],
        env,
        60,
        "C3_CLI_BOOTSTRAP_FAILED",
    )
    require("ACCOUNT_CLI_SUCCESS" in output, "C3_CLI_SUCCESS_MARKER_MISSING")
    schema = str(meta["schema"])
    account_count = mysql_exec(
        state,
        f"SELECT COUNT(*) FROM `{schema}`.admin_account WHERE account_id={sql_quote(account_id)} AND enabled=1;",
    )
    permission_count = mysql_exec(
        state,
        f"SELECT COUNT(*) FROM `{schema}`.admin_account_permission "
        f"WHERE account_id={sql_quote(account_id)} AND permission_code='STAFF_ACCESS_MANAGE';",
    )
    require(account_count == "1", "C3_ACCOUNT_ROW_MISSING")
    require(permission_count == "1", "C3_PERMISSION_ROW_MISSING")
    meta["status"] = "synthetic-staff-created-through-c3-cli"
    save_manifest(meta)
    return account_id


def static_candidate(root: Path, build: dict[str, Path], meta: dict[str, object]) -> Path:
    candidate = root / "candidate-admin-runtime.jar"
    shutil.copy2(RUNTIME_JAR, candidate)
    prefix = "BOOT-INF/classes/static/"
    files = [path for path in build["dist"].rglob("*") if path.is_file()]
    require(files and (build["dist"] / "index.html").is_file(), "FRONTEND_DIST_EMPTY")
    with zipfile.ZipFile(candidate, "a", compression=zipfile.ZIP_DEFLATED) as archive:
        existing = set(archive.namelist())
        require(not any(name.startswith(prefix) for name in existing), "STATIC_ASSET_COLLISION")
        for path in files:
            relative = path.relative_to(build["dist"]).as_posix()
            require(not relative.startswith("../") and "\\" not in relative, "STATIC_ASSET_PATH_INVALID")
            archive.write(path, prefix + relative)
    with zipfile.ZipFile(candidate, "r") as archive:
        names = set(archive.namelist())
        require(prefix + "index.html" in names, "STATIC_INDEX_NOT_PACKAGED")
        require(any(name.startswith(prefix + "assets/") for name in names), "STATIC_ASSETS_NOT_PACKAGED")
    meta["candidateJarSha256"] = sha256_file(candidate)
    meta["candidateSourceSha256"] = source_sha256()
    meta["status"] = "temporary-candidate-built-with-admin-web"
    save_manifest(meta)
    return candidate


def drain_runtime_stdout(
    process: subprocess.Popen[bytes], ready: Queue[str], diagnostics: Queue[str]
) -> None:
    if process.stdout is None:
        return
    for raw in iter(process.stdout.readline, b""):
        line = raw.decode("utf-8", errors="replace").strip()
        if line.startswith(("ADMIN_RUNTIME_READY:", "ADMIN_RUNTIME_REFUSED:")):
            ready.put(line)
        exception_type = re.search(
            r"(?<![A-Za-z0-9_$])(?:[A-Za-z_$][A-Za-z0-9_$]*\.)*"
            r"([A-Za-z_$][A-Za-z0-9_$]*(?:Exception|Error))(?=[:\s]|$)",
            line,
        )
        if exception_type:
            diagnostics.put(exception_type.group(1))


def take_runtime_diagnostic(diagnostics: Queue[str]) -> str | None:
    latest = None
    while True:
        try:
            latest = diagnostics.get_nowait()
        except Empty:
            break
    return re.sub(r"[^A-Z]", "_", latest.upper()) if latest else None


def safe_exception_type_chain(error: BaseException, limit: int = 4) -> str:
    types: list[str] = []
    current: BaseException | None = error
    seen: set[int] = set()
    verify_category: str | None = None
    verify_codes = {
        2: "ISSUER_MISSING",
        9: "NOT_YET_VALID",
        10: "EXPIRED",
        18: "SELF_SIGNED",
        19: "SELF_SIGNED_CHAIN",
        20: "ISSUER_MISSING",
        21: "CHAIN_INCOMPLETE",
        62: "HOSTNAME_MISMATCH",
        64: "IP_MISMATCH",
    }
    for _ in range(limit):
        if current is None or id(current) in seen:
            break
        seen.add(id(current))
        type_name = re.sub(r"[^A-Z]+", "_", type(current).__name__.upper()).strip("_")
        if re.fullmatch(r"[A-Z_]{1,64}", type_name) and (not types or types[-1] != type_name):
            types.append(type_name)
        if isinstance(current, ssl.SSLCertVerificationError):
            verify_code = getattr(current, "verify_code", None)
            verify_category = verify_codes.get(verify_code)
            if verify_category is None:
                message = str(getattr(current, "verify_message", "")).lower()
                message_categories = (
                    ("self-signed", "SELF_SIGNED"),
                    ("hostname mismatch", "HOSTNAME_MISMATCH"),
                    ("ip address mismatch", "IP_MISMATCH"),
                    ("not yet valid", "NOT_YET_VALID"),
                    ("expired", "EXPIRED"),
                    ("unable to get local issuer", "ISSUER_MISSING"),
                    ("unable to verify the first certificate", "CHAIN_INCOMPLETE"),
                )
                verify_category = next(
                    (category for phrase, category in message_categories if phrase in message),
                    "OTHER",
                )
            code_suffix = f"_CODE_{verify_code}" if isinstance(verify_code, int) else "_CODE_UNKNOWN"
            verify_category += code_suffix
        nested = current.__cause__ or current.__context__
        if nested is None:
            reason = getattr(current, "reason", None)
            if isinstance(reason, BaseException):
                nested = reason
        current = nested
    result = "_CAUSE_".join(types) if types else "UNKNOWN"
    if verify_category:
        category, _, code = verify_category.partition("_CODE_")
        result += f"_VERIFY_{category}_CODE_{code}"
    return result


def runtime_http_probe(port: int, ca_cert: str) -> None:
    try:
        context = ssl.create_default_context(cafile=ca_cert)
    except Exception as error:
        raise S1DFailure(f"HTTPS_TRUST_CONTEXT_{safe_exception_type_chain(error)}") from None
    url = f"https://localhost:{port}/"
    try:
        with urllib.request.urlopen(url, context=context, timeout=10) as response:
            body = response.read(262144).decode("utf-8", errors="replace")
            require(response.status == 200 and "id=\"app\"" in body, "HTTPS_UI_ROOT_FAILED")
    except S1DFailure:
        raise
    except Exception as error:
        raise S1DFailure(f"HTTPS_ROOT_PROBE_{safe_exception_type_chain(error)}") from None
    request = urllib.request.Request(
        f"https://localhost:{port}/api/admin/v1/auth/csrf",
        headers={"Accept": "application/json"},
        method="GET",
    )
    try:
        with urllib.request.urlopen(request, context=context, timeout=10) as response:
            payload = json.loads(response.read(16384).decode("utf-8"))
            require(
                response.status == 200
                and payload.get("code") == "OK"
                and payload.get("data", {}).get("headerName") == "X-CSRF-TOKEN"
                and isinstance(payload.get("data", {}).get("csrfToken"), str),
                "HTTPS_CSRF_ENDPOINT_FAILED",
            )
    except S1DFailure:
        raise
    except Exception as error:
        raise S1DFailure(f"HTTPS_CSRF_PROBE_{safe_exception_type_chain(error)}") from None


def start_runtime(
    state: dict[str, object], tools: dict[str, Path], candidate: Path, passwords: dict[str, str]
) -> subprocess.Popen[bytes]:
    meta = state["meta"]
    require(verify_manifest(meta), "MANIFEST_MISMATCH")
    require(sha256_file(candidate) == meta.get("candidateJarSha256"), "CANDIDATE_JAR_CHANGED")
    env = java_options_env(
        state["tls"],
        {
            "SOUTHLEAF_ADMIN_MODE": "demo",
            "SOUTHLEAF_ADMIN_DB_URL": jdbc_url(int(meta["mysqlPort"]), str(meta["schema"])),
            "SOUTHLEAF_ADMIN_DB_USERNAME": "s1d_runtime",
            "SOUTHLEAF_ADMIN_DB_PASSWORD": passwords["runtime"],
            "SOUTHLEAF_ADMIN_DB_SCHEMA": str(meta["schema"]),
            "SOUTHLEAF_ADMIN_DB_EXPECTED_USER": "s1d_runtime@localhost",
            "SOUTHLEAF_ADMIN_WEB_MODE": "demo",
            "SOUTHLEAF_ADMIN_WEB_ORIGIN": f"https://localhost:{meta['webPort']}",
            "SOUTHLEAF_ADMIN_WEB_PORT": str(meta["webPort"]),
            "SOUTHLEAF_ADMIN_WEB_KEYSTORE_PATH": str(state["root"] / "runtime-keystore.jks"),
            "SOUTHLEAF_ADMIN_WEB_KEYSTORE_PASSWORD": state["tls"]["keyStorePassword"],
            "SOUTHLEAF_ADMIN_WEB_KEYSTORE_TYPE": "JKS",
        },
    )
    startup = subprocess.STARTUPINFO()
    startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    startup.wShowWindow = 0
    process = subprocess.Popen(
        [str(tools["java"]), "-jar", str(candidate)],
        cwd=str(BACKEND),
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        creationflags=subprocess.CREATE_NEW_CONSOLE,
        startupinfo=startup,
    )
    state["runtimeProcess"] = process
    meta["runtimePid"] = process.pid
    meta["status"] = "https-runtime-starting"
    save_manifest(meta)
    ready: Queue[str] = Queue()
    diagnostics: Queue[str] = Queue()
    threading.Thread(
        target=drain_runtime_stdout,
        args=(process, ready, diagnostics),
        daemon=True,
    ).start()
    expected_marker = "ADMIN_RUNTIME_READY:admin-isolated-v2:HTTPS_WEB"
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            marker = ready.get(timeout=0.25)
        except Empty:
            if process.poll() is not None:
                diagnostic = take_runtime_diagnostic(diagnostics)
                if diagnostic:
                    raise S1DFailure(f"HTTPS_RUNTIME_EXITED_{diagnostic}")
                raise S1DFailure("HTTPS_RUNTIME_EXITED_BEFORE_READY")
            continue
        if marker.startswith("ADMIN_RUNTIME_REFUSED:"):
            reason = marker.partition(":")[2]
            require(bool(re.fullmatch(r"[A-Z0-9_]+", reason)), "HTTPS_RUNTIME_FAILURE_MARKER_INVALID")
            if reason == "INTERNAL":
                diagnostic = take_runtime_diagnostic(diagnostics)
                if diagnostic:
                    reason = f"INTERNAL_{diagnostic}"
            raise S1DFailure(f"HTTPS_RUNTIME_REFUSED_{reason}")
        require(process.poll() is None, "HTTPS_RUNTIME_EXITED_BEFORE_READY")
        require(marker == expected_marker, "HTTPS_RUNTIME_READY_MARKER_INVALID")
        listeners = netstat_listeners(process.pid)
        port = int(meta["webPort"])
        require(len(listeners) == 1 and is_loopback_listener(listeners[0], port), "HTTPS_LISTENER_SCOPE")
        runtime_http_probe(port, str(state["tls"]["caCert"]))
        meta["status"] = "https-runtime-and-same-origin-ui-ready"
        save_manifest(meta)
        return process
    raise S1DFailure("HTTPS_RUNTIME_READY_TIMEOUT")


def run_browser_checks(
    state: dict[str, object], tools: dict[str, Path], account_id: str, passwords: dict[str, str]
) -> int:
    meta = state["meta"]
    profile = Path(str(meta["root"])) / "browser-profile"
    env = child_env(
        {
            "SOUTHLEAF_S1D_FIXTURE_KIND": FIXTURE_KIND,
            "S1D_L5_BASE_URL": f"https://localhost:{meta['webPort']}",
            "S1D_L5_ACCOUNT": account_id,
            "S1D_L5_ACCOUNT_PASSWORD": passwords["account"],
            "S1D_L5_FIXTURE_ROOT": str(meta["root"]),
            "S1D_L5_PROFILE_DIR": str(profile),
            "S1D_L5_EDGE_PATH": str(tools["edge"]),
        }
    )
    process = subprocess.Popen(
        [str(tools["node"]), str(BROWSER_TEST)],
        cwd=str(WEB),
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        creationflags=subprocess.CREATE_NEW_PROCESS_GROUP,
    )
    state["browserProcess"] = process
    meta["browserPid"] = process.pid
    meta["status"] = "isolated-edge-browser-running"
    save_manifest(meta)
    try:
        stdout, stderr = process.communicate(timeout=120)
    except subprocess.TimeoutExpired:
        subprocess.run(
            ["taskkill.exe", "/PID", str(process.pid), "/T", "/F"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=20,
            check=False,
        )
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            pass
        raise S1DFailure("BROWSER_TIMEOUT") from None
    finally:
        if process.poll() is not None:
            state["browserProcess"] = None
            meta["browserPid"] = None
            save_manifest(meta)
    marker = re.search(rb"S1D_BROWSER_PASS:([0-9]+)", stdout)
    if process.returncode != 0 or marker is None:
        safe_reason = re.search(rb"S1D_BROWSER_FAILED:([A-Za-z0-9_-]+)", stderr)
        stage = safe_reason.group(1).decode("ascii") if safe_reason else "browser"
        meta["browserFailureStage"] = stage
        save_manifest(meta)
        stage_code = re.sub(r"[^A-Za-z0-9]+", "_", stage).strip("_").upper()
        if not re.fullmatch(r"[A-Z0-9_]{1,64}", stage_code):
            stage_code = "UNKNOWN"
        raise S1DFailure(f"BROWSER_CHECK_FAILED_{stage_code}")
    count = int(marker.group(1))
    require(count >= 15, "BROWSER_CHECK_COUNT_TOO_LOW")
    meta["browserCheckCount"] = count
    meta["status"] = "browser-api-cookie-csrf-login-logout-passed"
    save_manifest(meta)
    return count


def verify_mysql_auth_facts(state: dict[str, object], account_id: str) -> None:
    schema = str(state["meta"]["schema"])
    expected = {
        "account": f"SELECT COUNT(*) FROM `{schema}`.admin_account WHERE account_id={sql_quote(account_id)} AND enabled=1;",
        "denied": f"SELECT COUNT(*) FROM `{schema}`.admin_auth_event WHERE actor_account_id={sql_quote(account_id)} AND event_type='LOGIN_DENIED';",
        "opened": f"SELECT COUNT(*) FROM `{schema}`.admin_security_audit WHERE actor_account_id={sql_quote(account_id)} AND action='ADMIN_SESSION_OPENED';",
        "revoked": f"SELECT COUNT(*) FROM `{schema}`.admin_security_audit WHERE actor_account_id={sql_quote(account_id)} AND action='ADMIN_SESSION_REVOKED';",
        "migration": f"SELECT COUNT(*) FROM `{schema}`.flyway_admin_schema_history WHERE success=1;",
    }
    actual = {name: mysql_exec(state, query) for name, query in expected.items()}
    require(actual["account"] == "1", "MYSQL_SYNTHETIC_ACCOUNT_FACT_MISSING")
    require(actual["denied"] == "1", "MYSQL_DENIED_LOGIN_EVENT_MISSING")
    require(actual["opened"] == "1" and actual["revoked"] == "1", "MYSQL_SESSION_AUDIT_FACTS_MISSING")
    require(actual["migration"] != "0", "MYSQL_MIGRATION_HISTORY_MISSING")
    state["meta"]["mysqlAuthFactsVerified"] = True
    state["meta"]["status"] = "mysql-account-auth-event-session-audit-verified"
    save_manifest(state["meta"])


def ctrl_c_private_console(process: subprocess.Popen[bytes]) -> None:
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.FreeConsole()
    if not kernel.AttachConsole(process.pid):
        raise OSError("AttachConsole")
    try:
        if not kernel.SetConsoleCtrlHandler(None, True):
            raise OSError("SetConsoleCtrlHandler")
        if not kernel.GenerateConsoleCtrlEvent(0, 0):
            raise OSError("GenerateConsoleCtrlEvent")
        time.sleep(0.2)
    finally:
        kernel.FreeConsole()
        kernel.SetConsoleCtrlHandler(None, False)


def stop_runtime(state: dict[str, object]) -> tuple[bool, bool]:
    process = state.get("runtimeProcess")
    if not isinstance(process, subprocess.Popen):
        return True, True
    meta = state["meta"]
    if process.pid != meta.get("runtimePid") or not verify_manifest(meta):
        return False, False
    if process.poll() is not None:
        return not port_owner(int(meta["webPort"])), False
    graceful = True
    try:
        ctrl_c_private_console(process)
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
    return process.poll() is not None and not port_owner(int(meta["webPort"])), graceful


def stop_mysql(state: dict[str, object]) -> tuple[bool, bool]:
    process = state.get("mysqlProcess")
    if not isinstance(process, subprocess.Popen):
        return True, True
    meta = state["meta"]
    if process.pid != meta.get("mysqlPid") or not verify_manifest(meta):
        return False, False
    if process.poll() is not None:
        return not port_owner(int(meta["mysqlPort"])), False
    graceful = True
    server_uuid = meta.get("serverUuid")
    if server_uuid:
        identity = mysql_query_raw(state, "SELECT @@server_uuid,@@datadir,@@port;", 8)
        if identity is None or identity.returncode != 0:
            graceful = False
        else:
            columns = identity.stdout.decode("utf-8", errors="replace").strip().split("\t")
            if (
                len(columns) != 3
                or columns[0] != server_uuid
                or Path(columns[1]).resolve() != Path(str(meta["datadir"])).resolve()
                or int(columns[2]) != int(meta["mysqlPort"])
            ):
                return False, False
            shutdown = mysql_query_raw(state, "SHUTDOWN;", 12)
            if shutdown is None:
                graceful = False
    else:
        graceful = False
    try:
        process.wait(timeout=25)
    except subprocess.TimeoutExpired:
        graceful = False
    if process.poll() is None:
        try:
            process.terminate()
            process.wait(timeout=15)
        except Exception:
            try:
                process.kill()
                process.wait(timeout=10)
            except Exception:
                return False, False
    return process.poll() is not None and not port_owner(int(meta["mysqlPort"])), graceful


def remove_owned_fixture(state: dict[str, object]) -> tuple[bool, dict[str, bool]]:
    meta = state["meta"]
    cleanup = {"browserStopped": True, "runtimeStopped": False, "mysqlStopped": False, "fixtureRemoved": False}
    if not verify_manifest(meta):
        return False, cleanup
    browser = state.get("browserProcess")
    if isinstance(browser, subprocess.Popen) and browser.poll() is None:
        if browser.pid != meta.get("browserPid"):
            return False, cleanup
        subprocess.run(
            ["taskkill.exe", "/PID", str(browser.pid), "/T", "/F"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=20,
            check=False,
        )
        try:
            browser.wait(timeout=10)
        except subprocess.TimeoutExpired:
            cleanup["browserStopped"] = False
    runtime_ok, runtime_graceful = stop_runtime(state)
    mysql_ok, mysql_graceful = stop_mysql(state)
    cleanup["runtimeStopped"] = runtime_ok
    cleanup["mysqlStopped"] = mysql_ok
    meta["runtimeGracefulShutdown"] = runtime_graceful
    meta["mysqlGracefulShutdown"] = mysql_graceful
    save_manifest(meta)
    if not (cleanup["browserStopped"] and runtime_ok and mysql_ok):
        return False, cleanup
    root = Path(str(meta["root"]))
    if root.name != str(meta["runId"]) or root.parent.resolve().name != "S1DAdminWebVerification":
        return False, cleanup
    if root.is_symlink() or not root.is_dir() or not verify_manifest(meta):
        return False, cleanup
    for port_key in ("mysqlPort", "webPort"):
        if port_owner(int(meta[port_key])):
            return False, cleanup
    try:
        shutil.rmtree(root)
    except OSError:
        return False, cleanup
    cleanup["fixtureRemoved"] = not root.exists()
    return cleanup["fixtureRemoved"], cleanup


def write_evidence(evidence: dict[str, object]) -> bool:
    try:
        EVIDENCE.parent.mkdir(parents=True, exist_ok=True)
        with EVIDENCE.open("x", encoding="utf-8", newline="\n") as stream:
            json.dump(evidence, stream, ensure_ascii=False, sort_keys=True, indent=2)
            stream.write("\n")
        return True
    except (OSError, FileExistsError):
        return False


def run_fixture(tools: dict[str, Path], build: dict[str, Path]) -> int:
    require(not EVIDENCE.exists(), "EVIDENCE_ALREADY_EXISTS")
    run_id = secrets.token_hex(4)
    base = tools["localAppData"] / "SouthLeaf" / "S1DAdminWebVerification"
    base.mkdir(parents=True, exist_ok=True)
    root = base / run_id
    require(not root.exists(), "FIXTURE_RUN_ID_COLLISION")
    root.mkdir()
    try:
        secure_fixture_directory(root)
    except S1DFailure:
        try:
            root.rmdir()
        except OSError:
            pass
        raise
    schema = "southleaf_admin_s1d" + run_id
    mysql_port = allocate_loopback_port(set())
    web_port = allocate_loopback_port({mysql_port})
    source_hash = source_sha256()
    meta: dict[str, object] = {
        "kind": FIXTURE_KIND,
        "runId": run_id,
        "root": str(root.resolve()),
        "host": "127.0.0.1",
        "schema": schema,
        "mysqlPort": mysql_port,
        "webPort": web_port,
        "datadir": str((root / "mysql-data").resolve()),
        "mysqlExecutable": str(MYSQLD.resolve()),
        "mysqlStartMode": "no-monitor",
        "mysqlPid": None,
        "serverUuid": None,
        "runtimePid": None,
        "browserPid": None,
        "candidateSourceSha256": source_hash,
        "status": "manifest-created",
    }
    save_manifest(meta)
    state: dict[str, object] = {
        "root": root,
        "meta": meta,
        "mysqlProcess": None,
        "runtimeProcess": None,
        "browserProcess": None,
        "tls": None,
        "checks": [],
    }
    passwords = {
        "migrator": secrets.token_urlsafe(32),
        "cliDb": secrets.token_urlsafe(32),
        "runtime": secrets.token_urlsafe(32),
        "account": "S1D!a9_" + secrets.token_urlsafe(32),
    }
    status = "FAIL"
    failure_category: str | None = None
    browser_count = 0
    candidate: Path | None = None
    cleanup_result: dict[str, bool] = {}
    try:
        print("S1D_L5_STAGE:tls-fixture")
        state["tls"] = create_tls_fixture(root, tools)
        meta["status"] = "owned-tls-material-created"
        save_manifest(meta)

        print("S1D_L5_STAGE:owned-mysql-initialize-start")
        config = write_mysql_config(root, meta, state["tls"])
        require(sha256_file(config) == meta["mysqlConfigSha256"], "MYSQL_CONFIG_CHANGED")
        data_dir = root / "mysql-data"
        require(data_dir.is_dir() and not any(data_dir.iterdir()), "MYSQL_DATADIR_NOT_EMPTY")
        checked_run(
            [str(MYSQLD), f"--defaults-file={config}", "--initialize-insecure"],
            cwd=MYSQL_HOME,
            timeout=120,
            category="MYSQL_INITIALIZE_FAILED",
        )
        meta["status"] = "owned-empty-mysql-initialized"
        save_manifest(meta)

        print("S1D_L5_STAGE:owned-mysql-tls-start")
        mysql_process = start_mysql(state, config)
        require(mysql_process.pid == meta["mysqlPid"] and verify_manifest(meta), "MYSQL_MANIFEST_PID_MISMATCH")
        state["checks"].append("manifest-owned-mysql-tls-loopback")

        print("S1D_L5_STAGE:isolated-schema-and-migrator")
        create_database_and_migrator(state, passwords)
        state["checks"].append("disposable-schema-and-dedicated-migrator-created")

        print("S1D_L5_STAGE:explicit-management-migration")
        run_migrations(state, tools, build, passwords)
        state["checks"].append("explicit-migrate-and-validate-over-verified-tls")

        print("S1D_L5_STAGE:post-migration-table-scoped-users")
        create_scoped_database_users(state, passwords)
        state["checks"].append("schema-users-exactly-scoped-to-disposable-fixture")

        print("S1D_L5_STAGE:c3-synthetic-staff")
        account_id = bootstrap_fixture_account(state, tools, build, passwords)
        state["checks"].append("test-only-secretreader-c3-cli-bootstrap")

        print("S1D_L5_STAGE:candidate-https-runtime-ui")
        candidate = static_candidate(root, build, meta)
        runtime_process = start_runtime(state, tools, candidate, passwords)
        require(runtime_process.poll() is None and verify_manifest(meta), "HTTPS_RUNTIME_OWNERSHIP_LOST")
        state["checks"].append("https-servlet-startup-same-origin-ui-and-csrf")

        print("S1D_L5_STAGE:edge-browser-authentication")
        browser_count = run_browser_checks(state, tools, account_id, passwords)
        require(runtime_process.poll() is None and bool(netstat_listeners(runtime_process.pid)), "HTTPS_RUNTIME_DIED")
        state["checks"].append("edge-csrf-origin-login-session-cookie-logout")

        print("S1D_L5_STAGE:mysql-audit-and-session-facts")
        verify_mysql_auth_facts(state, account_id)
        state["checks"].append("mysql-account-denial-and-session-audit-facts")
        status = "PASS"
    except S1DFailure as error:
        failure_category = error.category
    except Exception:
        failure_category = "INTERNAL"
    finally:
        clean, cleanup_result = remove_owned_fixture(state)
        if not clean:
            status = "FAIL"
            failure_category = "CLEANUP"
        elif status == "PASS" and (
            not meta.get("runtimeGracefulShutdown") or not meta.get("mysqlGracefulShutdown")
        ):
            status = "FAIL"
            failure_category = "FORCED_SHUTDOWN"
        passwords.clear()

    evidence = {
        "kind": "S1D_ISOLATED_L5",
        "status": status,
        "runId": run_id,
        "checkedAtUtc": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "candidateSourceSha256": meta.get("candidateSourceSha256"),
        "candidateJarSha256": meta.get("candidateJarSha256"),
        "checkCount": len(state["checks"]) + browser_count,
        "browserCheckCount": browser_count,
        "checks": state["checks"],
        "failureCategory": failure_category,
        "cleanup": {
            "browserStopped": cleanup_result.get("browserStopped", False),
            "runtimeStopped": cleanup_result.get("runtimeStopped", False),
            "mysqlStopped": cleanup_result.get("mysqlStopped", False),
            "fixtureRemoved": cleanup_result.get("fixtureRemoved", False),
            "complete": bool(cleanup_result.get("fixtureRemoved", False)),
            "runtimeGraceful": bool(meta.get("runtimeGracefulShutdown", False)),
            "mysqlGraceful": bool(meta.get("mysqlGracefulShutdown", False)),
        },
        "secretsRecorded": False,
        "realOrProductionDataUsed": False,
        "c2FixtureUsed": False,
    }
    evidence_written = write_evidence(evidence)
    if not evidence_written:
        print("S1D_L5_EVIDENCE_WRITE_FAILED")
        return 2
    if status == "PASS":
        print(f"S1D_L5_PASS:{run_id}:browser_checks={browser_count}:cleanup=verified")
        return 0
    print(f"S1D_L5_FAIL:{failure_category or 'UNKNOWN'}:cleanup={'verified' if cleanup_result.get('fixtureRemoved') else 'incomplete'}")
    return 2


def configure_evidence_path(value: str | None) -> None:
    global EVIDENCE
    evidence_dir = (REPO / "docs" / "开发与演示" / "证据").resolve()
    if value:
        file_name = Path(value)
        require(
            not file_name.is_absolute() and file_name.name == value,
            "EVIDENCE_PATH_UNSAFE",
        )
        candidate = evidence_dir / file_name
    else:
        candidate = EVIDENCE
    try:
        safe_parent = candidate.parent.resolve() == evidence_dir
        is_symlink = candidate.is_symlink()
    except OSError:
        raise S1DFailure("EVIDENCE_PATH_UNSAFE") from None
    require(
        safe_parent
        and candidate.suffix.lower() == ".json"
        and candidate.name.startswith("stage-two-s1d-isolated-l5"),
        "EVIDENCE_PATH_UNSAFE",
    )
    require(not is_symlink, "EVIDENCE_PATH_SYMLINK")
    EVIDENCE = candidate.resolve()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-disposable-s1d-l5", action="store_true")
    parser.add_argument("--evidence-path", help="证据目录内新文件的文件名（不是路径）")
    args = parser.parse_args()
    if not args.confirm_disposable_s1d_l5:
        print("S1D_L5_NOT_RUN:EXPLICIT_FIXTURE_CONFIRMATION_REQUIRED")
        return 3
    try:
        configure_evidence_path(args.evidence_path)
    except S1DFailure as error:
        print(f"S1D_L5_NOT_RUN:{error.category}")
        return 3
    try:
        tools = discover_tools()
        build = check_build_inputs()
    except S1DFailure as error:
        print(f"S1D_L5_NOT_RUN:{error.category}")
        return 3
    try:
        return run_fixture(tools, build)
    except S1DFailure as error:
        print(f"S1D_L5_NOT_RUN:{error.category}")
        return 3
    except Exception:
        print("S1D_L5_NOT_RUN:INTERNAL_PREFLIGHT")
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
