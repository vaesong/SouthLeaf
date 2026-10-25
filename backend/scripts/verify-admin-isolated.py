"""Explicitly authorized Windows-only isolated MySQL runtime/credential verification.
Never targets an existing server: owns a unique private datadir, shared-memory name and child handle.
Secrets are random, DPAPI-protected for the current user, and passed only by environment/stdin.
Only an owned test fixture may be mutated; no existing services, production data or secret logging.
"""
import argparse
import csv
import ctypes
from ctypes import wintypes
import hashlib
import json
import os
from pathlib import Path
import re
import queue
import threading
import secrets
import shutil
import socket
import subprocess
import sys
import time
import uuid
import urllib.error
import urllib.request
import zipfile

REPO = Path(__file__).resolve().parents[2]
MYSQL_HOME = Path(r"D:\SoftWare\MySQL")
MYSQL = MYSQL_HOME / "bin/mysql.exe"
SERVER = MYSQL_HOME / "bin/mysqld.exe"
OPENSSL = Path(r"D:\SoftWare\Git\Git\usr\bin\openssl.exe")
KEYTOOL = Path(r"D:\SoftWare\JDK_21\bin\keytool.exe")
JAVA = Path(r"D:\SoftWare\JDK_21\bin\java.exe")
BASE = Path(os.environ.get("LOCALAPPDATA", "")) / "SouthLeaf/S1BVerification"
SCHEMA = "southleaf_admin_s1b_it"
PRODUCTION_FIXTURE = "southleaf_admin"  # Only in this new private TEST instance.
PUBLIC_IT_SCHEMA = "southleaf_public_it"
PUBLIC_RUNTIME_SCHEMA = "southleaf_public"
JAR = REPO / "backend/admin-runtime/target/admin-runtime-0.1.0-SNAPSHOT.jar"
PUBLIC_JAR = REPO / "backend/public-api/target/uniapp-api-0.1.0-SNAPSHOT.jar"
STEP = "preflight"


class VerificationFailure(Exception):
    pass


