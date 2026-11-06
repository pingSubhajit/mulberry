#!/usr/bin/env python3
"""Mulberry's release ledger, version suggestions and releases, for iOS, macOS and Android.

Builds and releases are two things. A build is anything uploaded: a TestFlight build or a Play
upload, and a version usually has several. A version is released when it's sent for public review
(App Store review, or Play's review of the open testing rollout). Only then does it get a git tag
(`ios-v1.1.0`) and a GitHub Release. If that build is rejected and a newer build of the same
version goes in, `publish --move` points the tag and release at it.

The ledger is docs/releases.json (read by the scripts) and docs/releases.md (rendered from it,
for people). Both are committed.

Usage:
  releases.py show [platform]
  releases.py next-version <platform>
  releases.py record-build <platform> --version X.Y.Z --build N --channel testflight|play-beta [--commit SHA] [--date YYYY-MM-DD]
  releases.py publish <platform> <version> [--build N] [--date YYYY-MM-DD] [--move] [--dry-run]
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
from typing import TypedDict

ROOT = pathlib.Path(__file__).resolve().parents[5]
LEDGER_JSON = ROOT / "docs/releases.json"
LEDGER_MD = ROOT / "docs/releases.md"
NOTES = ROOT / "apps/backend/whats-new"
API = "https://api.mulberry.my"  # hero images in GitHub Release text


class Platform(TypedDict):
    label: str
    paths: list[str]


PLATFORMS: dict[str, Platform] = {
    "ios": {"label": "iOS", "paths": ["apps/ios", "packages/apple"]},
    "macos": {"label": "macOS", "paths": ["apps/macos", "packages/apple"]},
    "android": {"label": "Android", "paths": ["apps/mobile"]},
}


class Build(TypedDict, total=False):
    version: str
    build: int
    commit: str
    channel: str
    uploadedAt: str
    releasedAt: str
    tag: str


Ledger = dict[str, list[Build]]


# ---- helpers ----------------------------------------------------------------------------------

def run(*args: str, check: bool = True) -> str:
    result = subprocess.run(args, cwd=ROOT, text=True, capture_output=True)
    if check and result.returncode != 0:
        sys.exit(f"{' '.join(args)} failed:\n{result.stderr.strip()}")
    return result.stdout.strip()


def semver(value: str) -> tuple[int, int, int]:
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", value)
    if not match:
        sys.exit(f"{value!r} is not MAJOR.MINOR.PATCH")
    return int(match[1]), int(match[2]), int(match[3])


def today() -> str:
    return dt.date.today().isoformat()


def tag_name(platform: str, version: str) -> str:
    return f"{platform}-v{version}"


def current_version(platform: str) -> tuple[str, int]:
    """The marketing version and build number in the repository now."""
    if platform == "android":
        text = (ROOT / "apps/mobile/gradle.properties").read_text()
        name = re.search(r"^MULBERRY_VERSION_NAME=(.+)$", text, re.M)
        code = re.search(r"^MULBERRY_VERSION_CODE=(\d+)$", text, re.M)
        if not name or not code:
            sys.exit("apps/mobile/gradle.properties lacks MULBERRY_VERSION_NAME or MULBERRY_VERSION_CODE")
        return name[1].strip(), int(code[1])
    plist = ROOT / f"apps/{platform}/Configuration/Info.plist"
    read = lambda key: run("/usr/libexec/PlistBuddy", "-c", f"Print {key}", str(plist))
    return read("CFBundleShortVersionString"), int(read("CFBundleVersion"))


def last_release(ledger: Ledger, platform: str) -> Build | None:
    released = [b for b in ledger.get(platform, []) if b.get("releasedAt")]
    return max(released, key=lambda b: semver(b["version"]), default=None)


def commits_since(platform: str, ref: str | None, end: str = "HEAD") -> list[str]:
    span = f"{ref}..{end}" if ref else end
    out = run("git", "log", "--format=%h %s", span, "--", *PLATFORMS[platform]["paths"])
    return [line for line in out.splitlines() if line]


# ---- ledger -----------------------------------------------------------------------------------

def load() -> Ledger:
    if not LEDGER_JSON.exists():
        return {platform: [] for platform in PLATFORMS}
    data: Ledger = json.loads(LEDGER_JSON.read_text())
    for platform in PLATFORMS:
        data.setdefault(platform, [])
    return data


def save(ledger: Ledger) -> None:
    for builds in ledger.values():
        builds.sort(key=lambda b: (semver(b["version"]), b["build"]))
    LEDGER_JSON.write_text(json.dumps(ledger, indent=2) + "\n")
    LEDGER_MD.write_text(render(ledger))


def render(ledger: Ledger) -> str:
    lines = [
        "# Releases",
        "",
        "Every uploaded build of each app, and which went public. Generated from `releases.json` by",
        "`.agents/skills/mulberry-skills/public-release/scripts/releases.py`; edit through the script,",
        "not by hand.",
        "",
        "A build is any TestFlight or Play upload. A release is the build sent for public review;",
        "only releases get a git tag and a GitHub Release.",
    ]
    for platform, info in PLATFORMS.items():
        lines += ["", f"## {info['label']}", "", "| Version | Build | Commit | Uploaded | Channel | Released |", "| --- | --- | --- | --- | --- | --- |"]
        builds = sorted(ledger.get(platform, []), key=lambda b: (semver(b["version"]), b["build"]), reverse=True)
        if not builds:
            lines.append("| | | | | | |")
        for b in builds:
            public = f"{b['releasedAt']} (`{b['tag']}`)" if b.get("releasedAt") else ""
            lines.append(f"| {b['version']} | {b['build']} | `{b['commit'][:8]}` | {b.get('uploadedAt', '')} | {b.get('channel', '')} | {public} |")
    return "\n".join(lines) + "\n"


# ---- commands ---------------------------------------------------------------------------------

def show(platform: str | None) -> None:
    ledger = load()
    for name in [platform] if platform else list(PLATFORMS):
        version, build = current_version(name)
        last = last_release(ledger, name)
        uploads = [b for b in ledger[name] if b["version"] == version]
        print(f"{PLATFORMS[name]['label']}: repository at {version} build {build}; "
              f"last public release {last['version'] + ' build ' + str(last['build']) if last else 'none recorded'}; "
              f"{len(uploads)} build(s) of {version} uploaded")


def next_version(platform: str) -> None:
    ledger = load()
    version, build = current_version(platform)
    last = last_release(ledger, platform)
    if not last:
        print(json.dumps({"repository": version, "lastRelease": None,
                          "advice": "No public release is recorded for this platform. If it has never been public, keep "
                                    "the repository's version until its first release. If a version is live, record it "
                                    "first (releases.py publish, with --date), then ask again."}, indent=2))
        return
    if semver(version) > semver(last["version"]):
        uploads = [b["build"] for b in ledger[platform] if b["version"] == version]
        print(json.dumps({"inProgress": version, "lastRelease": last["version"], "buildsUploaded": uploads,
                          "advice": f"{version} is not public yet: keep it and upload the next build."}, indent=2))
        return
    base = last["version"]
    commits = commits_since(platform, last.get("tag"))
    feature = any(re.match(r"^\w+ feat(\(|!|:)", c) for c in commits)
    major, minor, patch = semver(base)
    suggestion = f"{major}.{minor + 1}.0" if feature else f"{major}.{minor}.{patch + 1}"
    print(json.dumps({"lastRelease": base, "suggestion": suggestion,
                      "reason": "new features since the last release" if feature else "only fixes since the last release",
                      "commits": commits[:40]}, indent=2))


def record_build(platform: str, version: str, build: int, channel: str, commit: str | None, date: str | None) -> None:
    semver(version)
    ledger = load()
    if any(b["build"] == build for b in ledger[platform]):
        sys.exit(f"{platform} build {build} is already in the ledger")
    full = run("git", "rev-parse", commit or "HEAD")
    ledger[platform].append({"version": version, "build": build, "commit": full, "channel": channel, "uploadedAt": date or today()})
    save(ledger)
    print(f"recorded {PLATFORMS[platform]['label']} {version} build {build} ({full[:8]}, {channel})")


def note_path(platform: str, version: str) -> pathlib.Path:
    return NOTES / platform / f"{version}.md"


def set_release_date(path: pathlib.Path, date: str) -> bool:
    """Writes released_at into the note's frontmatter. True if the file changed."""
    text = path.read_text()
    if not text.startswith("---\n"):
        sys.exit(f"{path} has no frontmatter")
    end = text.index("\n---", 4)
    head, rest = text[:end], text[end:]
    if re.search(r"^released_at:.*$", head, re.M):
        new_head = re.sub(r"^released_at:.*$", f"released_at: {date}", head, flags=re.M)
    else:
        new_head = re.sub(r"^(version:.*)$", rf"\1\nreleased_at: {date}", head, count=1, flags=re.M)
        if new_head == head:
            new_head = head + f"\nreleased_at: {date}"
    if new_head == head:
        return False
    path.write_text(new_head + rest)
    return True


