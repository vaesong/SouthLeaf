#!/usr/bin/env python3
"""Pack the built admin-web SPA into the admin-runtime jar.

This is the only supported way to produce a deployable admin-runtime jar: the isolated
verification harness and the deployment instructions both call it, so what the L5 run exercises
is exactly what ships.

The jar is rebuilt into a temporary file and swapped in, because zip archives cannot drop entries
in place; a failed run therefore leaves the previous jar untouched.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

PREFIX = "BOOT-INF/classes/static/"
MAX_FILES = 4000
MAX_FILE_BYTES = 8 * 1024 * 1024
MAX_TOTAL_BYTES = 96 * 1024 * 1024


class PackagingRefused(Exception):
    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


def dist_files(dist: Path) -> list[Path]:
    if not dist.is_dir():
        raise PackagingRefused("DIST_MISSING")
    files = sorted(path for path in dist.rglob("*") if path.is_file())
    if not files:
        raise PackagingRefused("DIST_EMPTY")
    if not (dist / "index.html").is_file():
        raise PackagingRefused("DIST_INDEX_MISSING")
    if not any(path.parts[len(dist.parts)] == "assets" for path in files if len(path.parts) > len(dist.parts)):
        raise PackagingRefused("DIST_ASSETS_MISSING")
    if len(files) > MAX_FILES:
        raise PackagingRefused("DIST_TOO_MANY_FILES")
    total = 0
    for path in files:
        size = path.stat().st_size
        if size > MAX_FILE_BYTES:
            raise PackagingRefused("DIST_FILE_TOO_LARGE")
        total += size
    if total > MAX_TOTAL_BYTES:
        raise PackagingRefused("DIST_TOO_LARGE")
    return files


def pack(jar: Path, dist: Path, output: Path) -> dict[str, object]:
    if not jar.is_file():
        raise PackagingRefused("JAR_MISSING")
    files = dist_files(dist)
    if jar.resolve() == output.resolve():
        target = jar.with_suffix(".jar.packing")
    else:
        target = output
    handle, temporary = tempfile.mkstemp(suffix=".jar", dir=str(output.parent))
    os.close(handle)
    temporary_path = Path(temporary)
    try:
        written = 0
        with zipfile.ZipFile(jar) as source, zipfile.ZipFile(
            temporary_path, "w", compression=zipfile.ZIP_DEFLATED
        ) as sink:
            for entry in source.infolist():
                if entry.filename.startswith(PREFIX):
                    # Repacking always replaces the whole tree: a stale asset must never survive.
                    continue
                sink.writestr(entry, source.read(entry.filename))
            for path in files:
                relative = path.relative_to(dist).as_posix()
                if relative.startswith("../") or "\\" in relative:
                    raise PackagingRefused("DIST_PATH_INVALID")
                sink.write(path, PREFIX + relative)
                written += 1
        with zipfile.ZipFile(temporary_path) as check:
            names = set(check.namelist())
            if PREFIX + "index.html" not in names:
                raise PackagingRefused("STATIC_INDEX_NOT_PACKAGED")
            if not any(name.startswith(PREFIX + "assets/") for name in names):
                raise PackagingRefused("STATIC_ASSETS_NOT_PACKAGED")
        os.replace(temporary_path, target)
        if target != output:
            os.replace(target, output)
    finally:
        if temporary_path.exists():
            temporary_path.unlink()
    return {
        "files": written,
        "bytes": sum(path.stat().st_size for path in files),
        "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, help="admin-runtime jar to pack into")
    parser.add_argument("--dist", required=True, help="built admin-web dist directory")
    parser.add_argument("--output", help="write here instead of replacing the jar")
    args = parser.parse_args(argv)
    jar = Path(args.jar).resolve()
    dist = Path(args.dist).resolve()
    output = Path(args.output).resolve() if args.output else jar
    try:
        result = pack(jar, dist, output)
    except PackagingRefused as refusal:
        print(f"ADMIN_RUNTIME_STATIC_REFUSED:{refusal.reason}")
        return 2
    print(
        "ADMIN_RUNTIME_STATIC_PACKED:files={files}:bytes={bytes}:sha256={sha256}".format(
            **{key: result[key] for key in ("files", "bytes", "sha256")}
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