def require(condition, code):
    if not condition:
        raise VerificationFailure(code)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def clean_env(extra=None):
    env = {k: v for k, v in os.environ.items() if not k.upper().startswith(
        ("SOUTHLEAF_", "SPRING_", "LOGGING_", "LOGBACK_", "LOG_", "SERVER_", "MYSQL_"))
        and k.upper() not in ("DEBUG", "TRACE", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")}
    env.update(extra or {})
    return env


def run(command, *, env=None, data=None, timeout=45, expected=0, marker=None):
    # stdin must be explicit: detaching from the private Java console invalidates inherited
    # Windows console handles, even though our captured output pipes remain valid.
    result = subprocess.run([str(x) for x in command], input=data, env=clean_env(env),
                            stdin=subprocess.DEVNULL if data is None else None,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout,
                            cwd=REPO)
    if result.returncode != expected or (marker and marker.encode() not in result.stdout):
        codes = sorted(set(re.findall(rb"ERROR ([0-9]{4})", result.stdout)))
        # A smoke assertion is an authored, non-secret test label; retain only that bounded label.
        assertions = sorted(set(match.decode("ascii", "ignore") for match in re.findall(
            rb"FAILED: ([A-Za-z0-9 _=./-]{1,160})", result.stdout)))
        ps_lines = sorted(set(match.decode("ascii", "ignore") for match in re.findall(
            rb"smoke-(?:public|safety)[.]ps1:(\d+)", result.stdout)))
        curl_codes = sorted(set(match.decode("ascii", "ignore") for match in re.findall(
            rb"curl: [(]([0-9]{1,3})[)]", result.stdout)))
        smoke_steps = re.findall(rb"SMOKE_HTTP_EXPECT (?:GET|HEAD) ([A-Za-z0-9_/?=&.-]{1,160})", result.stdout)
        last_smoke_step = smoke_steps[-1].decode("ascii", "ignore") if smoke_steps else ""
        # Never emit arbitrary library/SQL output, command parameters or environment values.
        raise VerificationFailure("SUBPROCESS_FAILED:exit=" + str(result.returncode)
                                  + ":output-sha256=" + hashlib.sha256(result.stdout).hexdigest()
                                  + ":mysql-codes=" + ",".join(c.decode() for c in codes)
                                  + ":assertions=" + ",".join(assertions[:3])
                                  + ":powershell-lines=" + ",".join(ps_lines[:3])
                                  + ":curl-codes=" + ",".join(curl_codes[:3])
                                  + ":last-smoke-step=" + last_smoke_step)
    return result.stdout


class Blob(ctypes.Structure):
    _fields_ = [("size", wintypes.DWORD), ("data", ctypes.POINTER(ctypes.c_ubyte))]


def protect(data, decrypt=False):
    buffer = (ctypes.c_ubyte * len(data)).from_buffer_copy(data)
    source = Blob(len(data), buffer)
    target = Blob()
    crypt = ctypes.WinDLL("crypt32", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    if decrypt:
        ok = crypt.CryptUnprotectData(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target))
    else:
        ok = crypt.CryptProtectData(ctypes.byref(source), "SouthLeaf isolated S1-B test credentials",
                                    None, None, None, 1, ctypes.byref(target))
    require(ok, "DPAPI_FAILED")
    try:
        return ctypes.string_at(target.data, target.size)
    finally:
        kernel.LocalFree.argtypes = [ctypes.c_void_p]
        kernel.LocalFree(target.data)


def mysql_command(meta, user="root", tcp=False):
    command = [MYSQL, "--no-defaults", "--no-login-paths", "--batch", "--raw", "--skip-column-names", "--connect-timeout=2", "--user=" + user]
    if tcp:
        command += ["--protocol=TCP", "--host=localhost", "--port=" + str(meta["port"]),
                    "--ssl-mode=VERIFY_IDENTITY", "--ssl-ca=" + str(Path(meta["root"]) / "ca.pem")]
    else:
        command += ["--protocol=MEMORY", "--shared-memory-base-name=" + meta["memoryName"]]
    return command


def sql(meta, password, statement, user="root", tcp=False, expected=0):
    return run(mysql_command(meta, user, tcp), env={"MYSQL_PWD": password} if password else {},
               data=statement.encode("utf-8"), expected=expected)


def listeners(pid):
    output = run(["netstat", "-ano", "-p", "TCP"]).decode(errors="replace")
    return [row.split()[1] for row in output.splitlines() if len(row.split()) >= 5
            and row.split()[-1] == str(pid) and row.split()[-2] == "LISTENING"]


def start_server(meta, password, bootstrap=False):
    root = Path(meta["root"])
    require(digest(root / "server.ini") == meta["configSha256"], "CONFIG_CHANGED")
    process = subprocess.Popen([str(SERVER), "--defaults-file=" + str(root / "server.ini"), "--no-monitor",
                                "--skip-networking=" + ("ON" if bootstrap else "OFF")],
                               env=clean_env(), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        until = time.monotonic() + 45
        while time.monotonic() < until:
            require(process.poll() is None, "OWNED_SERVER_EXITED_BEFORE_READY")
            result = subprocess.run([str(x) for x in mysql_command(meta)],
                                    input=b"SELECT @@server_uuid,@@datadir;", env=clean_env({"MYSQL_PWD": password} if password else {}),
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
            if result.returncode == 0:
                columns = result.stdout.decode().strip().split("\t")
                require(len(columns) == 2 and columns[0] == meta["serverUuid"], "WRONG_INSTANCE_UUID")
                require(Path(columns[1]).resolve() == (root / "data").resolve(), "WRONG_INSTANCE_DATADIR")
                actual = listeners(process.pid)
                if not bootstrap and not actual:
                    time.sleep(0.1)
                    continue
                require(actual == ([] if bootstrap else ["127.0.0.1:" + str(meta["port"])]), "UNEXPECTED_INSTANCE_LISTENER")
                return process
            time.sleep(0.2)
        raise VerificationFailure("OWNED_SERVER_NOT_READY")
    except BaseException:
        process.terminate()
        process.wait(timeout=20)
        raise


def stop_server(meta, password, process):
    if process.poll() is None:
        try:
            sql(meta, password, "SHUTDOWN;")
            process.wait(timeout=30)
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=15)
    require(process.returncode == 0, "OWNED_SERVER_NOT_CLEANLY_STOPPED")


def privileges(schema, user):
    return "\n".join([
        f"GRANT SELECT ON `{schema}`.admin_account TO '{user}'@'localhost';",
        f"GRANT SELECT ON `{schema}`.admin_account_permission TO '{user}'@'localhost';",
        f"GRANT SELECT,INSERT,UPDATE ON `{schema}`.admin_session TO '{user}'@'localhost';",
        f"GRANT INSERT ON `{schema}`.admin_security_audit TO '{user}'@'localhost';",
        f"GRANT INSERT ON `{schema}`.admin_auth_event TO '{user}'@'localhost';"])


def runtime_env(meta, passwords, user="s1b_writer", mode="demo", host="localhost", tls="VERIFY_IDENTITY"):
    schema = PRODUCTION_FIXTURE if mode == "production" else SCHEMA
    return {"SOUTHLEAF_ADMIN_MODE": mode, "SOUTHLEAF_ADMIN_DB_URL": f"jdbc:mysql://{host}:{meta['port']}/{schema}?sslMode={tls}",
            "SOUTHLEAF_ADMIN_DB_USERNAME": user, "SOUTHLEAF_ADMIN_DB_PASSWORD": passwords[user],
            "SOUTHLEAF_ADMIN_DB_SCHEMA": schema, "SOUTHLEAF_ADMIN_DB_EXPECTED_USER": user + "@localhost"}


def java_options(meta, trusted=True):
    return [JAVA] + (["-Djavax.net.ssl.trustStore=" + str(Path(meta["root"]) / "truststore.jks"),
                      "-Djavax.net.ssl.trustStoreType=JKS"] if trusted else [])


def migrate(meta, passwords, schema):
    cp = os.pathsep.join(str(REPO / p) for p in [
        "backend/admin-migration-cli/target/admin-migration-cli-0.1.0-SNAPSHOT.jar",
        "backend/admin-db-policy/target/admin-db-policy-0.1.0-SNAPSHOT.jar",
        "backend/admin-migration-cli/target/dependency/*"])
    values = {"SOUTHLEAF_ADMIN_MIGRATION_URL": f"jdbc:mysql://localhost:{meta['port']}/{schema}?sslMode=VERIFY_IDENTITY&allowPublicKeyRetrieval=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
              "SOUTHLEAF_ADMIN_MIGRATION_SCHEMA": schema, "SOUTHLEAF_ADMIN_MIGRATION_USERNAME": "s1b_migrator",
              "SOUTHLEAF_ADMIN_MIGRATION_PASSWORD": passwords["s1b_migrator"]}
    run(java_options(meta) + ["-cp", cp, "cn.zhinan.adminmigration.AdminDatabaseMigrationMain", "--migrate"],
        env=values, marker="ADMIN_SCHEMA_MIGRATION_PASS")
    run(java_options(meta) + ["-cp", cp, "cn.zhinan.adminmigration.AdminDatabaseMigrationMain", "--migrate"],
        env=values, marker="ADMIN_SCHEMA_MIGRATION_PASS")
    run(java_options(meta) + ["-cp", cp, "cn.zhinan.adminmigration.AdminDatabaseMigrationMain", "--validate"],
        env=values, marker="ADMIN_SCHEMA_VALIDATION_PASS")


def prepare():
    global STEP
    STEP = "private-directory-and-tools"
    require(os.name == "nt" and os.environ.get("LOCALAPPDATA"), "WINDOWS_REQUIRED")
    for path in [MYSQL, SERVER, OPENSSL, KEYTOOL, JAVA, JAR]:
        require(path.is_file(), "APPROVED_TOOL_OR_ARTIFACT_MISSING")
    require(shutil.disk_usage(BASE.parent if BASE.parent.exists() else Path(os.environ["LOCALAPPDATA"])).free > 1024**3, "INSUFFICIENT_PRIVATE_DISK")
    root = BASE / ("s1b-" + uuid.uuid4().hex[:12])
    root.mkdir(parents=True, exist_ok=False)
    sid_output = run(["whoami", "/user", "/fo", "csv", "/nh"]).decode(errors="replace")
    sid = list(csv.reader(sid_output.splitlines()))[0][1]
    require(re.fullmatch(r"S-1-(?:[0-9]+-)*[0-9]+", sid), "USER_SID_UNAVAILABLE")
    run(["icacls", root, "/inheritance:r", "/grant:r", "*" + sid + ":(OI)(CI)F", "*S-1-5-18:(OI)(CI)F"])
    with socket.socket() as candidate:
        candidate.bind(("127.0.0.1", 0))
        port = candidate.getsockname()[1]
    passwords = {u: secrets.token_hex(24) for u in ["root", "s1b_migrator", "s1b_writer", "s1b_prod_writer", "s1b_probe", "s1b_reader", "s1b_public_it", "s1b_public_reader", "truststore"]}
    encrypted = protect(json.dumps(passwords).encode())
    require(json.loads(protect(encrypted, True)) == passwords, "DPAPI_ROUNDTRIP_FAILED")
    (root / "credentials.dpapi").write_bytes(encrypted)
    STEP = "test-certificates"
    config = root / "openssl.cnf"
    config.write_text("[req]\ndistinguished_name=dn\n[dn]\n[ca]\nbasicConstraints=critical,CA:true\nkeyUsage=critical,keyCertSign,cRLSign\n[server]\nbasicConstraints=critical,CA:false\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost\n", encoding="ascii")
    run([OPENSSL, "req", "-new", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", root / "ca-key.pem", "-out", root / "ca.pem", "-days", "2", "-sha256", "-subj", "/CN=SouthLeaf S1B Isolated Test CA", "-config", config, "-extensions", "ca"])
    run([OPENSSL, "req", "-new", "-newkey", "rsa:2048", "-nodes", "-keyout", root / "server-key.pem", "-out", root / "server.csr", "-subj", "/CN=localhost", "-config", config])
    run([OPENSSL, "x509", "-req", "-in", root / "server.csr", "-CA", root / "ca.pem", "-CAkey", root / "ca-key.pem", "-set_serial", "1", "-days", "2", "-sha256", "-extfile", config, "-extensions", "server", "-out", root / "server-cert.pem"])
    run([KEYTOOL, "-importcert", "-noprompt", "-alias", "s1b-test-ca", "-file", root / "ca.pem", "-keystore", root / "truststore.jks", "-storetype", "JKS", "-storepass:env", "SOUTHLEAF_TEST_TRUSTSTORE_PASSWORD"], env={"SOUTHLEAF_TEST_TRUSTSTORE_PASSWORD": passwords["truststore"]})
    # Truststore holds public CA only; JSSE opens JKS without an integrity password. Private-dir ACL
    # and recorded fingerprint protect this test fixture. No production trust configuration changes.
    memory = "SOUTHLEAF_S1B_" + uuid.uuid4().hex
    settings = "\n".join(["[mysqld]", "basedir=" + MYSQL_HOME.as_posix(), "datadir=" + (root / "data").as_posix(),
        "port=" + str(port), "bind-address=127.0.0.1", "mysqlx=OFF", "shared-memory=ON", "shared-memory-base-name=" + memory,
        "require_secure_transport=ON", "ssl-ca=" + (root / "ca.pem").as_posix(), "ssl-cert=" + (root / "server-cert.pem").as_posix(),
        "ssl-key=" + (root / "server-key.pem").as_posix(), "tls_version=TLSv1.2,TLSv1.3", "skip-log-bin", "general-log=OFF",
        "slow-query-log=OFF", "performance_schema=OFF", "max_connections=12", "innodb_buffer_pool_size=64M",
        "innodb_redo_log_capacity=32M", "log-error=" + (root / "server-error.log").as_posix(), "log_error_verbosity=2"]) + "\n"
    (root / "server.ini").write_text(settings, encoding="ascii")
    meta = {"kind": "southleaf-s1b-explicitly-authorized-test", "root": str(root), "port": port, "memoryName": memory,
            "configSha256": digest(root / "server.ini"), "testCaSha256": digest(root / "ca.pem"), "truststoreSha256": digest(root / "truststore.jks"),
            "status": "initializing", "createdAt": time.strftime("%Y-%m-%dT%H:%M:%S%z")}
    manifest = root / "manifest.json"
    manifest.write_text(json.dumps(meta, indent=2), encoding="utf-8")
    STEP = "initialize-owned-datadir"
    run([SERVER, "--defaults-file=" + str(root / "server.ini"), "--initialize-insecure"], timeout=90)
    meta["serverUuid"] = re.search(r"server-uuid=(\S+)", (root / "data/auto.cnf").read_text()).group(1)
    manifest.write_text(json.dumps(meta, indent=2), encoding="utf-8")
    STEP = "local-only-bootstrap"
    server = start_server(meta, "", bootstrap=True)
    root_password = ""
    try:
        sql(meta, "", "ALTER USER 'root'@'localhost' IDENTIFIED BY '" + passwords["root"] + "';")
        root_password = passwords["root"]
        statements = [f"CREATE DATABASE `{SCHEMA}`;", f"CREATE DATABASE `{PRODUCTION_FIXTURE}`;",
                      f"CREATE DATABASE `{PUBLIC_IT_SCHEMA}` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;",
                      f"CREATE DATABASE `{PUBLIC_RUNTIME_SCHEMA}` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;",
                      f"CREATE TABLE `{PUBLIC_RUNTIME_SCHEMA}`.s1b_canary(id INT PRIMARY KEY);",
                      f"INSERT INTO `{PUBLIC_RUNTIME_SCHEMA}`.s1b_canary VALUES(1);", "CREATE ROLE 's1b_role'@'localhost';"]
        for user in ["s1b_migrator", "s1b_writer", "s1b_prod_writer", "s1b_probe", "s1b_reader", "s1b_public_it", "s1b_public_reader"]:
            statements.append(f"CREATE USER '{user}'@'localhost' IDENTIFIED BY '{passwords[user]}' REQUIRE SSL;")
        for schema in [SCHEMA, PRODUCTION_FIXTURE]:
            statements.append(f"GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,DROP,INDEX,REFERENCES ON `{schema}`.* TO 's1b_migrator'@'localhost';")
        statements.append(f"GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,DROP,INDEX,REFERENCES,LOCK TABLES ON `{PUBLIC_IT_SCHEMA}`.* TO 's1b_public_it'@'localhost';")
        statements.append(f"GRANT SELECT ON `{PUBLIC_RUNTIME_SCHEMA}`.* TO 's1b_public_reader'@'localhost';")
        statements.append(f"GRANT SELECT ON `{PUBLIC_RUNTIME_SCHEMA}`.* TO 's1b_reader'@'localhost';")
        sql(meta, root_password, "\n".join(statements))
    finally:
        stop_server(meta, root_password, server)
    meta["status"] = "bootstrapped-and-cleanly-stopped"
    manifest.write_text(json.dumps(meta, indent=2), encoding="utf-8")
    STEP = "strict-tls-explicit-migration"
    server = start_server(meta, root_password)
    try:
        for schema in [SCHEMA, PRODUCTION_FIXTURE]:
            migrate(meta, passwords, schema)
        sql(meta, root_password, privileges(SCHEMA, "s1b_writer") + privileges(SCHEMA, "s1b_probe") + privileges(PRODUCTION_FIXTURE, "s1b_prod_writer")
            + f"INSERT INTO `{SCHEMA}`.admin_account(account_id,enabled,security_version,credential_hash) VALUES('s1b_fixture_account',1,1,'NOT_A_LOGIN_CREDENTIAL_FIXTURE');"
            + f"INSERT INTO `{SCHEMA}`.admin_account_permission VALUES('s1b_fixture_account','CONTENT_READ');")
        meta["status"] = "prepared-and-cleanly-stopped"
    finally:
        stop_server(meta, root_password, server)
        manifest.write_text(json.dumps(meta, indent=2), encoding="utf-8")
    print(json.dumps({"result": "PREPARED", "manifest": str(manifest), "newInstanceStopped": True,
                      "existingInstancesModified": False, "strictTlsMigrations": 2, "certificatesExpireInDays": 2}, indent=2))


def load_manifest(path):
    file = Path(path).resolve()
    require(file.parent.parent == BASE.resolve() and file.name == "manifest.json", "NOT_OWNED_MANIFEST_LOCATION")
    meta = json.loads(file.read_text(encoding="utf-8"))
    require(meta.get("kind") == "southleaf-s1b-explicitly-authorized-test" and Path(meta["root"]).resolve() == file.parent, "NOT_OWNED_INSTANCE")
    require(meta.get("status") in ["prepared-and-cleanly-stopped", "verified-and-cleanly-stopped"], "INSTANCE_PREPARATION_INCOMPLETE")
    require(digest(file.parent / "truststore.jks") == meta["truststoreSha256"] and digest(file.parent / "ca.pem") == meta["testCaSha256"], "TRUST_FIXTURE_CHANGED")
    passwords = json.loads(protect((file.parent / "credentials.dpapi").read_bytes(), True))
    return file, meta, passwords


def ctrl_c_private_console(process):
    # Only attach to the new Java child's own CREATE_NEW_CONSOLE console, never an existing console.
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.FreeConsole()
    require(kernel.AttachConsole(process.pid), "CANNOT_ATTACH_OWNED_JAVA_CONSOLE")
    try:
        require(kernel.SetConsoleCtrlHandler(None, True), "CANNOT_MASK_PARENT_CONSOLE_EVENT")
        require(kernel.GenerateConsoleCtrlEvent(0, 0), "CANNOT_SIGNAL_OWNED_JAVA_CONSOLE")
        time.sleep(0.2)
    finally:
        kernel.FreeConsole()
        kernel.SetConsoleCtrlHandler(None, False)


def public_test_env(meta, passwords):
    port = meta["port"]
    suffix = "?sslMode=VERIFY_IDENTITY&allowPublicKeyRetrieval=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
    return {
        "SOUTHLEAF_PUBLIC_IT_ISOLATED": "owned",
        "SOUTHLEAF_PUBLIC_IT_TEST_URL": f"jdbc:mysql://localhost:{port}/{PUBLIC_IT_SCHEMA}" + suffix,
        "SOUTHLEAF_PUBLIC_IT_TEST_USERNAME": "s1b_public_it",
        "SOUTHLEAF_PUBLIC_IT_TEST_PASSWORD": passwords["s1b_public_it"],
        "SOUTHLEAF_PUBLIC_IT_RUNTIME_URL": f"jdbc:mysql://localhost:{port}/{PUBLIC_RUNTIME_SCHEMA}" + suffix,
        "SOUTHLEAF_PUBLIC_IT_RUNTIME_USERNAME": "s1b_public_reader",
        "SOUTHLEAF_PUBLIC_IT_RUNTIME_PASSWORD": passwords["s1b_public_reader"],
        "SOUTHLEAF_PUBLIC_IT_MEDIA_ROOT": str(Path(meta["root"]) / "public-media"),
        "JAVA_TOOL_OPTIONS": "-Djavax.net.ssl.trustStore=" + str(Path(meta["root"]) / "truststore.jks")
        + " -Djavax.net.ssl.trustStoreType=JKS"}


def security_test_env(meta, passwords):
    return {
        "SOUTHLEAF_SECURITY_IT_URL": f"jdbc:mysql://localhost:{meta['port']}/{SCHEMA}?sslMode=VERIFY_IDENTITY&allowPublicKeyRetrieval=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "SOUTHLEAF_SECURITY_IT_USERNAME": "s1b_migrator",
        "SOUTHLEAF_SECURITY_IT_PASSWORD": passwords["s1b_migrator"],
        "SOUTHLEAF_SECURITY_IT_EXPECTED_USER": "s1b_migrator@localhost",
        "SOUTHLEAF_SECURITY_IT_UUID": meta["serverUuid"],
        "SOUTHLEAF_SECURITY_IT_TRUSTSTORE": str(Path(meta["root"]) / "truststore.jks")
    }


def source_snapshot():
    """Bind verification to source inputs, never generated outputs or local credential files."""
    backend = REPO / "backend"
    paths = [backend / "pom.xml"]
    for module in ("public-api", "admin-security-core", "admin-credential-crypto",
                   "admin-db-policy", "admin-migration-cli", "admin-security-jdbc", "admin-runtime"):
        paths.append(backend / module / "pom.xml")
        paths.extend(p for p in (backend / module / "src").rglob("*") if p.is_file())
    paths.extend(p for p in (backend / "scripts").iterdir()
                 if p.is_file() and p.suffix in (".py", ".ps1", ".mjs"))
    require(all(p.is_file() and not p.is_symlink() for p in paths), "SOURCE_INPUT_MISSING")
    return {p.relative_to(REPO).as_posix(): digest(p) for p in sorted(set(paths))}


def atomic_json(path, value):
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        temporary.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def stop_owned_command(process):
    if process.poll() is None:
        if os.name == "nt":
            subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                           stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, timeout=20, check=False)
        else:
            process.kill()
        return process.communicate(timeout=20)[0]
    return b""


def prepare_owned_public_fixture(meta, passwords):
    """Reset, migrate and seed both public schemas inside this verifier's owned MySQL instance."""
    values = public_test_env(meta, passwords)
    sql(meta, passwords["root"],
        f"DROP DATABASE IF EXISTS `{PUBLIC_IT_SCHEMA}`; DROP DATABASE IF EXISTS `{PUBLIC_RUNTIME_SCHEMA}`; "
        f"CREATE DATABASE `{PUBLIC_IT_SCHEMA}` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin; "
        f"CREATE DATABASE `{PUBLIC_RUNTIME_SCHEMA}` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;")

    def migrate_and_seed(url, schema):
        environment = dict(values)
        environment.update({
            "SOUTHLEAF_MIGRATION_URL": url,
            "SOUTHLEAF_MIGRATION_USERNAME": "root",
            "SOUTHLEAF_MIGRATION_PASSWORD": passwords["root"],
            "SOUTHLEAF_MEDIA_ROOT": values["SOUTHLEAF_PUBLIC_IT_MEDIA_ROOT"]})
        result = subprocess.run(
            ["mvnw.cmd", "-f", "public-api/pom.xml", "-DskipTests", "compile",
             "org.codehaus.mojo:exec-maven-plugin:3.5.0:java",
             "-Dexec.mainClass=cn.zhinan.uniapp.tooling.DatabaseMigrationMain",
             "-Dexec.args=--seed-demo"],
            cwd=REPO / "backend", env=clean_env(environment), stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=300)
        categories = [name for name, marker in (
            ("MAVEN_PLUGIN", b"No plugin found"), ("MIGRATION_CLASS", b"DatabaseMigrationMain"),
            ("TLS", b"SSLHandshakeException"), ("MYSQL_ACCESS", b"Access denied"),
            ("MYSQL_CONNECT", b"CommunicationsException"), ("FLYWAY", b"Flyway"),
            ("SEED", b"Demo import")) if marker in result.stdout]
        error_types = sorted(set(match.decode("ascii", "ignore") for match in re.findall(
            rb"[A-Za-z][A-Za-z0-9_.]*(?:Exception|Error)", result.stdout)))
        require(result.returncode == 0 and b"EXPLICIT_DEMO_IMPORT_PASS" in result.stdout,
                "PUBLIC_FIXTURE_PREPARATION_FAILED:" + schema + ":exit=" + str(result.returncode)
                + ":categories=" + ",".join(categories)
                + ":error-types=" + ",".join(error_types[:8])
                + ":output-sha256=" + hashlib.sha256(result.stdout).hexdigest())

    migrate_and_seed(values["SOUTHLEAF_PUBLIC_IT_TEST_URL"], PUBLIC_IT_SCHEMA)
    migrate_and_seed(values["SOUTHLEAF_PUBLIC_IT_RUNTIME_URL"], PUBLIC_RUNTIME_SCHEMA)
    sql(meta, passwords["root"],
        f"CREATE TABLE `{PUBLIC_RUNTIME_SCHEMA}`.s1b_canary(id INT PRIMARY KEY); "
        f"INSERT INTO `{PUBLIC_RUNTIME_SCHEMA}`.s1b_canary VALUES(1);")
    return values


def run_reactor_verification(meta, passwords, public_environment=None):
    """Full reactor + boundaries, before candidate pinning; no intermediate install/rebuild."""
    command = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
               str(REPO / "backend/scripts/Test-DatabaseIntegration.ps1")]
    environment = security_test_env(meta, passwords)
    if public_environment:
        environment.update(public_environment)
    process = subprocess.Popen(command, cwd=REPO,
                               env=clean_env(environment),
                               stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT)
    try:
        output, _ = process.communicate(timeout=600)
    except BaseException:
        stop_owned_command(process)
        raise
    # Even a failed compiler/test report could contain a credential: never echo raw child output.
    error_types = sorted(set(match.decode("ascii", "ignore") for match in re.findall(
        rb"[A-Za-z][A-Za-z0-9_.]*(?:Exception|Error)", output)))
    failing_tests = sorted(set(match.decode("ascii", "ignore") for match in re.findall(
        rb"(?:[A-Za-z0-9_]+[.])+(?:[A-Za-z0-9_]*(?:IT|Test))[.][A-Za-z0-9_]+", output)))
    require(process.returncode == 0, "REACTOR_FAILED:exit=" + str(process.returncode)
            + ":error-types=" + ",".join(error_types[:8])
            + ":failing-tests=" + ",".join(failing_tests[:8])
            + ":output-sha256=" + hashlib.sha256(output).hexdigest())
    return hashlib.sha256(output).hexdigest()


def run_owned_public_smoke(meta, public_environment):
    """Exercise the packaged public API only against this owned reader fixture and a loopback port."""
    require(PUBLIC_JAR.is_file(), "PREPARED_PUBLIC_JAR_REQUIRED")
    jar_hash = digest(PUBLIC_JAR)
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
    environment = {
        "SOUTHLEAF_DB_URL": public_environment["SOUTHLEAF_PUBLIC_IT_RUNTIME_URL"],
        "SOUTHLEAF_DB_USERNAME": public_environment["SOUTHLEAF_PUBLIC_IT_RUNTIME_USERNAME"],
        "SOUTHLEAF_DB_PASSWORD": public_environment["SOUTHLEAF_PUBLIC_IT_RUNTIME_PASSWORD"],
        "SOUTHLEAF_MEDIA_ROOT": public_environment["SOUTHLEAF_PUBLIC_IT_MEDIA_ROOT"],
        "SOUTHLEAF_BIND_ADDRESS": "127.0.0.1", "PORT": str(port),
        "JAVA_TOOL_OPTIONS": public_environment["JAVA_TOOL_OPTIONS"],
        "LOGGING_FILE_NAME": str(Path(meta["root"]) / "public-smoke.log"),
        "SOUTHLEAF_SMOKE_TRACE": "1"}
    process = subprocess.Popen(["java", "-jar", str(PUBLIC_JAR)], cwd=Path(meta["root"]),
                               env=clean_env(environment), stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try:
        ready_at = time.monotonic() + 75
        while time.monotonic() < ready_at:
            require(process.poll() is None, "PUBLIC_CANDIDATE_EXITED_BEFORE_READY")
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=0.5):
                    break
            except OSError:
                time.sleep(0.2)
        else:
            raise VerificationFailure("PUBLIC_CANDIDATE_NOT_READY")
        base_url = "http://127.0.0.1:" + str(port)
        application_ready_at = time.monotonic() + 75
        while time.monotonic() < application_ready_at:
            require(process.poll() is None, "PUBLIC_CANDIDATE_EXITED_DURING_APPLICATION_READY")
            try:
                with urllib.request.urlopen(base_url + "/api/v1/public/home", timeout=2) as response:
                    if response.status == 200:
                        response.read(1)
                        break
            except BaseException:
                time.sleep(0.2)
        else:
            raise VerificationFailure("PUBLIC_CANDIDATE_APPLICATION_NOT_READY")
        try:
            smoke = run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                         REPO / "backend/scripts/smoke-public.ps1", "-BaseUrl", base_url],
                        env=environment, timeout=120, marker="PUBLIC_SMOKE_PASS")
        except VerificationFailure as error:
            raise VerificationFailure("PUBLIC_HTTP_SMOKE_FAILED:" + str(error)) from error
        try:
            safety = run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                          REPO / "backend/scripts/smoke-safety.ps1", "-BaseUrl", base_url],
                         env=environment, timeout=60, marker="SAFETY_SMOKE_PASS")
        except VerificationFailure as error:
            raise VerificationFailure("PUBLIC_SAFETY_SMOKE_FAILED:" + str(error)) from error
        require(digest(PUBLIC_JAR) == jar_hash, "PUBLIC_CANDIDATE_CHANGED")
        return {"status": "PASS", "jarSha256": jar_hash, "loopbackOnly": True,
                "publicSmokeOutputSha256": hashlib.sha256(smoke).hexdigest(),
                "safetySmokeOutputSha256": hashlib.sha256(safety).hexdigest()}
    finally:
        stop_owned_command(process)
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.settimeout(1)
            require(probe.connect_ex(("127.0.0.1", port)) != 0, "PUBLIC_CANDIDATE_PORT_STILL_BOUND")


