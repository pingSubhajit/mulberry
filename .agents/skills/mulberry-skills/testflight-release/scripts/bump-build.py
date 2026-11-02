#!/usr/bin/env python3
"""Bump CFBundleVersion for the requested Mulberry Apple platforms.

Usage: bump-build.py <ios|macos>... [--build N] [--version X.Y.Z] [--dry-run]

By default the new build is one more than the highest build in any of the
three plists, so iOS and macOS stay aligned even when only one ships.
Edits the plist text in place, keeping its formatting. Prints the result as JSON.
"""
import argparse
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[5]
PLISTS = {
    "ios": [ROOT / "apps/ios/Configuration/Info.plist", ROOT / "apps/ios/Configuration/Widgets-Info.plist"],
    "macos": [ROOT / "apps/macos/Configuration/Info.plist"],
}


def read_key(text: str, key: str) -> str:
    match = re.search(rf"<key>{key}</key>\s*<string>([^<]*)</string>", text)
    if not match:
        sys.exit(f"{key} missing")
    return match.group(1)


def write_key(path: pathlib.Path, key: str, value: str) -> None:
    text = path.read_text()
    new, count = re.subn(rf"(<key>{key}</key>\s*<string>)[^<]*(</string>)", rf"\g<1>{value}\g<2>", text)
    if count != 1:
        sys.exit(f"{path}: expected one {key}, found {count}")
    path.write_text(new)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("platforms", nargs="+", choices=sorted(PLISTS))
    parser.add_argument("--build", type=int)
    parser.add_argument("--version")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    current = {str(p.relative_to(ROOT)): read_key(p.read_text(), "CFBundleVersion") for ps in PLISTS.values() for p in ps}
    highest = max(int(v) for v in current.values())
    build = args.build if args.build is not None else highest + 1
    if build <= highest and args.build is None:
        sys.exit("internal: build did not advance")

    targets = [p for platform in args.platforms for p in PLISTS[platform]]
    if not args.dry_run:
        for path in targets:
            write_key(path, "CFBundleVersion", str(build))
            if args.version:
                write_key(path, "CFBundleShortVersionString", args.version)

    versions = {platform: read_key(PLISTS[platform][0].read_text(), "CFBundleShortVersionString") for platform in args.platforms}
    print(json.dumps({"previous": current, "build": str(build), "versions": versions, "dryRun": args.dry_run}, indent=2))


if __name__ == "__main__":
    main()
