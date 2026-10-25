#!/usr/bin/env python3
"""S6 local-only runtime/browser verification using a disposable MySQL + TLS fixture."""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.request import urlopen

REPO = Path(__file__).resolve().parents[2]
BACKEND = REPO / "backend"
WEB = REPO / "admin-web"
HELPER_PATH = BACKEND / "scripts" / "verify-s1d-admin-web-isolated.py"
BROWSER_TEST = WEB / "e2e" / "s6-booking-runtime.mjs"
FIXTURE_KIND = "S6_BOOKING_RUNTIME_E2E"
BOOKING_TABLES = (
    "booking_customer",
    "booking_external_identity",
    "booking_contact_point",
    "booking_customer_relationship",
    "booking_customer_link_request",
    "booking_service_slot",
    "booking_reservation",
    "booking_slot_occupancy",
    "booking_slot_assignment_history",
    "booking_order",
    "booking_order_snapshot",
    "booking_order_revision",
    "booking_command",
    "booking_notification_outbox",
    "booking_audit",
)


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
            and root.parent.resolve().name == "S6BookingRuntimeVerification"
            and root.resolve().parent == root.parent.resolve()
            and disk.get("schema") == "southleaf_admin_s1d" + str(disk.get("runId"))
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


def allocate_port(helpers, excluded: set[int]) -> int:
    return helpers.allocate_loopback_port(excluded)


def execute_sql(helpers, state: dict[str, object], sql: str) -> str:
    """Runs control SQL and keeps the server's own error text, so a fixture failure is diagnosable."""
    result = helpers.mysql_query_raw(state, sql)
    if result is None or result.returncode != 0:
        detail = "" if result is None else result.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError("S6_SQL_FAILED:" + detail.replace("\n", " ")[:300])
    return result.stdout.decode("utf-8", errors="replace").strip()