def pin_candidate(root, run_id):
    """Use immutable copies for every packaged/probe case, including test-only classes."""
    candidate_root = root / ("candidate-" + run_id)
    candidate_root.mkdir()
    jar_bytes = JAR.read_bytes()
    jar_hash = hashlib.sha256(jar_bytes).hexdigest()
    frozen = candidate_root / "admin-runtime.jar"
    frozen.write_bytes(jar_bytes)
    classes = candidate_root / "classes"
    libraries = candidate_root / "lib"
    probes = candidate_root / "probes"
    for directory in (classes, libraries, probes):
        directory.mkdir()
    with zipfile.ZipFile(frozen) as archive:
        for entry in archive.namelist():
            if entry.endswith("/"):
                continue
            if entry.startswith("BOOT-INF/classes/"):
                relative = entry[len("BOOT-INF/classes/"):]
                require(relative and ".." not in Path(relative).parts
                        and not Path(relative).is_absolute(), "UNSAFE_CANDIDATE_ENTRY")
                destination = classes / relative
                require(destination.resolve().is_relative_to(classes.resolve()),
                        "UNSAFE_CANDIDATE_ENTRY")
                destination.parent.mkdir(parents=True, exist_ok=True)
                content = archive.read(entry)
                require((REPO / "backend/admin-runtime/target/classes" / relative).read_bytes()
                        == content, "CLASSPATH_DIFFERS_FROM_FINAL_JAR")
                destination.write_bytes(content)
            elif entry.startswith("BOOT-INF/lib/") and entry.endswith(".jar"):
                name = Path(entry).name
                content = archive.read(entry)
                destination = libraries / name
                require(not destination.exists(), "DUPLICATE_CANDIDATE_LIBRARY")
                destination.write_bytes(content)
    test_root = REPO / "backend/admin-runtime/target/test-classes"
    probe_hashes = {}
    for path in sorted(test_root.rglob("*.class")):
        require(not path.is_symlink(), "UNSAFE_PROBE_CLASS")
        relative = path.relative_to(test_root)
        destination = probes / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(path.read_bytes())
        probe_hashes[relative.as_posix()] = digest(path)
    for name in ("AdminRuntimeIsolatedProbe.class", "AdminCredentialFixtureHash.class"):
        require(any(p.endswith("/" + name) for p in probe_hashes),
                "PREPARED_PROBE_CLASS_MISSING")
    return {"path": frozen, "sha256": jar_hash, "probeHashes": probe_hashes,
            "classpath": os.pathsep.join([str(probes), str(classes), str(libraries / "*")])}


