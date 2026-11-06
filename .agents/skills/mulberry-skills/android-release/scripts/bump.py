#!/usr/bin/env python3
"""Bump the Android app's versionCode, and its versionName when given.

Usage: bump.py [--version X.Y.Z] [--build N] [--dry-run]

Both live in apps/mobile/gradle.properties as MULBERRY_VERSION_NAME and MULBERRY_VERSION_CODE.
By default the build advances by one. Prints the result as JSON.
"""
import argparse
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[5]
PROPERTIES = ROOT / "apps/mobile/gradle.properties"


def read(text: str, key: str) -> str:
    match = re.search(rf"^{key}=(.*)$", text, re.M)
    if not match:
        sys.exit(f"{key} missing from {PROPERTIES.relative_to(ROOT)}")
    return match.group(1).strip()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version")
    parser.add_argument("--build", type=int)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    text = PROPERTIES.read_text()
    current_code = int(read(text, "MULBERRY_VERSION_CODE"))
    current_name = read(text, "MULBERRY_VERSION_NAME")
    code = args.build if args.build is not None else current_code + 1
    if code <= current_code:
        sys.exit(f"build {code} must exceed the current build {current_code}")
    name = args.version or current_name
    if not re.fullmatch(r"\d+\.\d+\.\d+", name):
        sys.exit(f"{name!r} is not MAJOR.MINOR.PATCH")

    if not args.dry_run:
        text = re.sub(r"^MULBERRY_VERSION_CODE=.*$", f"MULBERRY_VERSION_CODE={code}", text, count=1, flags=re.M)
        text = re.sub(r"^MULBERRY_VERSION_NAME=.*$", f"MULBERRY_VERSION_NAME={name}", text, count=1, flags=re.M)
        PROPERTIES.write_text(text)

    print(json.dumps({"previous": {"version": current_name, "build": current_code},
                      "version": name, "build": code, "dryRun": args.dry_run}, indent=2))


if __name__ == "__main__":
    main()
