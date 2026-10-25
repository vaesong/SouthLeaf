#!/usr/bin/env python3
"""Check that booking JDBC adapter write columns exist in the booking schemas.

The unit tests use in-memory fakes, so a wrong INSERT or UPDATE column in a production statement is
invisible until the statement runs against a real schema. This script reads the migration file and
the adapter sources and reports unknown table references and write columns. It does not replace the
real database integration suite, which is the authoritative SQL evidence.

Usage: python scripts/check-booking-sql-columns.py <migration.sql> [more.sql ...] <adapter dir>

Every given migration contributes tables; the last argument is the adapter source directory. The
booking adapter spans V6 (identity/booking) and V8 (package facts), so both must be passed.
"""
from __future__ import annotations

import pathlib
import re
import sys

IDENTIFIER = r"[A-Za-z_][A-Za-z0-9_]*"

TABLE_PATTERN = re.compile(
    rf"\b(?:INSERT\s+INTO|UPDATE|FROM)\s+({IDENTIFIER})\b", re.IGNORECASE
)
COLUMN_LIST_PATTERN = re.compile(r"\(([^()]*)\)", re.DOTALL)

# Words that may follow a table reference without being a table.
SQL_KEYWORDS = {
    "select", "insert", "update", "delete", "from", "where", "set", "values",
    "order", "group", "by", "and", "or", "not", "limit", "offset", "join",
    "inner", "left", "right", "on", "as", "in", "is", "null", "for", "skip",
    "locked", "asc", "desc", "count", "sum", "max", "min",
}


def parse_schema(migration: pathlib.Path) -> dict[str, set[str]]:
    """Returns the column names of every table of the migration file."""
    text = migration.read_text(encoding="utf-8")
    schema: dict[str, set[str]] = {}
    for match in re.finditer(
        rf"CREATE\s+TABLE\s+({IDENTIFIER})\s*\((.*?)\n\)\s*ENGINE", text, re.IGNORECASE | re.DOTALL
    ):
        table = match.group(1).lower()
        columns: set[str] = set()
        for line in match.group(2).splitlines():
            line = line.strip()
            if not line or line.startswith("--"):
                continue
            head = line.split()[0].upper()
            if head in {"PRIMARY", "CONSTRAINT", "UNIQUE", "KEY", "INDEX", "FOREIGN", "CHECK"}:
                continue
            column = re.match(rf"({IDENTIFIER})\s", line + " ")
            if column:
                columns.add(column.group(1).lower())
        schema[table] = columns
    return schema


def statements_of(source: pathlib.Path) -> list[str]:
    """Every quoted SQL string of the source file, joined when a statement is split."""
    text = source.read_text(encoding="utf-8")
    chunks = re.findall(r'"((?:[^"\\]|\\.)*)"', text)
    joined: list[str] = []
    buffer = ""
    for chunk in chunks:
        unescaped = chunk.replace('\\"', '"')
        if unescaped.lstrip().upper().startswith(("SELECT ", "INSERT ", "UPDATE ", "DELETE ")):
            if buffer:
                joined.append(buffer)
            buffer = unescaped
        elif buffer:
            buffer += unescaped
    if buffer:
        joined.append(buffer)
    return joined


def columns_of(statement: str) -> list[str]:
    """The column names an insert names, or the columns an update assigns."""
    lowered = statement.lower()
    columns: list[str] = []
    if "insert into" in lowered:
        head = lowered.split("insert into", 1)[1]
        table = re.match(rf"\s*({IDENTIFIER})", head)
        if not table:
            return columns
        rest = head[table.end() :]
        if "(" not in rest:
            return columns
        inner = rest[rest.index("(") + 1 : rest.index(")")]
        columns = []
        for part in inner.split(","):
            name = part.strip().lower()
            if name and name != "?" and re.fullmatch(IDENTIFIER, name):
                columns.append(name)
    elif "update" in lowered:
        head = lowered.split("update", 1)[1]
        table = re.match(rf"\s*({IDENTIFIER})", head)
        if not table:
            return columns
        rest = head[table.end() :]
        if " set " not in rest:
            return columns
        assignments = rest.split(" set ", 1)[1].split(" where ", 1)[0]
        for part in assignments.split(","):
            name = part.strip().split("=")[0].strip()
            if re.fullmatch(IDENTIFIER, name):
                columns.append(name.lower())
    return columns


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    migrations = [pathlib.Path(arg) for arg in sys.argv[1:-1]]
    adapters = pathlib.Path(sys.argv[-1])
    schema: dict[str, set[str]] = {}
    for migration in migrations:
        schema.update(parse_schema(migration))
    problems: list[str] = []
    for source in sorted(adapters.glob("*.java")):
        for statement in statements_of(source):
            lowered = statement.lower()
            for table_match in TABLE_PATTERN.finditer(statement):
                table = table_match.group(1).lower()
                if table in SQL_KEYWORDS:
                    continue
                if table not in schema:
                    problems.append(f"{source.name}: unknown table {table}")
                    continue
                known = schema[table]
                for column in columns_of(statement):
                    if column not in known:
                        problems.append(
                            f"{source.name}: {table} has no column {column}"
                        )
    if problems:
        print("BOOKING_SQL_COLUMN_MISMATCH")
        for problem in sorted(set(problems)):
            print("  " + problem)
        return 1
    print("BOOKING_SQL_COLUMNS_MATCH_SCHEMA")
    return 0


if __name__ == "__main__":
    sys.exit(main())