def seed_booking_facts(helpers, state: dict[str, object], ids: dict[str, str]) -> None:
    schema = str(state["meta"]["schema"])
    q = lambda value: helpers.sql_quote(value)
    created = datetime.now(timezone.utc).replace(tzinfo=None)
    starts = [created + timedelta(days=14 + index) for index in range(4)]
    created_text = created.strftime("%Y-%m-%d %H:%M:%S.%f")
    expires_text = (created + timedelta(days=2)).strftime("%Y-%m-%d %H:%M:%S.%f")
    windows = [
        (value.strftime("%Y-%m-%d %H:%M:%S.%f"), (value + timedelta(hours=1)).strftime("%Y-%m-%d %H:%M:%S.%f"))
        for value in starts
    ]
    customer = ids["customer"]
    slots = [ids["reschedule_old_slot"], ids["target_slot"], ids["cancel_slot"]]
    reservations = [ids["reschedule_reservation"], ids["cancel_reservation"]]
    orders = [ids["reschedule_order"], ids["cancel_order"]]
    snapshots = [ids["reschedule_snapshot"], ids["cancel_snapshot"]]
    sql = [
        f"INSERT INTO `{schema}`.booking_customer "
        "(id,status,display_name,language,created_at,updated_at,version) VALUES "
        f"({q(customer)},'ACTIVE','S6 Synthetic Customer','ja',{q(created_text)},{q(created_text)},1);",
    ]
    for index, slot in enumerate(slots):
        start_text, end_text = windows[index]
        sql.append(
            f"INSERT INTO `{schema}`.booking_service_slot "
            "(id,start_at,end_at,business_zone,display_offset,status,capacity,rule_version,revision,created_at,updated_at) VALUES "
            f"({q(slot)},{q(start_text)},{q(end_text)},'Asia/Tokyo','+09:00','OPEN',1,'s6-isolated-fixture-v1',1,{q(created_text)},{q(created_text)});"
        )
    pairs = [
        (reservations[0], slots[0], orders[0], snapshots[0], windows[0]),
        (reservations[1], slots[2], orders[1], snapshots[1], windows[2]),
    ]
    for reservation, slot, order, snapshot, (start_text, end_text) in pairs:
        sql.append(
            f"INSERT INTO `{schema}`.booking_reservation "
            "(id,customer_id,package_version_ref,source,status,expires_at,slot_id,schedule_revision,confirmed_at,confirmed_by,created_at,updated_at,version) VALUES "
            f"({q(reservation)},{q(customer)},'s6-synthetic-package-v1','OFFLINE','CONFIRMED',{q(expires_text)},{q(slot)},1,{q(created_text)},'s6-fixture-operator',{q(created_text)},{q(created_text)},2);"
        )
        sql.append(
            f"INSERT INTO `{schema}`.booking_slot_occupancy (slot_id,reservation_id,assigned_at,revision) "
            f"VALUES ({q(slot)},{q(reservation)},{q(created_text)},1);"
        )
        sql.append(
            f"INSERT INTO `{schema}`.booking_slot_assignment_history "
            "(id,slot_id,reservation_id,previous_slot_id,assigned_at,reason_code,revision) VALUES "
            f"({q(str(uuid.uuid4()))},{q(slot)},{q(reservation)},NULL,{q(created_text)},'S6_E2E_SEED',1);"
        )
        sql.append(
            f"INSERT INTO `{schema}`.booking_order "
            "(id,source_reservation_id,customer_id,lifecycle_status,fulfilment_status,snapshot_id,current_revision,created_at,updated_at,version) VALUES "
            f"({q(order)},{q(reservation)},{q(customer)},'ACTIVE','UNSCHEDULED',{q(snapshot)},1,{q(created_text)},{q(created_text)},1);"
        )
        sql.append(
            f"INSERT INTO `{schema}`.booking_order_snapshot "
            "(id,order_id,package_version_ref,package_display_name,currency_code,amount_minor_units,price_version,slot_id,start_at,end_at,business_zone,display_offset,schedule_revision,customer_id,source,captured_at) VALUES "
            f"({q(snapshot)},{q(order)},'s6-synthetic-package-v1','S6 Synthetic Test Package','JPY',12345,'s6-synthetic-price-v1',{q(slot)},{q(start_text)},{q(end_text)},'Asia/Tokyo','+09:00',1,{q(customer)},'OFFLINE',{q(created_text)});"
        )
    # Intake-shaped pending request: a package version is named and no slot is held yet. Confirming
    # it is the operator gate from Q02, and the price can only come from the SYNTHETIC package fact.
    confirm_start, confirm_end = windows[3]
    sql.append(
        f"INSERT INTO `{schema}`.booking_service_slot "
        "(id,start_at,end_at,business_zone,display_offset,status,capacity,rule_version,revision,created_at,updated_at) VALUES "
        f"({q(ids['confirm_slot'])},{q(confirm_start)},{q(confirm_end)},'Asia/Tokyo','+09:00','OPEN',1,'s6-isolated-fixture-v1',1,{q(created_text)},{q(created_text)});"
    )
    sql.append(
        f"INSERT INTO `{schema}`.booking_reservation "
        "(id,customer_id,package_version_ref,source,status,expires_at,slot_id,schedule_revision,confirmed_at,confirmed_by,created_at,updated_at,version) VALUES "
        f"({q(ids['confirm_reservation'])},{q(customer)},'s6-synthetic-package-v1','OFFLINE','PENDING_CONFIRMATION',{q(expires_text)},NULL,0,NULL,NULL,{q(created_text)},{q(created_text)},1);"
    )
    sql.append(
        f"INSERT INTO `{schema}`.booking_reservation "
        "(id,customer_id,package_version_ref,source,status,expires_at,slot_id,schedule_revision,confirmed_at,confirmed_by,created_at,updated_at,version) VALUES "
        f"({q(ids['reject_reservation'])},{q(customer)},'s6-synthetic-package-v1','OFFLINE','PENDING_CONFIRMATION',{q(expires_text)},NULL,0,NULL,NULL,{q(created_text)},{q(created_text)},1);"
    )
    sql.append(
        f"INSERT INTO `{schema}`.admin_package_fact "
        "(version_ref,display_name,currency_code,amount_minor_units,price_version,data_mode,created_at) VALUES "
        f"('s6-synthetic-package-v1','S6 Synthetic Test Package','JPY',12345,'s6-synthetic-price-v1','SYNTHETIC',{q(created_text)});"
    )
    execute_sql(helpers, state, "\n".join(sql))


def seed_dead_letter(helpers, state: dict[str, object], ids: dict[str, str]) -> None:
    schema = str(state["meta"]["schema"])
    q = helpers.sql_quote
    now = datetime.now(timezone.utc).replace(tzinfo=None)
    now_text = now.strftime("%Y-%m-%d %H:%M:%S.%f")
    due_text = (now + timedelta(days=1)).strftime("%Y-%m-%d %H:%M:%S.%f")
    execute_sql(
        helpers,
        state,
        f"INSERT INTO `{schema}`.booking_notification_outbox "
        "(id,intent,recipient_ref,object_ref,due_at,status,attempts,lease_until,lease_owner,last_error,created_at,updated_at) VALUES "
        f"({q(ids['dead_outbox_message'])},'RESERVATION_CONFIRMED','s6-synthetic-recipient','s6-synthetic-object',"
        f"{q(due_text)},'FAILED',3,NULL,NULL,'TEST_SINK_TIMEOUT',{q(now_text)},{q(now_text)});",
    )


