#!/usr/bin/env python3
"""Bump CFBundleVersion for the requested Mulberry Apple platforms.

Usage: bump-build.py <ios|macos>... [--build N] [--version X.Y.Z] [--dry-run]

By default each platform advances its own highest app/widget build by one.
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
    "macos": [ROOT / "apps/macos/Configuration/Info.plist", ROOT / "apps/macos/Configuration/Widgets-Info.plist"],
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
    builds = {}
    for platform in args.platforms:
        highest = max(int(current[str(p.relative_to(ROOT))]) for p in PLISTS[platform])
        build = args.build if args.build is not None else highest + 1
        if build <= highest:
            sys.exit(f"{platform}: build {build} must exceed current build {highest}")
        builds[platform] = str(build)

    if not args.dry_run:
        for platform in args.platforms:
            for path in PLISTS[platform]:
                write_key(path, "CFBundleVersion", builds[platform])
                if args.version:
                    write_key(path, "CFBundleShortVersionString", args.version)

    versions = {platform: args.version or read_key(PLISTS[platform][0].read_text(), "CFBundleShortVersionString") for platform in args.platforms}
    print(json.dumps({"previous": current, "builds": builds, "versions": versions, "dryRun": args.dry_run}, indent=2))


if __name__ == "__main__":
    main()
