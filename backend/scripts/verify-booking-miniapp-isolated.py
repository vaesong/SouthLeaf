#!/usr/bin/env python3
"""小程序预约入口的一次性隔离 L5：真实 MySQL、真实 public-api、真实微信开发者工具。

与 S6 runner 一样，夹具完全自制、可销毁：独立 loopback MySQL、独立 schema、独立账号与精确授权、
独立 HMAC 会话密钥；结束时停进程并删除本 run 的 datadir。判定只看数据库事实与小程序页面真实渲染出的
状态，不以 mock UI 代替链路。
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
import re
import secrets
import shutil
import subprocess
import threading
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

REPO = Path(__file__).resolve().parents[2]
BACKEND = REPO / "backend"
FRONTEND = REPO / "frontend"
HELPER_PATH = BACKEND / "scripts" / "verify-s1d-admin-web-isolated.py"
PUBLIC_JAR = BACKEND / "public-api" / "target" / "uniapp-api-0.1.0-SNAPSHOT.jar"
MINIAPP_TEST = FRONTEND / "e2e" / "booking-miniapp.mjs"
MINIAPP_DIST = FRONTEND / "dist" / "build" / "mp-weixin"
DEVTOOLS_CLI = Path(r"D:\SoftWare\WechatDev\微信web开发者工具\wechatidecli.cmd")
AUTOMATOR = REPO / ".trellis" / ".runtime" / "wechat-automation" / "node_modules" / "miniprogram-automator"
FIXTURE_KIND = "BOOKING_MINIAPP_E2E"
PACKAGE_REFS = "demo-package-family=家庭写真体验,demo-package-portrait=个人形象照"


def load_helpers():
    spec = importlib.util.spec_from_file_location("s1d_isolated_helpers", HELPER_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("HELPERS_UNAVAILABLE")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def verify_manifest(meta: dict[str, object], helpers) -> bool:
    try:
        root = Path(str(meta["root"]))
        disk = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
        return (
            disk == meta
            and disk.get("kind") == FIXTURE_KIND
            and disk.get("runId") == root.name
            and root.parent.resolve().name == "BookingMiniappVerification"
            and root.resolve().parent == root.parent.resolve()
            and disk.get("schema") == "southleaf_admin_miniapp" + str(disk.get("runId"))
            and disk.get("publicSchema") == "southleaf_public_miniapp" + str(disk.get("runId"))
            and disk.get("datadir") == str((root / "mysql-data").resolve())
            and disk.get("host") == "127.0.0.1"
            and disk.get("mysqlExecutable") == str(helpers.MYSQLD.resolve())
            and disk.get("mysqlStartMode") == "no-monitor"
        )
    except (OSError, ValueError, TypeError):
        return False


def save_manifest(meta: dict[str, object]) -> None:
    root = Path(str(meta["root"]))
    temporary = root / "manifest.tmp"
    temporary.write_text(json.dumps(meta, sort_keys=True, indent=2), encoding="utf-8")
    temporary.replace(root / "manifest.json")


def require(condition: bool, category: str) -> None:
    if not condition:
        raise RuntimeError(category)


def execute_sql(helpers, state: dict[str, object], sql: str) -> str:
    result = helpers.mysql_query_raw(state, sql, 30)
    if result is None or result.returncode != 0:
        detail = "" if result is None else result.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError("MINIAPP_SQL_FAILED:" + detail.replace("\n", " ")[:300])
    return result.stdout.decode("utf-8", errors="replace").strip()


def check_build_inputs() -> dict[str, Path]:
    migration = BACKEND / "admin-migration-cli"
    build = {
        "migrationClasses": migration / "target" / "classes",
        "migrationDeps": migration / "target" / "dependency",
    }
    require(PUBLIC_JAR.is_file(), "PUBLIC_JAR_REQUIRED")
    migrations = build["migrationClasses"] / "db" / "admin-migration"
    require(migrations.is_dir(), "MIGRATION_CLASSES_REQUIRED")
    # The intake preference table arrives with V11; a stale resource copy would silently skip it.
    require((migrations / "V11__admin_booking_request_preference.sql").is_file(), "MIGRATION_CLASSES_STALE")
    require(any(build["migrationDeps"].glob("*.jar")), "MIGRATION_DEPENDENCIES_REQUIRED")
    require(MINIAPP_TEST.is_file(), "MINIAPP_TEST_REQUIRED")
    require(AUTOMATOR.is_dir(), "MINIAPP_AUTOMATOR_REQUIRED")
    require(DEVTOOLS_CLI.is_file(), "WECHAT_DEVTOOLS_REQUIRED")
    public_sources = [p for p in (BACKEND / "public-api" / "src" / "main").rglob("*") if p.is_file()]
    require(
        public_sources and max(p.stat().st_mtime for p in public_sources) <= PUBLIC_JAR.stat().st_mtime,
        "STALE_PUBLIC_BUILD",
    )
    frontend_sources = [
        p for p in (FRONTEND / "src" if (FRONTEND / "src").is_dir() else FRONTEND).rglob("*") if p.is_file()
    ]
    frontend_sources = [
        p for p in frontend_sources if "node_modules" not in p.parts and "dist" not in p.parts
    ]
    require(bool(frontend_sources), "FRONTEND_SOURCES_MISSING")
    return build


def run_java(tools: dict[str, Path], args: list[str], env: dict[str, str], timeout: int, category: str) -> str:
    result = subprocess.run(
        [str(tools["java"]), *args],
        cwd=str(BACKEND),
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=timeout,
        check=False,
    )
    output = result.stdout.decode("utf-8", errors="replace")
    if result.returncode != 0:
        print(f"MINIAPP_JAVA_EXIT:{category}:{result.returncode}")
        print(output[-6000:])
        raise RuntimeError(category)
    return output


def create_schemas(helpers, state: dict[str, object], passwords: dict[str, str]) -> None:
    meta = state["meta"]
    admin = str(meta["schema"])
    public = str(meta["publicSchema"])
    statements = [
        f"CREATE DATABASE `{admin}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;",
        f"CREATE DATABASE `{public}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;",
        "CREATE USER 's1d_migrator'@'localhost' IDENTIFIED BY "
        + helpers.sql_quote(passwords["migrator"])
        + ";",
        f"GRANT ALL PRIVILEGES ON `{admin}`.* TO 's1d_migrator'@'localhost';",
        "CREATE USER 'mmp_migrator'@'localhost' IDENTIFIED BY "
        + helpers.sql_quote(passwords["publicMigrator"])
        + ";",
        f"GRANT ALL PRIVILEGES ON `{public}`.* TO 'mmp_migrator'@'localhost';",
    ]
    execute_sql(helpers, state, "\n".join(statements))
    meta["status"] = "disposable-schemas-and-migrators-created"
    save_manifest(meta)


def run_migrations(helpers, state, tools, build, passwords) -> None:
    """Admin migrations come from the proven S1-D helper; the public schema is migrated here."""
    meta = state["meta"]
    helpers.run_migrations(state, tools, build, passwords)
    port = int(meta["mysqlPort"])
    public_env = helpers.java_options_env(
        state["tls"],
        {
            "SOUTHLEAF_MIGRATION_URL": helpers.jdbc_url(port, str(meta["publicSchema"])),
            "SOUTHLEAF_MIGRATION_USERNAME": "mmp_migrator",
            "SOUTHLEAF_MIGRATION_PASSWORD": passwords["publicMigrator"],
            "SOUTHLEAF_MEDIA_ROOT": str(Path(str(meta["root"])) / "media"),
        },
    )
    output = run_java(
        tools,
        [
            "-cp",
            str(PUBLIC_JAR),
            "-Dloader.main=cn.zhinan.uniapp.tooling.DatabaseMigrationMain",
            "org.springframework.boot.loader.launch.PropertiesLauncher",
            "--seed-demo",
        ],
        public_env,
        180,
        "PUBLIC_MIGRATION_FAILED",
    )
    require("EXPLICIT_DEMO_IMPORT_PASS" in output, "PUBLIC_SEED_MARKER_MISSING")
    booking_tables = execute_sql(
        helpers,
        state,
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema="
        + helpers.sql_quote(str(meta["schema"]))
        + " AND table_name IN ('booking_reservation','booking_request_preference');",
    )
    require(booking_tables.strip().splitlines()[-1] == "2", "BOOKING_TABLES_MISSING")
    meta["status"] = "admin-and-public-migrations-passed"
    save_manifest(meta)


def create_scoped_users(helpers, state: dict[str, object], passwords: dict[str, str]) -> None:
    """The intake identity gets exactly the grants its startup policy requires, nothing more."""
    meta = state["meta"]
    admin = str(meta["schema"])
    public = str(meta["publicSchema"])
    writer = "southleaf_public_booking_writer"
    grants = {
        "booking_customer": "INSERT, SELECT",
        "booking_external_identity": "INSERT, SELECT",
        "booking_reservation": "INSERT, SELECT",
        "booking_request_preference": "INSERT, SELECT",
        "booking_command": "INSERT, SELECT",
        "booking_audit": "INSERT",
        "booking_notification_outbox": "INSERT",
        "booking_service_slot": "SELECT",
        "booking_slot_occupancy": "SELECT",
        "booking_order": "SELECT",
        "booking_order_snapshot": "SELECT",
    }
    statements = [
        f"CREATE USER '{writer}'@'localhost' IDENTIFIED BY {helpers.sql_quote(passwords['writer'])};",
        f"CREATE USER 'mmp_reader'@'localhost' IDENTIFIED BY {helpers.sql_quote(passwords['reader'])};",
        f"GRANT SELECT ON `{public}`.* TO 'mmp_reader'@'localhost';",
    ]
    for table, privileges in grants.items():
        statements.append(f"GRANT {privileges} ON `{admin}`.`{table}` TO '{writer}'@'localhost';")
    execute_sql(helpers, state, "\n".join(statements))
    meta["status"] = "intake-writer-and-public-reader-grants-created"
    save_manifest(meta)


def seed_slots(helpers, state: dict[str, object], count: int = 4) -> list[str]:
    """Opens a small window of bookable slots; nothing is occupied, so the intake may claim one."""
    schema = str(state["meta"]["schema"])
    q = helpers.sql_quote
    created = datetime.now(timezone.utc).replace(tzinfo=None)
    created_text = created.strftime("%Y-%m-%d %H:%M:%S.%f")
    slots: list[str] = []
    statements = []
    for index in range(count):
        slot_id = str(uuid.uuid4())
        start = created + timedelta(days=2 + index, hours=1)
        end = start + timedelta(hours=1)
        slots.append(slot_id)
        statements.append(
            f"INSERT INTO `{schema}`.booking_service_slot "
            "(id,start_at,end_at,business_zone,display_offset,status,capacity,rule_version,revision,"
            "created_at,updated_at) VALUES "
            f"({q(slot_id)},{q(start.strftime('%Y-%m-%d %H:%M:%S.%f'))},"
            f"{q(end.strftime('%Y-%m-%d %H:%M:%S.%f'))},'Asia/Tokyo','+09:00','OPEN',1,"
            f"'miniapp-isolated-fixture-v1',1,{q(created_text)},{q(created_text)});"
        )
    execute_sql(helpers, state, "\n".join(statements))
    return slots


def start_public_api(helpers, state, tools, passwords) -> None:
    meta = state["meta"]
    workdir = Path(str(meta["root"])) / "public-runtime"
    workdir.mkdir()
    env = helpers.java_options_env(
        state["tls"],
        {
            "SOUTHLEAF_DB_URL": helpers.jdbc_url(int(meta["mysqlPort"]), str(meta["publicSchema"])),
            "SOUTHLEAF_DB_USERNAME": "mmp_reader",
            "SOUTHLEAF_DB_PASSWORD": passwords["reader"],
            "SOUTHLEAF_MEDIA_ROOT": str(Path(str(meta["root"])) / "media"),
            "SOUTHLEAF_BIND_ADDRESS": "127.0.0.1",
            "PORT": str(meta["webPort"]),
            "SOUTHLEAF_PUBLIC_BOOKING_MODE": "enabled",
            "SOUTHLEAF_PUBLIC_BOOKING_DB_URL": helpers.jdbc_url(
                int(meta["mysqlPort"]), str(meta["schema"])
            ),
            "SOUTHLEAF_PUBLIC_BOOKING_DB_USERNAME": "southleaf_public_booking_writer",
            "SOUTHLEAF_PUBLIC_BOOKING_DB_PASSWORD": passwords["writer"],
            "SOUTHLEAF_PUBLIC_BOOKING_DB_EXPECTED_USER": "southleaf_public_booking_writer@localhost",
            "SOUTHLEAF_PUBLIC_BOOKING_DATA_MODE": "SYNTHETIC",
            "SOUTHLEAF_PUBLIC_BOOKING_IDENTITY_SINK": "TEST",
            "SOUTHLEAF_PUBLIC_BOOKING_APP_SCOPE": "miniapp-isolated-" + str(meta["runId"]),
            "SOUTHLEAF_PUBLIC_BOOKING_SESSION_SECRET": state["sessionSecret"],
            "SOUTHLEAF_PUBLIC_BOOKING_SESSION_MINUTES": "30",
            "SOUTHLEAF_PUBLIC_BOOKING_REQUEST_HOURS": "48",
            "SOUTHLEAF_PUBLIC_BOOKING_BUSINESS_ZONE": "Asia/Tokyo",
            "SOUTHLEAF_PUBLIC_BOOKING_PACKAGE_REFS": PACKAGE_REFS,
        },
    )
    startup = subprocess.STARTUPINFO()
    startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    startup.wShowWindow = 0
    process = subprocess.Popen(
        [str(tools["java"]), "-jar", str(PUBLIC_JAR)],
        cwd=str(workdir),
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        creationflags=subprocess.CREATE_NEW_CONSOLE,
        startupinfo=startup,
    )
    state["runtimeProcess"] = process
    meta["runtimePid"] = process.pid
    meta["status"] = "public-api-starting"
    save_manifest(meta)
    collected: list[str] = []

    def drain() -> None:
        if process.stdout is None:
            return
        for raw in iter(process.stdout.readline, b""):
            collected.append(raw.decode("utf-8", errors="replace").rstrip())
            if len(collected) > 400:
                del collected[:200]

    threading.Thread(target=drain, daemon=True).start()
    last = ""
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if process.poll() is not None:
            detail = b""
            try:
                detail = process.stdout.read() or b""
            except Exception:
                detail = b""
            raise RuntimeError("PUBLIC_API_EXITED:" + detail.decode("utf-8", "replace")[-300:])
        try:
            status, body = public_get(state, "/api/v1/public/booking/packages")
            last = f"{status}:{body[:300]}"
        except (URLError, HTTPError, OSError) as failure:
            status = 0
            last = type(failure).__name__
        if status == 200:
            listeners = helpers.netstat_listeners(process.pid)
            require(len(listeners) == 1 and helpers.is_loopback_listener(listeners[0], int(meta["webPort"])),
                    "PUBLIC_LISTENER_SCOPE_INVALID")
            meta["status"] = "public-api-ready"
            save_manifest(meta)
            return
        time.sleep(0.5)
    print("MINIAPP_PUBLIC_API_TAIL:" + " | ".join(collected[-12:])[:1500])
    print("MINIAPP_PUBLIC_API_LAST_PROBE:" + last)
    raise RuntimeError("PUBLIC_API_READY_TIMEOUT")


def public_get(state: dict[str, object], path: str, token: str = "") -> tuple[int, str]:
    request = Request(
        f"http://127.0.0.1:{state['meta']['webPort']}{path}",
        headers={"Accept": "application/json", **({"Authorization": "Bearer " + token} if token else {})},
    )
    try:
        with urlopen(request, timeout=10) as response:
            return response.status, response.read().decode("utf-8", errors="replace")
    except HTTPError as failure:
        return failure.code, failure.read().decode("utf-8", errors="replace")


def public_post(state: dict[str, object], path: str, payload: dict[str, object]) -> tuple[int, str]:
    request = Request(
        f"http://127.0.0.1:{state['meta']['webPort']}{path}",
        data=json.dumps(payload).encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json"},
    )
    try:
        with urlopen(request, timeout=15) as response:
            return response.status, response.read().decode("utf-8", errors="replace")
    except HTTPError as failure:
        return failure.code, failure.read().decode("utf-8", errors="replace")


def create_session(state: dict[str, object], run_id: str) -> str:
    status, body = public_post(
        state, "/api/v1/public/booking/session", {"loginCode": "test-miniapp-" + run_id}
    )
    require(status == 200, "SESSION_REFUSED_" + str(status))
    try:
        token = json.loads(body)["data"]["token"]
    except (ValueError, KeyError, TypeError):
        raise RuntimeError("SESSION_TOKEN_MISSING")
    require(isinstance(token, str) and len(token) > 20, "SESSION_TOKEN_INVALID")
    return token


def probe_intake(helpers, state, slot_id: str) -> None:
    """Calls the intake endpoint directly once, so a client failure can be told apart from an API one."""
    status, body = public_post(
        state, "/api/v1/public/booking/session", {"loginCode": "test-probe-" + str(state["meta"]["runId"])}
    )
    require(status == 200, "PROBE_SESSION_REFUSED_" + str(status))
    token = json.loads(body)["data"]["token"]
    request = Request(
        f"http://127.0.0.1:{state['meta']['webPort']}/api/v1/public/booking/requests",
        data=json.dumps({"slotId": slot_id, "packageVersionRef": "demo-package-family"}).encode("utf-8"),
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "Authorization": "Bearer " + token,
            "Idempotency-Key": "probe-" + str(state["meta"]["runId"]) + "-key",
        },
    )
    try:
        with urlopen(request, timeout=15) as response:
            code, text = response.status, response.read().decode("utf-8", errors="replace")
    except HTTPError as failure:
        code, text = failure.code, failure.read().decode("utf-8", errors="replace")
    print(f"MINIAPP_INTAKE_PROBE:{code}:{text[:400]}")
    if code in (200, 201):
        reservation = json.loads(text)["data"]["reservationId"]
        schema = str(state["meta"]["schema"])
        q = helpers.sql_quote
        execute_sql(
            helpers,
            state,
            "\n".join(
                [
                    f"DELETE FROM `{schema}`.booking_notification_outbox WHERE object_ref={q(reservation)};",
                    f"DELETE FROM `{schema}`.booking_audit WHERE object_id={q(reservation)};",
                    f"DELETE FROM `{schema}`.booking_request_preference WHERE reservation_id={q(reservation)};",
                    f"DELETE FROM `{schema}`.booking_reservation WHERE id={q(reservation)};",
                ]
            ),
        )


def build_miniapp(helpers, state, tools) -> None:
    env = helpers.child_env({"SOUTHLEAF_API_ORIGIN": f"http://127.0.0.1:{state['meta']['webPort']}"})
    result = subprocess.run(
        [str(tools["node"]), "scripts/build-native.mjs", "demo"],
        cwd=str(FRONTEND),
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=180,
        check=False,
    )
    output = result.stdout.decode("utf-8", errors="replace")
    require(result.returncode == 0 and "NATIVE_BUILD_PASS" in output, "MINIAPP_BUILD_FAILED")
    require((MINIAPP_DIST / "pages" / "booking" / "booking.js").is_file(), "MINIAPP_BOOKING_PAGE_MISSING")
    print("MINIAPP_BUILD:" + [line for line in output.splitlines() if "NATIVE_BUILD_PASS" in line][0])
    state["meta"]["status"] = "miniapp-built-for-isolated-api"
    save_manifest(state["meta"])


def ensure_devtools(helpers, state) -> str:
    """A stale IDE keeps the previous bundle, so an already-open project is restarted."""
    port = int(state["meta"]["autoPort"])
    if helpers.port_owner(port):
        for image in ("微信开发者工具.exe", "wechatide.exe", "wechatidecli.exe"):
            subprocess.run(
                ["taskkill", "/F", "/IM", image],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline and helpers.port_owner(port):
            time.sleep(1)
        require(not helpers.port_owner(port), "DEVTOOLS_STALE_INSTANCE")
        state["devtoolsMode"] = "restarted"
    launch = subprocess.run(
        [
            "powershell",
            "-NoProfile",
            "-Command",
            "Start-Process -FilePath '"
            + str(DEVTOOLS_CLI)
            + "' -ArgumentList 'auto --project "
            + str(MINIAPP_DIST)
            + " --auto-port "
            + str(port)
            + "' -WindowStyle Hidden",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=60,
        check=False,
    )
    require(launch.returncode == 0, "DEVTOOLS_LAUNCH_FAILED")
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        if helpers.port_owner(port):
            state["devtoolsMode"] = "started"
            return "started"
        time.sleep(1)
    raise RuntimeError("DEVTOOLS_AUTOMATION_TIMEOUT")


def run_miniapp(helpers, state, tools, phase: str, token: str, reservation: str = "") -> dict[str, object]:
    env = helpers.child_env(
        {
            "MINIAPP_PHASE": phase,
            "MINIAPP_WS": f"ws://127.0.0.1:{state['meta']['autoPort']}",
            "MINIAPP_SESSION_TOKEN": token,
            "MINIAPP_RESERVATION_ID": reservation,
            "MINIAPP_SHOT_DIR": str(Path(str(state["meta"]["root"])) / "screens"),
            "SOUTHLEAF_AUTOMATOR_PATH": str(AUTOMATOR),
        }
    )
    result = subprocess.run(
        [str(tools["node"]), str(MINIAPP_TEST)],
        cwd=str(FRONTEND),
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=180,
        check=False,
    )
    output = result.stdout.decode("utf-8", errors="replace")
    marker = f"BOOKING_MINIAPP_{phase.upper()}:"
    if result.returncode != 0 or marker not in output:
        dump = Path(os.environ.get("LOCALAPPDATA", ".")) / "SouthLeaf" / "miniapp-e2e.log"
        dump.parent.mkdir(parents=True, exist_ok=True)
        dump.write_text(output, encoding="utf-8")
        print("MINIAPP_E2E_LOG:" + str(dump))
        print(output[-4000:])
        raise RuntimeError("MINIAPP_E2E_FAILED_" + phase.upper())
    print([line for line in output.splitlines() if marker in line][0][:400])
    return json.loads(output.split(marker, 1)[1].splitlines()[0])


def verify_pending_facts(helpers, state, reservation: str, slot_id: str) -> None:
    schema = str(state["meta"]["schema"])
    q = helpers.sql_quote
    status = execute_sql(
        helpers, state, f"SELECT status FROM `{schema}`.booking_reservation WHERE id={q(reservation)};"
    ).strip().splitlines()[-1]
    require(status == "PENDING_CONFIRMATION", "REQUEST_NOT_PENDING")
    preference = execute_sql(
        helpers,
        state,
        f"SELECT slot_id FROM `{schema}`.booking_request_preference WHERE reservation_id={q(reservation)};",
    ).strip().splitlines()[-1]
    require(preference == slot_id, "REQUEST_PREFERENCE_MISMATCH")
    occupancy = execute_sql(
        helpers, state, f"SELECT COUNT(*) FROM `{schema}`.booking_slot_occupancy WHERE slot_id={q(slot_id)};"
    ).strip().splitlines()[-1]
    require(occupancy == "0", "REQUEST_OCCUPIED_A_SLOT")
    audit = execute_sql(
        helpers,
        state,
        f"SELECT COUNT(*) FROM `{schema}`.booking_audit WHERE object_id={q(reservation)};",
    ).strip().splitlines()[-1]
    require(int(audit) >= 1, "REQUEST_AUDIT_MISSING")
    outbox = execute_sql(
        helpers,
        state,
        f"SELECT COUNT(*) FROM `{schema}`.booking_notification_outbox WHERE object_ref={q(reservation)};",
    ).strip().splitlines()[-1]
    require(outbox == "1", "REQUEST_OUTBOX_MISSING")


def promote_to_confirmed(helpers, state, reservation: str, slot_id: str) -> dict[str, str]:
    """The operator decision from Q02, applied as one transaction so the intake view can show it."""
    schema = str(state["meta"]["schema"])
    q = helpers.sql_quote
    row = execute_sql(
        helpers,
        state,
        "SELECT r.customer_id, s.start_at, s.end_at FROM `%s`.booking_reservation r"
        " JOIN `%s`.booking_service_slot s ON s.id=%s WHERE r.id=%s;" % (schema, schema, q(slot_id), q(reservation)),
    ).strip().splitlines()[-1].split("\t")
    customer, start_at, end_at = row[0], row[1], row[2]
    order_id, snapshot_id = str(uuid.uuid4()), str(uuid.uuid4())
    now = datetime.now(timezone.utc).replace(tzinfo=None).strftime("%Y-%m-%d %H:%M:%S.%f")
    statements = [
        f"UPDATE `{schema}`.booking_reservation SET status='CONFIRMED', slot_id={q(slot_id)},"
        f" schedule_revision=1, confirmed_at={q(now)}, confirmed_by='miniapp-fixture-operator',"
        f" updated_at={q(now)}, version=version+1 WHERE id={q(reservation)} AND status='PENDING_CONFIRMATION';",
        f"INSERT INTO `{schema}`.booking_slot_occupancy (slot_id,reservation_id,assigned_at,revision)"
        f" VALUES ({q(slot_id)},{q(reservation)},{q(now)},1);",
        f"INSERT INTO `{schema}`.booking_slot_assignment_history"
        " (id,slot_id,reservation_id,previous_slot_id,assigned_at,reason_code,revision) VALUES"
        f" ({q(str(uuid.uuid4()))},{q(slot_id)},{q(reservation)},NULL,{q(now)},'MINIAPP_E2E_CONFIRM',1);",
        f"INSERT INTO `{schema}`.booking_order"
        " (id,source_reservation_id,customer_id,lifecycle_status,fulfilment_status,snapshot_id,"
        "current_revision,created_at,updated_at,version) VALUES"
        f" ({q(order_id)},{q(reservation)},{q(customer)},'ACTIVE','UNSCHEDULED',{q(snapshot_id)},1,"
        f"{q(now)},{q(now)},1);",
        f"INSERT INTO `{schema}`.booking_order_snapshot"
        " (id,order_id,package_version_ref,package_display_name,currency_code,amount_minor_units,"
        "price_version,slot_id,start_at,end_at,business_zone,display_offset,schedule_revision,"
        "customer_id,source,captured_at) VALUES"
        f" ({q(snapshot_id)},{q(order_id)},'demo-package-family','家庭写真体验','JPY',38000,"
        f"'miniapp-isolated-price-v1',{q(slot_id)},{q(start_at)},{q(end_at)},'Asia/Tokyo','+09:00',1,"
        f"{q(customer)},'OFFLINE',{q(now)});",
    ]
    execute_sql(helpers, state, "\n".join(statements))
    return {"orderId": order_id, "snapshotId": snapshot_id}


def verify_confirmed_facts(helpers, state, reservation: str, slot_id: str) -> None:
    schema = str(state["meta"]["schema"])
    q = helpers.sql_quote
    status = execute_sql(
        helpers, state, f"SELECT status FROM `{schema}`.booking_reservation WHERE id={q(reservation)};"
    ).strip().splitlines()[-1]
    require(status == "CONFIRMED", "CONFIRMATION_NOT_APPLIED")
    occupant = execute_sql(
        helpers,
        state,
        f"SELECT reservation_id FROM `{schema}`.booking_slot_occupancy WHERE slot_id={q(slot_id)};",
    ).strip().splitlines()[-1]
    require(occupant == reservation, "OCCUPANCY_OWNER_MISMATCH")
    orders = execute_sql(
        helpers,
        state,
        f"SELECT COUNT(*) FROM `{schema}`.booking_order WHERE source_reservation_id={q(reservation)};",
    ).strip().splitlines()[-1]
    require(orders == "1", "ORDER_COUNT_MISMATCH")
    snapshots = execute_sql(
        helpers,
        state,
        "SELECT COUNT(*) FROM `%s`.booking_order_snapshot s JOIN `%s`.booking_order o ON o.id=s.order_id"
        " WHERE o.source_reservation_id=%s;" % (schema, schema, q(reservation)),
    ).strip().splitlines()[-1]
    require(snapshots == "1", "SNAPSHOT_COUNT_MISMATCH")


def cleanup(helpers, state, root: Path, base: Path) -> tuple[bool, bool, bool]:
    meta = state["meta"]
    if not verify_manifest(meta, helpers):
        return False, False, False
    runtime_ok, runtime_graceful = helpers.stop_runtime(state)
    mysql_ok, mysql_graceful = helpers.stop_mysql(state)
    if not (runtime_ok and mysql_ok):
        return False, runtime_graceful, mysql_graceful
    if any(helpers.port_owner(int(meta[key])) for key in ("mysqlPort", "webPort")):
        return False, runtime_graceful, mysql_graceful
    if root.is_symlink() or root.resolve().parent != base.resolve() or root.name != meta["runId"]:
        return False, runtime_graceful, mysql_graceful
    if not verify_manifest(meta, helpers):
        return False, runtime_graceful, mysql_graceful
    try:
        shutil.rmtree(root)
    except OSError:
        return False, runtime_graceful, mysql_graceful
    return not root.exists(), runtime_graceful, mysql_graceful


def run_fixture(helpers, evidence_name: str = "") -> int:
    # The console is GBK on this machine; a currency sign in the evidence must not abort the run.
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    tools = helpers.discover_tools()
    build = check_build_inputs()
    run_id = secrets.token_hex(4)
    base = tools["localAppData"] / "SouthLeaf" / "BookingMiniappVerification"
    base.mkdir(parents=True, exist_ok=True)
    root = base / run_id
    if root.exists():
        raise RuntimeError("FIXTURE_COLLISION")
    root.mkdir()
    helpers.secure_fixture_directory(root)
    mysql_port = helpers.allocate_loopback_port(set())
    web_port = helpers.allocate_loopback_port({mysql_port})
    meta: dict[str, object] = {
        "kind": FIXTURE_KIND,
        "runId": run_id,
        "root": str(root.resolve()),
        "host": "127.0.0.1",
        # AdminMigrationPolicy only accepts a schema named southleaf_admin*, so the disposable
        # schema keeps that prefix and carries the run id instead of a friendlier name.
        "schema": "southleaf_admin_miniapp" + run_id,
        "publicSchema": "southleaf_public_miniapp" + run_id,
        "mysqlPort": mysql_port,
        "webPort": web_port,
        "autoPort": 9420,
        "datadir": str((root / "mysql-data").resolve()),
        "mysqlExecutable": str(helpers.MYSQLD.resolve()),
        "mysqlStartMode": "no-monitor",
        "mysqlPid": None,
        "serverUuid": None,
        "runtimePid": None,
        "status": "manifest-created",
    }
    save_manifest(meta)
    helpers.verify_manifest = lambda candidate: verify_manifest(candidate, helpers)
    state: dict[str, object] = {
        "root": root,
        "meta": meta,
        "mysqlProcess": None,
        "runtimeProcess": None,
        "tls": None,
        "sessionSecret": secrets.token_hex(32),
        "devtoolsMode": "unknown",
    }
    passwords = {
        "migrator": secrets.token_urlsafe(32),
        "publicMigrator": secrets.token_urlsafe(32),
        "writer": secrets.token_urlsafe(32),
        "reader": secrets.token_urlsafe(32),
    }
    status = "FAIL"
    failure = None
    runtime_graceful = False
    mysql_graceful = False
    fixture_removed = False
    created: dict[str, object] = {}
    confirmed: dict[str, object] = {}
    try:
        print("MINIAPP_STAGE:tls_and_owned_mysql")
        state["tls"] = helpers.create_tls_fixture(root, tools)
        config = helpers.write_mysql_config(root, meta, state["tls"])
        helpers.checked_run(
            [str(helpers.MYSQLD), f"--defaults-file={config}", "--initialize-insecure"],
            cwd=helpers.MYSQL_HOME,
            timeout=180,
            category="MINIAPP_MYSQL_INITIALIZE_FAILED",
        )
        helpers.start_mysql(state, config)
        print("MINIAPP_STAGE:schemas_migrations_and_exact_grants")
        create_schemas(helpers, state, passwords)
        run_migrations(helpers, state, tools, build, passwords)
        create_scoped_users(helpers, state, passwords)
        slots = seed_slots(helpers, state)
        print("MINIAPP_STAGE:public_api_with_synthetic_identity_sink")
        start_public_api(helpers, state, tools, passwords)
        token = create_session(state, run_id)
        print("MINIAPP_STAGE:miniapp_build_and_devtools_automation")
        build_miniapp(helpers, state, tools)
        probe_intake(helpers, state, slots[-1])
        ensure_devtools(helpers, state)
        created = run_miniapp(helpers, state, tools, "create", token)
        reservation = str(created["reservationId"])
        slot_id = str(created["slotId"])
        require(slot_id in slots, "REQUEST_SLOT_NOT_ISOLATED")
        verify_pending_facts(helpers, state, reservation, slot_id)
        print("MINIAPP_STAGE:operator_confirmation_and_confirmed_agreement")
        promote_to_confirmed(helpers, state, reservation, slot_id)
        confirmed = run_miniapp(helpers, state, tools, "confirmed", token, reservation)
        verify_confirmed_facts(helpers, state, reservation, slot_id)
        status = "PASS"
    except Exception as error:
        detail = str(error).replace("\n", " ")[:400]
        print(f"MINIAPP_FAILURE_DETAIL:{type(error).__name__}:{detail}")
        failure = str(error) if str(error).isupper() and len(str(error)) < 100 else type(error).__name__
    finally:
        removed, runtime_graceful, mysql_graceful = cleanup(helpers, state, root, base)
        fixture_removed = removed
        if not removed:
            status = "FAIL"
            failure = "CLEANUP_INCOMPLETE"
        passwords.clear()
    evidence = {
        "kind": FIXTURE_KIND,
        "status": status,
        "runId": run_id,
        "checkCount": 9,
        "checks": [
            "dedicated_isolated_tls_mysql_admin_and_public_schemas",
            "intake_writer_grants_match_startup_policy_exactly",
            "public_api_started_with_synthetic_data_mode_and_test_identity_sink",
            "miniapp_built_against_the_isolated_api_origin",
            "wechat_devtools_automation_drives_the_real_miniapp",
            "miniapp_lists_packages_and_slots_without_any_payment_entry",
            "miniapp_submits_a_pending_request_and_reports_pending_status",
            "pending_request_holds_no_slot_and_leaves_audit_and_outbox",
            "miniapp_shows_confirmed_agreement_without_payment",
        ],
        "devtoolsMode": state.get("devtoolsMode"),
        "created": created,
        "confirmed": confirmed,
        "failureCategory": failure,
        "cleanup": {
            "fixtureRemoved": fixture_removed,
            "runtimeGraceful": runtime_graceful,
            "mysqlGraceful": mysql_graceful,
        },
        "syntheticDataOnly": True,
        "testSinkOnly": True,
        "secretsRecorded": False,
        "realOrProductionDataUsed": False,
        "limitations": [
            "The WeChat identity is the TEST sink: no real openid, no WeChat login, no getPhoneNumber.",
            "The developer tools IDE is a local GUI process; the runner starts it but does not close it.",
            "Notification delivery stays in the test sink, so this proves the intake view, not real messaging.",
        ],
    }
    if evidence_name:
        if not evidence_name.isalnum() or not evidence_name.islower():
            print("MINIAPP_NOT_RUN:EVIDENCE_NAME_INVALID")
            return 3
        target = REPO / "docs" / "开发与演示" / "证据" / f"stage-two-booking-miniapp-isolated-l5-{evidence_name}.json"
        if target.exists():
            print("MINIAPP_NOT_RUN:EVIDENCE_ALREADY_EXISTS")
            return 3
        target.write_text(json.dumps(evidence, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        print(f"MINIAPP_EVIDENCE:{target.as_posix()}")
    print(json.dumps(evidence, ensure_ascii=False, sort_keys=True))
    return 0 if status == "PASS" and fixture_removed else 2


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-disposable-miniapp-booking-e2e", action="store_true")
    parser.add_argument("--evidence-name", default="")
    args = parser.parse_args()
    if not args.confirm_disposable_miniapp_booking_e2e:
        print("MINIAPP_NOT_RUN:EXPLICIT_FIXTURE_CONFIRMATION_REQUIRED")
        return 3
    helpers = load_helpers()
    try:
        return run_fixture(helpers, args.evidence_name)
    except Exception as error:
        reason = str(error) if str(error).isupper() and len(str(error)) < 100 else type(error).__name__
        print(f"MINIAPP_NOT_RUN:{reason}")
        return 3


if __name__ == "__main__":
    sys.exit(main())