def verify_business_facts(helpers, state: dict[str, object], ids: dict[str, str]) -> None:
    schema = str(state["meta"]["schema"])
    def query(sql: str) -> str:
        return helpers.mysql_exec(state, sql)
    moved = ids["reschedule_reservation"]
    old_slot = ids["reschedule_old_slot"]
    target_slot = ids["target_slot"]
    cancelled = ids["cancel_reservation"]
    cancel_slot = ids["cancel_slot"]
    moved_state = query(
        f"SELECT status,slot_id,version FROM `{schema}`.booking_reservation WHERE id='{moved}';"
    ).split("\t")
    if moved_state != ["CONFIRMED", target_slot, "3"]:
        raise RuntimeError("RESCHEDULE_RESERVATION_FACT_MISMATCH")
    if query(f"SELECT COUNT(*) FROM `{schema}`.booking_slot_occupancy WHERE slot_id='{old_slot}';") != "0":
        raise RuntimeError("RESCHEDULE_OLD_OCCUPANCY_RETAINED")
    if query(
        f"SELECT COUNT(*) FROM `{schema}`.booking_slot_occupancy WHERE slot_id='{target_slot}' AND reservation_id='{moved}';"
    ) != "1":
        raise RuntimeError("RESCHEDULE_NEW_OCCUPANCY_MISSING")
    if query(
        f"SELECT lifecycle_status,current_revision FROM `{schema}`.booking_order WHERE id='{ids['reschedule_order']}';"
    ) != "ACTIVE\t2":
        raise RuntimeError("RESCHEDULE_ORDER_REVISION_MISMATCH")
    if query(
        f"SELECT COUNT(*) FROM `{schema}`.booking_order_revision WHERE order_id='{ids['reschedule_order']}' AND revision_no=2;"
    ) != "1":
        raise RuntimeError("RESCHEDULE_HISTORY_MISSING")
    if query(
        f"SELECT slot_id,amount_minor_units,currency_code FROM `{schema}`.booking_order_snapshot WHERE order_id='{ids['reschedule_order']}';"
    ) != f"{old_slot}\t12345\tJPY":
        raise RuntimeError("RESCHEDULE_SNAPSHOT_MUTATED")
    # A refused reschedule must leave the original assignment, its revision and the ledger intact.
    if query(
        f"SELECT COUNT(*) FROM `{schema}`.booking_slot_assignment_history WHERE reservation_id='{moved}';"
    ) != "2":
        raise RuntimeError("RESCHEDULE_ASSIGNMENT_HISTORY_MISMATCH")
    if query(
        f"SELECT COUNT(*) FROM `{schema}`.booking_slot_assignment_history "
        f"WHERE slot_id='{cancel_slot}' AND reservation_id='{moved}';"
    ) != "0":
        raise RuntimeError("REFUSED_RESCHEDULE_LEAKED_ASSIGNMENT")
    if query(
        f"SELECT COUNT(*) FROM `{schema}`.booking_command WHERE operation='RESCHEDULE_RESERVATION';"
    ) != "1":
        raise RuntimeError("REFUSED_RESCHEDULE_RECORDED_COMMAND")
    cancelled_state = query(
        f"SELECT status,version FROM `{schema}`.booking_reservation WHERE id='{cancelled}';"
    ).split("\t")
    if cancelled_state != ["CANCELLED", "3"]:
        raise RuntimeError("CANCEL_RESERVATION_FACT_MISMATCH")
    if query(f"SELECT COUNT(*) FROM `{schema}`.booking_slot_occupancy WHERE slot_id='{cancel_slot}';") != "0":
        raise RuntimeError("CANCEL_OCCUPANCY_RETAINED")
    if query(
        f"SELECT lifecycle_status,current_revision FROM `{schema}`.booking_order WHERE id='{ids['cancel_order']}';"
    ) != "CANCELLED\t2":
        raise RuntimeError("CANCEL_ORDER_REVISION_MISMATCH")
    if query(
        f"SELECT COUNT(*) FROM `{schema}`.booking_order_revision WHERE order_id='{ids['cancel_order']}' AND revision_no=2;"
    ) != "1":
        raise RuntimeError("CANCEL_HISTORY_MISSING")
    pending = ids["confirm_reservation"]
    confirm_slot = ids["confirm_slot"]
    confirmed_state = query(
        f"SELECT status,slot_id,version,schedule_revision FROM `{schema}`.booking_reservation "
        f"WHERE id='{pending}';"
    ).split("\t")
    if confirmed_state != ["CONFIRMED", confirm_slot, "2", "1"]:
        raise RuntimeError("CONFIRM_RESERVATION_FACT_MISMATCH:" + "|".join(confirmed_state))
    if query(
        f"SELECT COUNT(*) FROM `{schema}`.booking_slot_occupancy WHERE slot_id='{confirm_slot}';"
    ) != "1":
        raise RuntimeError("CONFIRM_OCCUPANCY_NOT_UNIQUE")
    if query(
        f"SELECT COUNT(*) FROM `{schema}`.booking_slot_occupancy "
        f"WHERE slot_id='{confirm_slot}' AND reservation_id='{pending}';"
    ) != "1":
        raise RuntimeError("CONFIRM_OCCUPANCY_OWNER_MISMATCH")
    confirm_orders = query(
        f"SELECT COUNT(*) FROM `{schema}`.booking_order WHERE source_reservation_id='{pending}';"
    )
    if confirm_orders != "1":
        raise RuntimeError("CONFIRM_ORDER_NOT_UNIQUE:" + confirm_orders)
    confirm_snapshot = query(
        f"SELECT s.package_version_ref,s.currency_code,s.amount_minor_units,s.price_version,s.slot_id "
        f"FROM `{schema}`.booking_order_snapshot s "
        f"JOIN `{schema}`.booking_order o ON o.snapshot_id = s.id "
        f"WHERE o.source_reservation_id='{pending}';"
    )
    if confirm_snapshot != f"s6-synthetic-package-v1\tJPY\t12345\ts6-synthetic-price-v1\t{confirm_slot}":
        raise RuntimeError("CONFIRM_SNAPSHOT_FACT_MISMATCH:" + confirm_snapshot.replace("\t", "|"))
    # Revision 1 is the creation itself and is carried by the snapshot; corrections append from
    # revision 2. A freshly confirmed order therefore has no revision rows yet.
    confirm_order = query(
        f"SELECT lifecycle_status,current_revision FROM `{schema}`.booking_order "
        f"WHERE source_reservation_id='{pending}';"
    ).split("\t")
    if confirm_order != ["ACTIVE", "1"]:
        raise RuntimeError("CONFIRM_ORDER_FACT_MISMATCH:" + "|".join(confirm_order))
    if query(
        f"SELECT COUNT(*) FROM `{schema}`.booking_order_revision "
        f"WHERE order_id=(SELECT id FROM `{schema}`.booking_order WHERE source_reservation_id='{pending}');"
    ) != "0":
        raise RuntimeError("CONFIRM_ORDER_MUTATED_AFTER_CREATION")
    if query(
        f"SELECT COUNT(*) FROM `{schema}`.booking_audit WHERE object_type='RESERVATION' "
        f"AND object_id='{pending}' AND action='CONFIRM';"
    ) != "1":
        raise RuntimeError("CONFIRM_AUDIT_NOT_EXACTLY_ONE")
    if query(
        f"SELECT COUNT(*) FROM `{schema}`.booking_notification_outbox "
        f"WHERE intent='RESERVATION_CONFIRMED' AND object_ref='{pending}';"
    ) != "1":
        raise RuntimeError("CONFIRM_OUTBOX_NOT_EXACTLY_ONE")
    if query(
        f"SELECT COUNT(*) FROM `{schema}`.booking_command WHERE operation='CONFIRM_RESERVATION' "
        f"AND result_ref=(SELECT id FROM `{schema}`.booking_order WHERE source_reservation_id='{pending}');"
    ) != "1":
        raise RuntimeError("CONFIRM_COMMAND_NOT_EXACTLY_ONE")
    # The issued fact stays as issued: a correction would be a new version_ref, never an update.
    if query(
        f"SELECT COUNT(*) FROM `{schema}`.admin_package_fact "
        "WHERE version_ref='s6-synthetic-package-v1' AND amount_minor_units=12345 "
        "AND currency_code='JPY' AND data_mode='SYNTHETIC';"
    ) != "1":
        raise RuntimeError("PACKAGE_FACT_ROW_CHANGED")
    refused = ids["reject_reservation"]
    refused_state = query(
        f"SELECT status,slot_id,version FROM `{schema}`.booking_reservation WHERE id='{refused}';"
    ).split("\t")
    if refused_state != ["REJECTED", "NULL", "2"]:
        raise RuntimeError("REJECT_RESERVATION_FACT_MISMATCH:" + "|".join(refused_state))
    # Refusing a request must never hold a slot or create an order.
    if query(
        f"SELECT COUNT(*) FROM `{schema}`.booking_slot_occupancy WHERE reservation_id='{refused}';"
    ) != "0":
        raise RuntimeError("REJECT_OCCUPANCY_LEAKED")
    if query(
        f"SELECT COUNT(*) FROM `{schema}`.booking_order WHERE source_reservation_id='{refused}';"
    ) != "0":
        raise RuntimeError("REJECT_CREATED_AN_ORDER")
    if query(
        f"SELECT COUNT(*) FROM `{schema}`.booking_audit WHERE object_type='RESERVATION' "
        f"AND object_id='{refused}' AND action='REJECT';"
    ) != "1":
        raise RuntimeError("REJECT_AUDIT_NOT_EXACTLY_ONE")
    if query(
        f"SELECT COUNT(*) FROM `{schema}`.booking_notification_outbox "
        f"WHERE intent='RESERVATION_REJECTED' AND object_ref='{refused}';"
    ) != "1":
        raise RuntimeError("REJECT_OUTBOX_NOT_EXACTLY_ONE")
    if query(
        f"SELECT COUNT(*) FROM `{schema}`.booking_command WHERE operation='REJECT_RESERVATION';"
    ) != "1":
        raise RuntimeError("REJECT_COMMAND_NOT_EXACTLY_ONE")

    audit_total = query(f"SELECT COUNT(*) FROM `{schema}`.booking_audit;")
    if audit_total != "5":
        raise RuntimeError("BOOKING_AUDIT_FACTS_MISMATCH:" + audit_total)
    outbox_total = query(f"SELECT COUNT(*) FROM `{schema}`.booking_notification_outbox;")
    if outbox_total != "5":
        raise RuntimeError("BOOKING_OUTBOX_FACTS_MISMATCH:" + outbox_total)
    command_total = query(f"SELECT COUNT(*) FROM `{schema}`.booking_command;")
    if command_total != "5":
        raise RuntimeError("BOOKING_COMMAND_FACTS_MISMATCH:" + command_total)
    recovered = query(
        f"SELECT status,attempts FROM `{schema}`.booking_notification_outbox "
        f"WHERE id='{ids['dead_outbox_message']}';"
    ).split("\t")
    if len(recovered) != 2 or recovered[0] == "FAILED" or recovered[1] not in {"3", "4"}:
        raise RuntimeError("OUTBOX_MANUAL_RECOVERY_FACT_MISMATCH")
    if query(
        f"SELECT COUNT(*) FROM `{schema}`.booking_audit WHERE object_type='NOTIFICATION' "
        f"AND object_id='{ids['dead_outbox_message']}' AND action='MANUAL_RETRY' "
        "AND reason_code='OPERATOR_REVIEWED';"
    ) != "1":
        raise RuntimeError("OUTBOX_MANUAL_RECOVERY_AUDIT_MISSING")