def assert_candidate_unchanged(candidate):
    require(digest(JAR) == candidate["sha256"]
            and digest(candidate["path"]) == candidate["sha256"], "CANDIDATE_CHANGED")
    test_root = REPO / "backend/admin-runtime/target/test-classes"
    actual = {p.relative_to(test_root).as_posix(): digest(p)
              for p in sorted(test_root.rglob("*.class")) if p.is_file()}
    require(actual == candidate["probeHashes"], "PROBE_CLASSES_CHANGED")


def verification_failure(error):
    # Arbitrary exception text, SQL, environments and arguments never enter evidence.
    return str(error) if isinstance(error, VerificationFailure) else type(error).__name__


def verify(path, with_reactor=False):
    global STEP
    STEP = "load-owned-manifest"
    manifest, meta, passwords = load_manifest(path)
    root = Path(meta["root"])
    # A failed new attempt must not leave a previous PASS available as the latest result.
    for name in ("verification.json", "verification-partial.json"):
        (root / name).unlink(missing_ok=True)
    run_id = uuid.uuid4().hex
    started = time.time()
    results = []
    server = None
    candidate = None
    failure = None
    failed_step = None
    cleanup_failure = None
    reactor = {"requested": with_reactor, "status": "NOT_RUN", "publicNativeSmoke": "NOT_RUN"}
    sources = {}
    try:
        STEP = "prepared-artifact-and-source-preflight"
        require(JAR.is_file(), "PREPARED_RUNTIME_JAR_REQUIRED")
        require((REPO / "backend/admin-migration-cli/target/admin-migration-cli-0.1.0-SNAPSHOT.jar").is_file(),
                "PREPARED_MIGRATION_JAR_REQUIRED")
        sources = source_snapshot()
        STEP = "start-owned-verification-instance"
        server = start_server(meta, passwords["root"])
        STEP = "owned-v3-migration"
        migrate(meta, passwords, SCHEMA)
        migrate(meta, passwords, PRODUCTION_FIXTURE)
        STEP = "owned-auth-event-incremental-grants"
        sql(meta, passwords["root"],
            f"GRANT INSERT ON `{SCHEMA}`.admin_auth_event TO 's1b_writer'@'localhost'; "
            f"GRANT INSERT ON `{SCHEMA}`.admin_auth_event TO 's1b_probe'@'localhost'; "
            f"GRANT INSERT ON `{PRODUCTION_FIXTURE}`.admin_auth_event TO 's1b_prod_writer'@'localhost';")
        if with_reactor:
            STEP = "prepare-owned-public-c2-fixture"
            public_environment = prepare_owned_public_fixture(meta, passwords)
            STEP = "full-reactor-before-candidate-pin"
            reactor["status"] = "RUNNING"
            reactor["outputSha256"] = run_reactor_verification(meta, passwords, public_environment)
            STEP = "owned-public-native-smoke"
            reactor["publicNativeSmoke"] = run_owned_public_smoke(meta, public_environment)
            reactor["status"] = "PASS"
            results.append({"case": "full-reactor-public-db-it-and-module-boundaries", "status": "PASS",
                            "isolatedManagementSchema": SCHEMA, "realMysql": True})
            results.append({"case": "packaged-public-native-and-safety-smoke", "status": "PASS",
                            "loopbackOnly": True, "realMysql": True})
        require(source_snapshot() == sources, "SOURCE_CHANGED_DURING_PREPARATION")
        STEP = "pin-final-candidate"
        candidate = pin_candidate(root, run_id)
        verify_probe_matrix(meta, passwords, server, candidate, results)
        STEP = "final-source-and-candidate-binding"
        assert_candidate_unchanged(candidate)
        require(source_snapshot() == sources, "SOURCE_CHANGED_DURING_VERIFICATION")
    except BaseException as error:
        failure, failed_step = error, STEP
        if reactor["status"] == "RUNNING":
            reactor["status"] = "FAILED"
    finally:
        STEP = "restore-owned-globals-and-shutdown"
        if server is not None:
            try:
                try:
                    if server.poll() is None:
                        sql(meta, passwords["root"],
                            "SET GLOBAL mandatory_roles=''; SET GLOBAL require_secure_transport=ON;")
                finally:
                    stop_server(meta, passwords["root"], server)
            except BaseException as error:
                cleanup_failure = error
    common = {"runId": run_id, "startedAtEpoch": started, "finishedAtEpoch": time.time(),
              "runtimeJarSha256": candidate["sha256"] if candidate else None,
              "probeClassSha256": candidate["probeHashes"] if candidate else {},
              "sourceSha256": sources, "reactor": reactor,
              "shutdown": {"status": ("FAILED" if cleanup_failure else
                                       "COMPLETE" if server is not None else "NO_RETURNED_SERVER_HANDLE"),
                           "serverStarted": server is not None}}
    if failure is not None or cleanup_failure is not None:
        atomic_json(root / "verification-partial.json", {
            **common, "result": "FAILED", "failedStep": failed_step or STEP,
            "failure": verification_failure(failure or cleanup_failure),
            "cleanupFailure": verification_failure(cleanup_failure) if cleanup_failure else None,
            "completedCases": results})
        raise failure if failure is not None else cleanup_failure
    report = {
        **common, "result": "PASS", "scope": "owned-instance runtime/credential probes",
        "instance": {"serverUuid": meta["serverUuid"], "bindAddress": "127.0.0.1",
                     "port": meta["port"], "testOnly": True, "newInstanceStopped": True,
                     "testCaSha256": meta["testCaSha256"]},
        "cases": results, "strictTlsMigrations": 2, "noExistingServerOrGrantsModified": True,
        "c2Acceptance": ("PASS_OWNED_PUBLIC_DB_AND_NATIVE_REGRESSION" if with_reactor
                         and reactor["status"] == "PASS"
                         and reactor["publicNativeSmoke"].get("status") == "PASS"
                         else "NOT_CLOSED_BY_THIS_REPORT"),
        "limitations": [
            "Local disposable test certificates and loopback listener, not production CA/deployment acceptance",
            "Admin runtime remains non-Web; this public-only native smoke is not admin HTTP/CSRF/UI acceptance",
            "Plaintext verifier probe is test-source only; no production bypass"]}
    updated = dict(meta, status="verified-and-cleanly-stopped", latestVerificationRun=run_id)
    atomic_json(manifest, updated)
    atomic_json(root / "verification.json", report)
    print(json.dumps(report, indent=2))