def release_body(platform: str, version: str, previous_tag: str | None, build: Build) -> str:
    note = note_path(platform, version)
    if note.exists():
        text = note.read_text()
        front = text.split("\n---", 1)[0]
        body = text.split("\n---", 1)[1].lstrip("-\n") if text.startswith("---\n") else text
        title = re.search(r"^title:\s*(.+)$", front, re.M)
        hero = re.search(r"^hero_image:\s*(.+)$", front, re.M)
        parts = []
        if title:
            parts.append(f"# {title[1].strip()}")
        if hero:
            url = hero[1].strip()
            parts.append(f"![]({url if url.startswith('http') else API + url})")
        parts.append(body.strip())
        return "\n\n".join(parts) + f"\n\n---\nBuild {build['build']}, from `{build['commit'][:8]}`.\n"
    footer = f"\n\n---\nBuild {build['build']}, from `{build['commit'][:8]}`.\n"
    if not previous_tag:
        return f"Mulberry for {PLATFORMS[platform]['label']} {version}, the first release recorded with a tag." + footer
    commits = [c for c in commits_since(platform, previous_tag, build["commit"]) if re.match(r"^\w+ (feat|fix|perf)(\(|!|:)", c)]
    listed = "\n".join(f"- {c}" for c in commits) or "- Maintenance and internal changes."
    return f"Changes since `{previous_tag}`:\n\n{listed}" + footer


