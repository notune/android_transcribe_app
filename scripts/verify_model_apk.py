#!/usr/bin/env python3
"""Verify the immutable model contract in a built APK."""

import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path
from typing import NoReturn


def fail(message: str) -> NoReturn:
    print(f"model APK verification failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--metadata", required=True, type=Path)
    parser.add_argument("--manifest-package", required=True)
    args = parser.parse_args()

    metadata = json.loads(args.metadata.read_text(encoding="utf-8"))
    expected_package = metadata.get("application_id")
    if args.manifest_package != expected_package:
        fail(f"manifest package {args.manifest_package!r} != {expected_package!r}")

    asset_path = metadata.get("asset_path")
    expected_len = metadata.get("byte_length")
    expected_hash = metadata.get("sha256")
    if not isinstance(asset_path, str) or not asset_path.startswith("assets/builtin-model/"):
        fail("invalid asset path metadata")
    if not isinstance(expected_len, int) or expected_len <= 0:
        fail("expected byte length must be positive")
    if not isinstance(expected_hash, str) or len(expected_hash) != 64:
        fail("expected SHA-256 must be 64 lowercase hex characters")
    try:
        bytes.fromhex(expected_hash)
    except ValueError:
        fail("expected SHA-256 is not hexadecimal")
    if expected_hash != expected_hash.lower():
        fail("expected SHA-256 must be lowercase")

    with zipfile.ZipFile(args.apk) as archive:
        matches = [entry for entry in archive.infolist() if entry.filename == asset_path]
        if len(matches) != 1:
            fail(f"expected exactly one {asset_path}, found {len(matches)}")
        entry = matches[0]
        if entry.file_size != expected_len:
            fail(f"asset length {entry.file_size} != {expected_len}")
        digest = hashlib.sha256()
        total = 0
        with archive.open(entry) as stream:
            while chunk := stream.read(1024 * 1024):
                total += len(chunk)
                digest.update(chunk)
        if total != expected_len:
            fail(f"streamed asset length {total} != {expected_len}")
        actual_hash = digest.hexdigest()
        if actual_hash != expected_hash:
            fail(f"asset SHA-256 {actual_hash} != {expected_hash}")

    print(f"verified {args.apk}: package={expected_package} asset={asset_path} bytes={expected_len} sha256={expected_hash}")


if __name__ == "__main__":
    main()
