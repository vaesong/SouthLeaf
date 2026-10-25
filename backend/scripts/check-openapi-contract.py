#!/usr/bin/env python3
"""Cross-checks the two OpenAPI documents against the Spring controllers in this repository.

The check is deliberately narrow: it only decides whether every documented operation exists in
code and whether every implemented ``/api`` route is documented. It does not judge the shape of
the schemas, the wording of the descriptions, or runtime behaviour.

Usage:
  python backend/scripts/check-openapi-contract.py [--json]

Exit codes: 0 = contract holds, 1 = contract broken, 2 = the inputs could not be read.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit

REPOSITORY = Path(__file__).resolve().parents[2]
ADMIN_SPEC = REPOSITORY / "docs" / "接口" / "admin-api.openapi.json"
PUBLIC_SPEC = REPOSITORY / "docs" / "接口" / "public-api.openapi.json"

# Routes that must stay reachable but are deliberately absent from the contract: they exist only to
# answer with a terminal "gone" response for retired clients.
ALLOWED_UNDOCUMENTED = {"RetiredPublicRouteController"}

MAPPING = re.compile(r"@(Get|Post|Put|Patch|Delete)Mapping\b")
REQUEST_MAPPING = re.compile(r"@RequestMapping\s*\(([^)]*)\)", re.DOTALL)
CLASS_MAPPING = re.compile(r"@RequestMapping\s*\(([^)]*)\)", re.DOTALL)
STRING_LITERAL = re.compile(r'"([^"]*)"')
ROUTE_VARIABLE = re.compile(r"\{[^}]*\}")
VERB_ANNOTATIONS = {"Get": "get", "Post": "post", "Put": "put", "Patch": "patch", "Delete": "delete"}


def normalise(path: str) -> str:
    path = re.sub(r"/+", "/", path.strip())
    path = ROUTE_VARIABLE.sub("{}", path)
    if len(path) > 1 and path.endswith("/"):
        path = path[:-1]
    return path


def is_wildcard(path: str) -> bool:
    return "**" in path or "{*" in path


def class_base_path(text: str) -> str:
    """Returns the class level base path, which is the first mapping that names an /api route."""
    for args in CLASS_MAPPING.findall(text):
        for literal in STRING_LITERAL.findall(args):
            if literal.startswith("/api"):
                return literal
    return ""


def method_mappings(text: str) -> list[tuple[str, str]]:
    """Returns (verb, path) for each handler, using an empty path when the annotation has none."""
    found: list[tuple[str, str]] = []
    for match in MAPPING.finditer(text):
        verb = VERB_ANNOTATIONS[match.group(1)]
        tail = text[match.end() : match.end() + 400]
        if not tail.lstrip().startswith("("):
            found.append((verb, ""))
            continue
        depth = 0
        args_chars: list[str] = []
        for character in tail[tail.index("(") :]:
            if character == "(":
                depth += 1
                if depth == 1:
                    continue
            if character == ")":
                depth -= 1
                if depth == 0:
                    break
            args_chars.append(character)
        arguments = "".join(args_chars)
        literals = [value for value in STRING_LITERAL.findall(arguments) if value.startswith("/")]
        found.append((verb, literals[0] if literals else ""))
    return found


def scan_controllers(root: Path) -> tuple[dict[str, set[tuple[str, str]]], list[str], list[str]]:
    """Returns (routes by class, classes allowed to stay undocumented, wildcard routes)."""
    routes: dict[str, set[tuple[str, str]]] = {}
    wildcards: list[str] = []
    for file in sorted(root.rglob("*.java")):
        if "/target/" in file.as_posix() or "\\target\\" in file.as_posix():
            continue
        text = file.read_text(encoding="utf-8")
        if "@RestController" not in text:
            continue
        base = class_base_path(text)
        entries = routes.setdefault(file.stem, set())
        for verb, path in method_mappings(text):
            joined = normalise(base + ("" if path == "/" else path))
            if joined in ("", "/"):
                continue
            if is_wildcard(joined) or not joined.startswith("/api"):
                wildcards.append(f"{verb.upper()} {joined} ({file.stem})")
                continue
            entries.add((verb, joined))
        if not entries:
            routes.pop(file.stem, None)
    return routes, sorted(ALLOWED_UNDOCUMENTED), wildcards


def documented_operations(spec_path: Path) -> tuple[dict[str, dict], str, list[str]]:
    document = json.loads(spec_path.read_text(encoding="utf-8"))
    base = ""
    servers = document.get("servers") or []
    if servers:
        # A development server URL may be absolute; only its path belongs to the contract prefix.
        base = normalise(urlsplit(servers[0].get("url", "")).path or "")
    problems: list[str] = []
    operations: dict[str, dict] = {}
    seen_operation_ids: set[str] = set()
    for raw_path, item in document.get("paths", {}).items():
        full = normalise(base + raw_path) if base else normalise(raw_path)
        for verb, operation in item.items():
            if verb.lower() not in VERB_ANNOTATIONS.values():
                continue
            key = f"{verb.lower()} {full}"
            if not operation.get("operationId"):
                problems.append(f"{key} has no operationId")
            elif operation["operationId"] in seen_operation_ids:
                problems.append(f"{key} reuses operationId {operation['operationId']}")
            else:
                seen_operation_ids.add(operation["operationId"])
            if not operation.get("responses"):
                problems.append(f"{key} declares no responses")
            operations[key] = {"path": full, "verb": verb.lower(), "operationId": operation.get("operationId")}
    return operations, base, problems


def compare(name: str, spec_path: Path, controllers: dict[str, set[tuple[str, str]]], allowed: list[str]) -> dict:
    operations, base, spec_problems = documented_operations(spec_path)
    implemented: dict[str, str] = {}
    for owner, entries in controllers.items():
        for verb, path in entries:
            if base and not path.startswith(base):
                continue
            implemented[f"{verb} {path}"] = owner

    documented_keys = set(operations)
    implemented_keys = set(implemented)

    missing_in_code = sorted(documented_keys - implemented_keys)
    undocumented = sorted(implemented_keys - documented_keys)
    intentional = [key for key in undocumented if implemented[key] in allowed]
    broken = [key for key in undocumented if implemented[key] not in allowed]

    heads = sorted(key for key in documented_keys if key.split(" ")[0] == "head")
    head_covered = []
    for key in heads:
        verb, path = key.split(" ", 1)
        head_covered.append(f"{key} (Spring answers HEAD for the GET handler)" if f"get {path}" in implemented_keys else key)

    problems = list(spec_problems)
    for key in missing_in_code:
        problems.append(f"{key} is documented but no controller implements it")
    for key in broken:
        problems.append(f"{key} is implemented by {implemented[key]} but missing from the contract")

    return {
        "name": name,
        "spec": spec_path.relative_to(REPOSITORY).as_posix(),
        "specVersion": json.loads(spec_path.read_text(encoding="utf-8"))["info"]["version"],
        "baseUrl": base,
        "documentedPaths": len({value["path"] for value in operations.values()}),
        "documentedOperations": len(documented_keys),
        "implementedOperations": len(implemented_keys),
        "controllerCount": len({owner for owner in implemented.values()}),
        "missingInCode": missing_in_code,
        "missingFromContract": broken,
        "intentionalUndocumented": intentional,
        "headOperations": head_covered,
        "problems": problems,
        "result": "PASS" if not problems else "FAIL",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", action="store_true", help="print a single machine readable object")
    arguments = parser.parse_args()

    for spec in (ADMIN_SPEC, PUBLIC_SPEC):
        if not spec.is_file():
            print(f"OPENAPI_CONTRACT_ERROR missing spec {spec}", file=sys.stderr)
            return 2

    public_root = REPOSITORY / "backend" / "public-api" / "src" / "main" / "java"
    admin_root = REPOSITORY / "backend" / "admin-runtime" / "src" / "main" / "java"
    for root in (public_root, admin_root):
        if not root.is_dir():
            print(f"OPENAPI_CONTRACT_ERROR missing source root {root}", file=sys.stderr)
            return 2

    public_routes, allowed, public_wildcards = scan_controllers(public_root)
    admin_routes, _, admin_wildcards = scan_controllers(admin_root)

    admin = compare("admin", ADMIN_SPEC, admin_routes, allowed)
    public = compare("public", PUBLIC_SPEC, public_routes, allowed)
    admin["wildcardRoutes"] = admin_wildcards
    public["wildcardRoutes"] = public_wildcards

    result = "PASS" if admin["result"] == "PASS" and public["result"] == "PASS" else "FAIL"
    payload = {
        "result": result,
        "admin": admin,
        "public": public,
        "notes": [
            "Only /api routes are compared; SPA fallbacks and retired 410 handlers are reported, not enforced.",
            "HEAD operations are covered by Spring's automatic HEAD support for a GET handler.",
            "Schema shapes, status codes and error codes are not judged by this script.",
        ],
    }

    if arguments.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        for entry in (admin, public):
            print(
                f"OPENAPI_{entry['name'].upper()}_{entry['result']} version={entry['specVersion']}"
                f" paths={entry['documentedPaths']} documented={entry['documentedOperations']}"
                f" implemented={entry['implementedOperations']} controllers={entry['controllerCount']}"
                f" intentionalUndocumented={len(entry['intentionalUndocumented'])}"
                f" wildcard={len(entry['wildcardRoutes'])}"
            )
            for problem in entry["problems"]:
                print(f"  - {problem}")
        print(f"OPENAPI_CONTRACT_{result}")
    return 0 if result == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