def verify_probe_matrix(meta, passwords, server, candidate, results):
    global STEP
    cp = candidate["classpath"]
    jar = candidate["path"]

    def control(statement):
        require(server.poll() is None, "OWNED_SERVER_GONE")
        return sql(meta, passwords["root"], statement)
    def reset_probe():
        control("SET GLOBAL mandatory_roles=''; SET GLOBAL require_secure_transport=ON;"
                "DROP USER 's1b_probe'@'localhost'; CREATE USER 's1b_probe'@'localhost' IDENTIFIED BY '" + passwords["s1b_probe"] + "' REQUIRE SSL;" + privileges(SCHEMA, "s1b_probe"))
    def check(label, values, reason=None, trusted=True):
        global STEP
        STEP = label
        expected = "ADMIN_RUNTIME_REFUSED:" + reason if reason else "ADMIN_RUNTIME_READY:admin-isolated-v2:NON_WEB"
        output = run(java_options(meta, trusted) + ["-jar", jar, "--check"], env=values, expected=2 if reason else 0)
        require(output.decode().strip() == expected, "UNEXPECTED_RUNTIME_OUTPUT")
        results.append({"case": label, "status": "PASS", "exitCode": 2 if reason else 0, "reason": reason, "realMysql": True})
    check("exact-demo-writer", runtime_env(meta, passwords))
    check("exact-production-mode-on-test-instance", runtime_env(meta, passwords, "s1b_prod_writer", "production"))
    for tls in ["REQUIRED", "VERIFY_CA"]:
        check("explicit-demo-tls-" + tls, runtime_env(meta, passwords, tls=tls))
    check("untrusted-test-certificate", runtime_env(meta, passwords), "DATABASE_CHECK", trusted=False)
    check("certificate-hostname-mismatch", runtime_env(meta, passwords, host="127.0.0.1"), "DATABASE_CHECK")
    values = runtime_env(meta, passwords)
    values["SOUTHLEAF_ADMIN_DB_EXPECTED_USER"] = "s1b_writer@127.0.0.1"
    check("current-user-host-mismatch", values, "IDENTITY")
    values = runtime_env(meta, passwords, "s1b_reader")
    check("public-only-reader-cannot-open-admin-db", values, "DATABASE_CHECK")
    mutations = [
        ("missing-session-update", f"REVOKE UPDATE ON `{SCHEMA}`.admin_session FROM 's1b_probe'@'localhost';", "GRANTS"),
        ("missing-auth-event-insert", f"REVOKE INSERT ON `{SCHEMA}`.admin_auth_event FROM 's1b_probe'@'localhost';", "GRANTS"),
        ("extra-global-select", "GRANT SELECT ON *.* TO 's1b_probe'@'localhost';", "GRANTS"),
        ("extra-schema-select", f"GRANT SELECT ON `{SCHEMA}`.* TO 's1b_probe'@'localhost';", "GRANTS"),
        ("extra-public-schema-select", "GRANT SELECT ON southleaf_public.* TO 's1b_probe'@'localhost';", "GRANTS"),
        ("extra-history-table-select", f"GRANT SELECT ON `{SCHEMA}`.flyway_admin_schema_history TO 's1b_probe'@'localhost';", "GRANTS"),
        ("extra-account-column-update", f"GRANT UPDATE(enabled) ON `{SCHEMA}`.admin_account TO 's1b_probe'@'localhost';", "GRANTS"),
        ("extra-session-delete", f"GRANT DELETE ON `{SCHEMA}`.admin_session TO 's1b_probe'@'localhost';", "GRANTS"),
        ("extra-auth-event-select", f"GRANT SELECT ON `{SCHEMA}`.admin_auth_event TO 's1b_probe'@'localhost';", "GRANTS"),
        ("extra-auth-event-update", f"GRANT UPDATE ON `{SCHEMA}`.admin_auth_event TO 's1b_probe'@'localhost';", "GRANTS"),
        ("extra-auth-event-delete", f"GRANT DELETE ON `{SCHEMA}`.admin_auth_event TO 's1b_probe'@'localhost';", "GRANTS"),
        ("extra-account-table-update", f"GRANT UPDATE ON `{SCHEMA}`.admin_account TO 's1b_probe'@'localhost';", "GRANTS"),
        ("grant-option", f"GRANT SELECT ON `{SCHEMA}`.admin_account TO 's1b_probe'@'localhost' WITH GRANT OPTION;", "GRANTS"),
        ("inactive-role", "GRANT 's1b_role'@'localhost' TO 's1b_probe'@'localhost';", "GRANTS"),
        ("active-default-role", "GRANT 's1b_role'@'localhost' TO 's1b_probe'@'localhost'; SET DEFAULT ROLE 's1b_role'@'localhost' TO 's1b_probe'@'localhost';", "ROLES"),
        ("global-mandatory-role-new-instance-only", "SET GLOBAL mandatory_roles='s1b_role@localhost';", "ROLES")]
    for label, mutation, reason in mutations:
        reset_probe()
        control(mutation)
        check(label, runtime_env(meta, passwords, "s1b_probe"), reason)
    reset_probe()
    STEP = "real-auth-event-shape"
    added_column = False
    try:
        control(f"ALTER TABLE `{SCHEMA}`.admin_auth_event ADD COLUMN unexpected_test_column INT NULL;")
        added_column = True
        check("extra-auth-event-column-real-mysql", runtime_env(meta, passwords), "DATABASE_CHECK")
    finally:
        if added_column:
            control(f"ALTER TABLE `{SCHEMA}`.admin_auth_event DROP COLUMN unexpected_test_column;")
    check("auth-event-shape-restored", runtime_env(meta, passwords))
    STEP = "real-cross-schema-denials"
    for user, statement, label in [
        ("s1b_writer", "SELECT 1 FROM southleaf_public.s1b_canary LIMIT 0;", "writer-cannot-read-public-canary"),
        ("s1b_reader", f"SELECT 1 FROM `{SCHEMA}`.admin_account LIMIT 0;", "reader-cannot-read-admin"),
        ("s1b_writer", f"UPDATE `{SCHEMA}`.admin_account SET enabled=enabled WHERE 1=0;", "writer-cannot-update-account"),
        ("s1b_writer", f"SELECT 1 FROM `{SCHEMA}`.admin_security_audit LIMIT 0;", "writer-cannot-read-audit"),
        ("s1b_writer", f"SELECT 1 FROM `{SCHEMA}`.admin_auth_event LIMIT 0;", "writer-cannot-read-auth-events"),
        ("s1b_writer", f"UPDATE `{SCHEMA}`.admin_auth_event SET event_type=event_type WHERE 1=0;", "writer-cannot-update-auth-events"),
        ("s1b_writer", f"DELETE FROM `{SCHEMA}`.admin_auth_event WHERE 1=0;", "writer-cannot-delete-auth-events"),
        ("s1b_writer", f"SELECT 1 FROM `{SCHEMA}`.flyway_admin_schema_history LIMIT 0;", "writer-cannot-read-migration-history")]:
        output = sql(meta, passwords[user], statement, user=user, tcp=True, expected=1)
        require(b"ERROR 1142" in output, "EXPECTED_TABLE_PERMISSION_DENIAL")
        results.append({"case": label, "status": "PASS", "mysqlErrorCode": 1142, "realMysql": True})
    STEP = "real-jdbc-credential-login"
    login_account="s1c2_login_"+uuid.uuid4().hex[:16]
    login_password=secrets.token_urlsafe(28)
    success_request,denied_request,absent_request=[str(uuid.uuid4()) for _ in range(3)]
    hasher_output=run(java_options(meta)+["-cp",cp,"cn.zhinan.adminruntime.AdminCredentialFixtureHash"],
        env={"SOUTHLEAF_TEST_LOGIN_PASSWORD":login_password},timeout=45)
    credential_hash=hasher_output.decode("ascii").strip()
    require(credential_hash.startswith("$argon2id$v=19$m=65536,t=3,p=1$"),"SYNTHETIC_FIXTURE_HASH_INVALID")
    control(f"INSERT INTO `{SCHEMA}`.admin_account(account_id,enabled,security_version,credential_hash) VALUES('{login_account}',1,1,'{credential_hash}'); INSERT INTO `{SCHEMA}`.admin_account_permission(account_id,permission_code) VALUES('{login_account}','CONTENT_READ');")
    auth_before=int(control(f"SELECT COUNT(*) FROM `{SCHEMA}`.admin_auth_event;").strip())
    probe_env=runtime_env(meta,passwords)
    probe_env.update({"SOUTHLEAF_TEST_LOGIN_ACCOUNT":login_account,"SOUTHLEAF_TEST_LOGIN_PASSWORD":login_password,
        "SOUTHLEAF_TEST_LOGIN_SUCCESS_REQUEST":success_request,"SOUTHLEAF_TEST_LOGIN_DENIED_REQUEST":denied_request,
        "SOUTHLEAF_TEST_LOGIN_ABSENT_REQUEST":absent_request})
    run(java_options(meta)+["-cp",cp,"cn.zhinan.adminruntime.AdminRuntimeIsolatedProbe","credential-login"],
        env=probe_env,marker="ADMIN_ISOLATED_CREDENTIAL_LOGIN_PASS",timeout=60)
    auth_after=int(control(f"SELECT COUNT(*) FROM `{SCHEMA}`.admin_auth_event;").strip())
    require(auth_after==auth_before+2,"AUTHENTICATION_DENIAL_EVENTS_NOT_DURABLE")
    require(int(control(f"SELECT COUNT(*) FROM `{SCHEMA}`.admin_auth_event WHERE actor_account_id='{login_account}' AND event_type='LOGIN_DENIED' AND request_id='{denied_request}';").strip())==1,"KNOWN_DENIAL_EVENT_MISSING")
    require(int(control(f"SELECT COUNT(*) FROM `{SCHEMA}`.admin_auth_event WHERE actor_account_id IS NULL AND event_type='LOGIN_DENIED' AND request_id='{absent_request}';").strip())==1,"ABSENT_DENIAL_EVENT_MISSING_OR_ENUMERATED")
    require(int(control(f"SELECT COUNT(*) FROM `{SCHEMA}`.admin_security_audit WHERE actor_account_id='{login_account}' AND action IN ('ADMIN_SESSION_OPENED','ADMIN_SESSION_REVOKED');").strip())==2,"REAL_LOGIN_SESSION_AUDIT_MISSING")
    control(f"DELETE FROM `{SCHEMA}`.admin_auth_event WHERE request_id IN ('{denied_request}','{absent_request}'); DELETE FROM `{SCHEMA}`.admin_security_audit WHERE actor_account_id='{login_account}'; DELETE FROM `{SCHEMA}`.admin_session WHERE account_id='{login_account}'; DELETE FROM `{SCHEMA}`.admin_account_permission WHERE account_id='{login_account}'; DELETE FROM `{SCHEMA}`.admin_account WHERE account_id='{login_account}';")
    login_password="\0"*len(login_password)
    credential_hash="\0"*len(credential_hash)
    results.append({"case":"real-jdbc-argon2-login-wrong-and-absent-with-durable-audit","status":"PASS","secretValuesLogged":False,"realMysql":True})
    STEP = "real-restricted-store-operations"
    before = int(control(f"SELECT COUNT(*) FROM `{SCHEMA}`.admin_security_audit WHERE actor_account_id='s1b_fixture_account';").strip())
    run(java_options(meta) + ["-cp", cp, "cn.zhinan.adminruntime.AdminRuntimeIsolatedProbe", "store"],
        env=runtime_env(meta, passwords), marker="ADMIN_ISOLATED_STORE_PASS")
    after = int(control(f"SELECT COUNT(*) FROM `{SCHEMA}`.admin_security_audit WHERE actor_account_id='s1b_fixture_account';").strip())
    require(after == before + 2, "ISSUE_REVOKE_AUDIT_COUNT_WRONG")
    results.append({"case": "same-verified-pool-real-store-issue-read-revoke-idempotency-audit", "status": "PASS", "auditRowsAdded": 2, "realMysql": True})
    STEP = "real-unencrypted-connection-verifier"
    control("SET GLOBAL require_secure_transport=OFF; ALTER USER 's1b_probe'@'localhost' REQUIRE NONE;")
    run(java_options(meta) + ["-cp", cp, "cn.zhinan.adminruntime.AdminRuntimeIsolatedProbe", "unencrypted-connection"],
        env=runtime_env(meta, passwords, "s1b_probe"), marker="ADMIN_ISOLATED_PLAINTEXT_REJECTED")
    results.append({"case": "actual-empty-tls-cipher-rejected-via-test-source-factory", "status": "PASS", "realMysql": True, "productionBypassAdded": False})
    reset_probe()
    STEP = "default-runtime-lifecycle"
    startup = subprocess.STARTUPINFO()
    startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    startup.wShowWindow = 0
    process = subprocess.Popen([str(x) for x in java_options(meta) + ["-jar", jar]], env=clean_env(runtime_env(meta, passwords)),
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                               creationflags=subprocess.CREATE_NEW_CONSOLE, startupinfo=startup)
    try:
        ready_line = queue.Queue()
        reader = threading.Thread(target=lambda: ready_line.put(process.stdout.readline()), daemon=True)
        reader.start()
        first_line = b""
        deadline = time.monotonic() + 25
        ready = False
        while time.monotonic() < deadline:
            require(process.poll() is None, "DEFAULT_RUNTIME_EXITED_BEFORE_READY")
            require(not listeners(process.pid), "DEFAULT_RUNTIME_OPENED_LISTENER")
            try:
                first_line = ready_line.get(timeout=0.1)
                require(first_line.strip() == b"ADMIN_RUNTIME_READY:admin-isolated-v2:NON_WEB", "DEFAULT_RUNTIME_READY_MARKER_MISSING")
                time.sleep(1)
                require(process.poll() is None and not listeners(process.pid), "DEFAULT_RUNTIME_NOT_STABLE_NON_WEB")
                ready = True
                break
            except queue.Empty:
                pass
        require(ready, "DEFAULT_RUNTIME_NOT_READY")
        ctrl_c_private_console(process)
        output, _ = process.communicate(timeout=20)
        require(process.returncode in (0, 130), "DEFAULT_RUNTIME_NOT_GRACEFULLY_STOPPED")
        require((first_line + output).decode().strip() == "ADMIN_RUNTIME_READY:admin-isolated-v2:NON_WEB", "DEFAULT_RUNTIME_UNEXPECTED_OUTPUT")
        require(int(control("SELECT COUNT(*) FROM information_schema.processlist WHERE USER='s1b_writer';").strip()) == 0, "WRITER_CONNECTIONS_LEFT_AFTER_SHUTDOWN")
        results.append({"case": "default-non-web-runtime-and-private-console-graceful-shutdown", "status": "PASS", "exitCode": process.returncode, "remainingWriterConnections": 0, "realMysql": True})
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=10)
    check("exact-writer-after-negative-fixture-restoration", runtime_env(meta, passwords))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["prepare", "verify"])
    parser.add_argument("--confirm-isolated-provisioning", action="store_true")
    parser.add_argument("--manifest")
    parser.add_argument("--with-reactor", action="store_true",
                        help="Run full reactor with owned management IT before pinning the candidate")
    arguments = parser.parse_args()
    try:
        require(os.name == "nt", "WINDOWS_REQUIRED")
        require(not arguments.with_reactor or arguments.action == "verify", "REACTOR_VERIFY_ONLY")
        if arguments.action == "prepare":
            require(arguments.confirm_isolated_provisioning, "EXPLICIT_PROVISIONING_CONFIRMATION_REQUIRED")
            prepare()
        else:
            require(arguments.manifest, "EXPLICIT_OWNED_MANIFEST_REQUIRED")
            verify(arguments.manifest, with_reactor=arguments.with_reactor)
    except (VerificationFailure, subprocess.TimeoutExpired, OSError, ValueError) as failure:
        message = str(failure) if isinstance(failure, VerificationFailure) else type(failure).__name__
        print("S1B_ISOLATED_FAILED:" + STEP + ":" + message, file=sys.stderr)
        sys.exit(2)