def create_booking_and_outbox_users(helpers, state: dict[str, object], passwords: dict[str, str]) -> None:
    schema = str(state["meta"]["schema"])
    statements = [
        "CREATE USER 'southleaf_booking_writer'@'localhost' IDENTIFIED BY " + helpers.sql_quote(passwords["booking"]) + ";",
        "CREATE USER 's6_outbox'@'localhost' IDENTIFIED BY " + helpers.sql_quote(passwords["outbox"]) + ";",
    ]
    for table in BOOKING_TABLES:
        statements.append(f"GRANT ALL PRIVILEGES ON `{schema}`.`{table}` TO 'southleaf_booking_writer'@'localhost';")
    # admin_package_fact is a mode-scoped, read-only price source. The writer may only SELECT from
    # it; the runtime verifies at startup that this read-only privilege set matches exactly.
    statements.append(
        f"GRANT SELECT ON `{schema}`.admin_package_fact TO 'southleaf_booking_writer'@'localhost';"
    )
    statements.extend(
        [
            f"GRANT SELECT, UPDATE ON `{schema}`.booking_notification_outbox TO 's6_outbox'@'localhost';",
            f"GRANT SELECT, INSERT ON `{schema}`.booking_command TO 's6_outbox'@'localhost';",
            f"GRANT INSERT ON `{schema}`.booking_audit TO 's6_outbox'@'localhost';",
        ]
    )
    execute_sql(helpers, state, "\n".join(statements))