def publish(platform: str, version: str, build_number: int | None, date: str | None, dry_run: bool, move: bool) -> None:
    """Releases a version when it's sent for public review: ledger, note date, tag, GitHub Release.
    With move, points an existing release at a newer build of the same version (after a rejection)."""
    semver(version)
    date = date or today()
    ledger = load()
    builds = [b for b in ledger[platform] if b["version"] == version]
    candidates = [b for b in builds if build_number is None or b["build"] == build_number]
    if not candidates:
        sys.exit(f"no uploaded {platform} {version}{' build ' + str(build_number) if build_number else ''} in the ledger")
    build = max(candidates, key=lambda b: b["build"])
    released = next((b for b in builds if b.get("releasedAt")), None)
    tag = tag_name(platform, version)
    tag_exists = bool(run("git", "tag", "-l", tag) or run("git", "ls-remote", "--tags", "origin", tag))
    label = PLATFORMS[platform]["label"]

    if move:
        if not released or not tag_exists:
            sys.exit(f"{platform} {version} isn't released yet; run publish without --move")
        if build["build"] <= released["build"]:
            sys.exit(f"{tag} is on build {released['build']}; --move needs a newer build of {version}")
    elif released or tag_exists:
        sys.exit(f"{platform} {version} is already released as {tag} (build {released['build'] if released else '?'}). "
                 "If that build was rejected and a newer one resubmitted, run with --move.")

    previous = last_release({platform: [b for b in ledger[platform] if b["version"] != version]}, platform)
    previous_tag = previous.get("tag") if previous else None
    body = release_body(platform, version, previous_tag, build)
    note = note_path(platform, version)
    action = f"moves {tag} from build {released['build']} to" if move else f"releases {label} {version} as {tag}:"
    print(f"{action} build {build['build']} ({build['commit'][:8]}), {date}")

    dirty = run("git", "status", "--porcelain")
    if dry_run:
        if dirty:
            print("! the working tree has uncommitted changes; a real run stops on them")
        if not move:
            print(f"would {'date ' + str(note.relative_to(ROOT)) if note.exists() else 'find no note for ' + version}")
        print("--- GitHub Release ---\n" + body)
        return
    if dirty:
        sys.exit("commit or stash the working tree first: the release commits the ledger and note")

    # 1. The ledger, and for a first release the note's date, which puts it in What's New history.
    #    Committed and pushed; a pushed note deploys the backend.
    changed = [str(note)] if not move and note.exists() and set_release_date(note, date) else []
    if move:
        released.pop("releasedAt", None); released.pop("tag", None)
    build["releasedAt"], build["tag"] = date, tag
    save(ledger)
    run("git", "add", str(LEDGER_JSON), str(LEDGER_MD), *changed)
    subject = f"chore(release): move {label} {version} to build {build['build']}" if move else f"chore(release): release {label} {version}"
    run("git", "commit", "-m", subject, "-m", "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>")
    run("git", "push", "origin", "HEAD")

    # 2. The tag on the commit the build came from, and its GitHub Release. A moved tag keeps its
    #    release, which GitHub ties to the tag's name.
    message = f"Mulberry for {label} {version} (build {build['build']})"
    with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as handle:
        handle.write(body)
    if move:
        run("git", "tag", "-f", "-a", tag, build["commit"], "-m", message)
        run("git", "push", "-f", "origin", tag)
        run("gh", "release", "edit", tag, "--notes-file", handle.name)
    else:
        run("git", "tag", "-a", tag, build["commit"], "-m", message)
        run("git", "push", "origin", tag)
        run("gh", "release", "create", tag, "--verify-tag", "--title", f"Mulberry for {label} {version}",
            "--notes-file", handle.name, "--latest=false")
    os.unlink(handle.name)
    print(f"{'moved' if move else 'released'} {tag}: {run('gh', 'release', 'view', tag, '--json', 'url', '--jq', '.url')}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("show"); p.add_argument("platform", nargs="?", choices=PLATFORMS)
    p = sub.add_parser("next-version"); p.add_argument("platform", choices=PLATFORMS)
    p = sub.add_parser("record-build")
    p.add_argument("platform", choices=PLATFORMS); p.add_argument("--version", required=True)
    p.add_argument("--build", type=int, required=True); p.add_argument("--channel", required=True, choices=["testflight", "play-beta"])
    p.add_argument("--commit"); p.add_argument("--date")
    p = sub.add_parser("publish")
    p.add_argument("platform", choices=PLATFORMS); p.add_argument("version")
    p.add_argument("--build", type=int); p.add_argument("--date"); p.add_argument("--dry-run", action="store_true")
    p.add_argument("--move", action="store_true", help="point the version's release at a newer build, after a rejection")
    args = parser.parse_args()

    if args.command == "show":
        show(args.platform)
    elif args.command == "next-version":
        next_version(args.platform)
    elif args.command == "record-build":
        record_build(args.platform, args.version, args.build, args.channel, args.commit, args.date)
    else:
        publish(args.platform, args.version, args.build, args.date, args.dry_run, args.move)


if __name__ == "__main__":
    main()
