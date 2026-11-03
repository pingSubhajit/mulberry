#!/usr/bin/env python3
"""Write <release-dir>/source.json for an uploaded Mulberry build.

Usage: record.py <release-dir> <ios|macos>... --validation "..." [--status "..."]
"""
import argparse
import hashlib
import json
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[5]
ARTIFACTS = {"ios": ("iOS", "iOS/Mulberry.ipa"), "macos": ("macOS", "macOS/Mulberry.pkg")}
SOURCE_PLIST = {"ios": "apps/ios/Configuration/Info.plist", "macos": "apps/macos/Configuration/Info.plist"}


def plist_value(path: str, key: str) -> str:
    return subprocess.check_output(["/usr/libexec/PlistBuddy", "-c", f"Print {key}", str(ROOT / path)], text=True).strip()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("release_dir")
    parser.add_argument("platforms", nargs="+", choices=sorted(ARTIFACTS))
    parser.add_argument("--validation", required=True)
    parser.add_argument("--status", default="Uploaded to App Store Connect; processing not yet confirmed")
    args = parser.parse_args()

    release = pathlib.Path(args.release_dir).resolve()
    commit = subprocess.check_output(["git", "-C", str(ROOT), "rev-parse", "--short", "HEAD"], text=True).strip()

    artifacts = {}
    for platform in args.platforms:
        label, relative = ARTIFACTS[platform]
        data = (release / relative).read_bytes()
        artifacts[label] = {
            "version": plist_value(SOURCE_PLIST[platform], "CFBundleShortVersionString"),
            "build": plist_value(SOURCE_PLIST[platform], "CFBundleVersion"),
            "path": str((release / relative).relative_to(ROOT)),
            "sha256": hashlib.sha256(data).hexdigest(),
            "bytes": len(data),
        }

    record = {
        "sourceCommit": commit,
        "platforms": [ARTIFACTS[p][0] for p in args.platforms],
        "bundleId": "com.subhajit.mulberry.beta",
        "appStoreConnectId": "6829284245",
        "api": "https://api.mulberry.my/",
        "validation": args.validation,
        "status": args.status,
        "artifacts": artifacts,
    }
    # Keep legacy summary fields only when they describe every artifact.
    for key in ("version", "build"):
        values = {artifact[key] for artifact in artifacts.values()}
        if len(values) == 1:
            record[key] = values.pop()
    (release / "source.json").write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps(record, indent=2))


if __name__ == "__main__":
    main()