def grant_synthetic_operator(helpers, state: dict[str, object], account_id: str) -> None:
    schema = str(state["meta"]["schema"])
    codes = (
        "RESERVATION_READ",
        "RESERVATION_CANCEL",
        "RESERVATION_RESCHEDULE",
        "RESERVATION_CONFIRM",
        "RESERVATION_REJECT",
        "ORDER_READ",
        "OUTBOX_READ",
        "OUTBOX_RECOVER",
    )
    values = ",".join(f"({helpers.sql_quote(account_id)},{helpers.sql_quote(code)})" for code in codes)
    execute_sql(
        helpers,
        state,
        f"INSERT INTO `{schema}`.admin_account_permission (account_id,permission_code) VALUES {values};",
    )


def start_runtime(helpers, state, tools, build, passwords):
    meta = state["meta"]
    candidate = helpers.static_candidate(Path(str(meta["root"])), build, meta)
    state["candidate"] = candidate
    env = helpers.java_options_env(
        state["tls"],
        {
            "SOUTHLEAF_ADMIN_MODE": "demo",
            "SOUTHLEAF_ADMIN_DB_URL": helpers.jdbc_url(int(meta["mysqlPort"]), str(meta["schema"])),
            "SOUTHLEAF_ADMIN_DB_USERNAME": "s1d_runtime",
            "SOUTHLEAF_ADMIN_DB_PASSWORD": passwords["runtime"],
            "SOUTHLEAF_ADMIN_DB_SCHEMA": str(meta["schema"]),
            "SOUTHLEAF_ADMIN_DB_EXPECTED_USER": "s1d_runtime@localhost",
            "SOUTHLEAF_ADMIN_WEB_MODE": "demo",
            "SOUTHLEAF_ADMIN_WEB_ORIGIN": f"https://localhost:{meta['webPort']}",
            "SOUTHLEAF_ADMIN_WEB_PORT": str(meta["webPort"]),
            "SOUTHLEAF_ADMIN_WEB_KEYSTORE_PATH": str(Path(str(meta["root"])) / "runtime-keystore.jks"),
            "SOUTHLEAF_ADMIN_WEB_KEYSTORE_PASSWORD": state["tls"]["keyStorePassword"],
            "SOUTHLEAF_ADMIN_WEB_KEYSTORE_TYPE": "JKS",
            "SOUTHLEAF_ADMIN_BOOKING_MODE": "enabled",
            "SOUTHLEAF_ADMIN_BOOKING_DB_USERNAME": "southleaf_booking_writer",
            "SOUTHLEAF_ADMIN_BOOKING_DB_PASSWORD": passwords["booking"],
            "SOUTHLEAF_ADMIN_BOOKING_DB_EXPECTED_USER": "southleaf_booking_writer@localhost",
            # The writer only sees SYNTHETIC package facts; a production runtime can never read them.
            "SOUTHLEAF_ADMIN_BOOKING_DATA_MODE": "SYNTHETIC",
            "SOUTHLEAF_ADMIN_OUTBOX_MODE": "enabled",
            "SOUTHLEAF_ADMIN_OUTBOX_DB_USERNAME": "s6_outbox",
            "SOUTHLEAF_ADMIN_OUTBOX_DB_PASSWORD": passwords["outbox"],
            "SOUTHLEAF_ADMIN_OUTBOX_DB_EXPECTED_USER": "s6_outbox@localhost",
            "SOUTHLEAF_ADMIN_OUTBOX_DELIVERY_MODE": "TEST_SINK",
            "SOUTHLEAF_ADMIN_OUTBOX_MAX_ATTEMPTS": "3",
            "SOUTHLEAF_ADMIN_OUTBOX_LEASE_SECONDS": "30",
            "SOUTHLEAF_ADMIN_OUTBOX_RETRY_BASE_SECONDS": "2",
            "SOUTHLEAF_ADMIN_OUTBOX_RETRY_MAX_SECONDS": "30",
            "SOUTHLEAF_ADMIN_OUTBOX_BATCH_SIZE": "10",
            "SOUTHLEAF_ADMIN_OUTBOX_POLL_INTERVAL_MS": "1500",
            "SOUTHLEAF_ADMIN_OUTBOX_WORKER_ID": "s6e2e_" + str(meta["runId"]),
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
    meta["status"] = "s6-https-runtime-starting"
    save_manifest(meta)
    ready = helpers.Queue()
    diagnostics = helpers.Queue()
    helpers.threading.Thread(
        target=helpers.drain_runtime_stdout,
        args=(process, ready, diagnostics),
        daemon=True,
    ).start()
    expected = "ADMIN_RUNTIME_READY:admin-isolated-v2:HTTPS_WEB_OUTBOX_BOOKING"
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            marker = ready.get(timeout=0.25)
        except helpers.Empty:
            if process.poll() is not None:
                diagnostic = helpers.take_runtime_diagnostic(diagnostics)
                raise RuntimeError("RUNTIME_EXITED_" + diagnostic if diagnostic else "RUNTIME_EXITED_BEFORE_READY")
            continue
        if marker.startswith("ADMIN_RUNTIME_REFUSED:"):
            reason = marker.partition(":")[2]
            if not reason.replace("_", "").isalnum():
                raise RuntimeError("RUNTIME_INVALID_REFUSAL_MARKER")
            raise RuntimeError("RUNTIME_REFUSED_" + reason)
        if marker != expected or process.poll() is not None:
            raise RuntimeError("RUNTIME_READY_MARKER_MISMATCH")
        listeners = helpers.netstat_listeners(process.pid)
        if len(listeners) != 1 or not helpers.is_loopback_listener(listeners[0], int(meta["webPort"])):
            raise RuntimeError("RUNTIME_LISTENER_SCOPE_INVALID")
        helpers.runtime_http_probe(int(meta["webPort"]), str(state["tls"]["caCert"]))
        meta["status"] = "s6-runtime-ready"
        save_manifest(meta)
        return
    raise RuntimeError("RUNTIME_READY_TIMEOUT")


def run_browser(helpers, state, tools, account_id, password, ids):
    root = Path(str(state["meta"]["root"]))
    profile = root / "edge-profile"
    env = helpers.child_env(
        {
            "S6_E2E_BASE_URL": f"https://localhost:{state['meta']['webPort']}",
            "S6_E2E_ACCOUNT": account_id,
            "S6_E2E_PASSWORD": password,
            "S6_E2E_FIXTURE_ROOT": str(root),
            "S6_E2E_PROFILE_DIR": str(profile),
            "S6_E2E_RESCHEDULE_ID": ids["reschedule_reservation"],
            "S6_E2E_CANCEL_ID": ids["cancel_reservation"],
            "S6_E2E_TARGET_SLOT_ID": ids["target_slot"],
            # The cancel reservation still holds this slot when the refused reschedule runs.
            "S6_E2E_CONFLICT_SLOT_ID": ids["cancel_slot"],
            "S6_E2E_DEAD_MESSAGE_ID": ids["dead_outbox_message"],
            "S6_E2E_CONFIRM_ID": ids["confirm_reservation"],
            "S6_E2E_CONFIRM_SLOT_ID": ids["confirm_slot"],
            "S6_E2E_REJECT_ID": ids["reject_reservation"],
        }
    )
    result = subprocess.run(
        [str(tools["node"]), str(BROWSER_TEST)],
        cwd=str(WEB),
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=100,
        check=False,
    )
    output = result.stdout.decode("utf-8", errors="replace")
    if result.returncode != 0 or "S6_BROWSER_PASS:" not in output:
        print(output[-5000:])
        raise RuntimeError("BROWSER_E2E_FAILED")
    print(output.strip())


def cleanup(helpers, state, root, base) -> tuple[bool, bool, bool]:
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
    tools = helpers.discover_tools()
    build = helpers.check_build_inputs()
    if not BROWSER_TEST.is_file():
        raise RuntimeError("BROWSER_TEST_MISSING")
    run_id = secrets.token_hex(4)
    base = tools["localAppData"] / "SouthLeaf" / "S6BookingRuntimeVerification"
    base.mkdir(parents=True, exist_ok=True)
    root = base / run_id
    if root.exists():
        raise RuntimeError("FIXTURE_COLLISION")
    root.mkdir()
    helpers.secure_fixture_directory(root)
    schema = "southleaf_admin_s1d" + run_id
    mysql_port = allocate_port(helpers, set())
    web_port = allocate_port(helpers, {mysql_port})
    meta: dict[str, object] = {
        "kind": FIXTURE_KIND,
        "runId": run_id,
        "root": str(root.resolve()),
        "host": "127.0.0.1",
        "schema": schema,
        "mysqlPort": mysql_port,
        "webPort": web_port,
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
        "checks": [],
    }
    passwords = {
        "migrator": secrets.token_urlsafe(32),
        "cliDb": secrets.token_urlsafe(32),
        "runtime": secrets.token_urlsafe(32),
        "booking": secrets.token_urlsafe(32),
        "outbox": secrets.token_urlsafe(32),
        "account": "S6!" + secrets.token_urlsafe(32),
    }
    account_id = "s1d." + run_id
    ids = {key: str(uuid.uuid4()) for key in (
        "customer", "reschedule_old_slot", "target_slot", "cancel_slot",
        "reschedule_reservation", "cancel_reservation", "reschedule_order",
        "cancel_order", "reschedule_snapshot", "cancel_snapshot", "dead_outbox_message",
        "confirm_slot", "confirm_reservation", "reject_reservation")}
    status = "FAIL"
    failure = None
    runtime_graceful = False
    mysql_graceful = False
    fixture_removed = False
    try:
        print("S6_STAGE:tls_and_owned_mysql")
        state["tls"] = helpers.create_tls_fixture(root, tools)
        config = helpers.write_mysql_config(root, meta, state["tls"])
        helpers.checked_run(
            [str(helpers.MYSQLD), f"--defaults-file={config}", "--initialize-insecure"],
            cwd=helpers.MYSQL_HOME,
            timeout=120,
            category="S6_MYSQL_INITIALIZE_FAILED",
        )
        helpers.start_mysql(state, config)
        print("S6_STAGE:isolated_schema_migrations")
        helpers.create_database_and_migrator(state, passwords)
        helpers.run_migrations(state, tools, build, passwords)
        helpers.create_scoped_database_users(state, passwords)
        create_booking_and_outbox_users(helpers, state, passwords)
        account_id = helpers.bootstrap_fixture_account(state, tools, build, passwords)
        grant_synthetic_operator(helpers, state, account_id)
        seed_booking_facts(helpers, state, ids)
        seed_dead_letter(helpers, state, ids)
        print("S6_STAGE:runtime_https_booking_and_test_sink_outbox")
        start_runtime(helpers, state, tools, build, passwords)
        print("S6_STAGE:edge_browser_confirm_reject_reschedule_refused_and_outbox_recovery")
        run_browser(helpers, state, tools, account_id, passwords["account"], ids)
        verify_business_facts(helpers, state, ids)
        status = "PASS"
    except Exception as error:
        detail = str(error).replace("\n", " ")[:400]
        print(f"S6_FAILURE_DETAIL:{type(error).__name__}:{detail}")
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
        "checkCount": 10,
        "checks": [
            "dedicated_isolated_tls_mysql_and_migrations",
            "verified_booking_writer_and_outbox_grants",
            "synthetic_session_capabilities_and_booking_facts",
            "runtime_https_same_origin_login_and_csrf",
            "browser_pending_request_confirmed_into_unique_order",
            "browser_pending_request_rejected_without_side_effects",
            "browser_reschedule_and_refused_reschedule_keeps_original",
          "browser_reschedule_cancel_and_order_revision",
            "browser_outbox_failure_review_and_manual_recovery",
            "database_occupancy_audit_command_and_outbox_facts",
        ],
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
    }
    if evidence_name:
        if not evidence_name.isalnum() or not evidence_name.islower():
            print("S6_NOT_RUN:EVIDENCE_NAME_INVALID")
            return 3
        target = REPO / "docs" / "开发与演示" / "证据" / f"stage-two-s5-confirm-isolated-l5-{evidence_name}.json"
        if target.exists():
            print("S6_NOT_RUN:EVIDENCE_ALREADY_EXISTS")
            return 3
        target.write_text(
            json.dumps(evidence, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8"
        )
        print(f"S6_EVIDENCE:{target.as_posix()}")
    print(json.dumps(evidence, ensure_ascii=False, sort_keys=True))
    return 0 if status == "PASS" and fixture_removed else 2


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-disposable-s6-booking-e2e", action="store_true")
    parser.add_argument("--evidence-name", default="")
    args = parser.parse_args()
    if not args.confirm_disposable_s6_booking_e2e:
        print("S6_NOT_RUN:EXPLICIT_FIXTURE_CONFIRMATION_REQUIRED")
        return 3
    helpers = load_helpers()
    try:
        return run_fixture(helpers, args.evidence_name)
    except Exception as error:
        reason = str(error) if str(error).isupper() and len(str(error)) < 100 else type(error).__name__
        print(f"S6_NOT_RUN:{reason}")
        return 3


if __name__ == "__main__":
    raise SystemExit(main())