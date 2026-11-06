#!/usr/bin/env python3
"""Mulberry's App Store Connect steps for an iOS release, through the App Store Connect API with
the team key in the ignored secrets/apple/app-store-connect.json.

Run through `uv run --with pyjwt --with cryptography --with requests` (see SKILL.md).

Usage:
  asc.py wait <version> <build> [--timeout-minutes 60]   wait until the uploaded build has processed
  asc.py whats-new <version>                             print the App Store text made from the note
  asc.py prepare <version> <build> [--whats-new-file FILE] [--promo-file FILE] [--manual-release] [--dry-run]
                                                         attach the build to the App Store version,
                                                         set What's New and the promotional text, and
                                                         put it in a draft review submission; the user
                                                         presses Submit for Review
  asc.py promo <version> [--promo-file FILE] [--dry-run] set a version's promotional text (allowed at
                                                         any time, without review)
  asc.py status [<version>]                              the iOS versions' App Store states
"""
import argparse
import json
import pathlib
import re
import sys
import time

import jwt
import requests

ROOT = pathlib.Path(__file__).resolve().parents[5]
CONFIG = ROOT / "secrets/apple/app-store-connect.json"
NOTES = ROOT / "apps/backend/whats-new/ios"
API = "https://api.appstoreconnect.apple.com/v1"
# States in which a version's build and text can still change, and it can be submitted.
EDITABLE = {"PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "REJECTED", "METADATA_REJECTED", "INVALID_BINARY"}


class Client:
    def __init__(self) -> None:
        if not CONFIG.exists():
            sys.exit(f"Missing {CONFIG.relative_to(ROOT)}, the App Store Connect API key settings.")
        config = json.loads(CONFIG.read_text())
        self.app = config["appId"]
        key = (ROOT / config["keyFile"]).read_text()
        now = int(time.time())
        token = jwt.encode({"iss": config["issuerId"], "aud": "appstoreconnect-v1", "iat": now, "exp": now + 1200},
                           key, algorithm="ES256", headers={"kid": config["keyId"], "typ": "JWT"})
        self.session = requests.Session()
        self.session.headers.update({"Authorization": f"Bearer {token}", "Content-Type": "application/json"})

    def call(self, method: str, path: str, action: str, **kwargs) -> dict:
        response = self.session.request(method, f"{API}{path}", timeout=60, **kwargs)
        if response.status_code >= 300:
            try:
                errors = response.json().get("errors", [])
                detail = "; ".join(f"{e.get('title', '')}: {e.get('detail', '')}" for e in errors) or response.text
            except ValueError:
                detail = response.text
            sys.exit(f"{action} failed ({response.status_code}): {detail}")
        return response.json() if response.content else {}

    def build(self, version: str, build: str) -> dict | None:
        data = self.call("GET", "/builds", "finding the build", params={
            "filter[app]": self.app, "filter[version]": build,
            "filter[preReleaseVersion.version]": version, "filter[preReleaseVersion.platform]": "IOS",
            "fields[builds]": "version,processingState,uploadedDate,expired"}).get("data", [])
        return data[0] if data else None

    def versions(self, version: str | None = None) -> list[dict]:
        params = {"filter[platform]": "IOS", "fields[appStoreVersions]": "versionString,appStoreState,releaseType", "limit": 20}
        if version:
            params["filter[versionString]"] = version
        return self.call("GET", f"/apps/{self.app}/appStoreVersions", "reading versions", params=params).get("data", [])


def whats_new_text(version: str) -> str | None:
    """The note's body as App Store plain text: headings and paragraphs as lines, bullets as •."""
    note = NOTES / f"{version}.md"
    if not note.exists():
        return None
    text = note.read_text()
    if text.startswith("---\n"):
        text = text.split("\n---", 1)[1].lstrip("-\n")
    lines = []
    for line in text.splitlines():
        line = re.sub(r"^#{1,6}\s+", "", line.rstrip())
        line = re.sub(r"^[-*+]\s+", "• ", line)
        line = re.sub(r"\*\*(.+?)\*\*|__(.+?)__", lambda m: m.group(1) or m.group(2), line)
        line = re.sub(r"(?<!\w)[*_](.+?)[*_](?!\w)", r"\1", line)
        line = re.sub(r"\[(.+?)\]\((.+?)\)", r"\1 (\2)", line)
        lines.append(line)
    plain = re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip()
    return plain


PROMO_LIMIT = 170


def note_promotional_text(version: str) -> str | None:
    """The `promotional_text` field of the version's What's New note, if it has one."""
    note = NOTES / f"{version}.md"
    if not note.exists() or not note.read_text().startswith("---\n"):
        return None
    front = note.read_text().split("\n---", 1)[0]
    match = re.search(r"^promotional_text:\s*(.+)$", front, re.M)
    return match[1].strip().strip('"') if match else None


def promotional_text(client: "Client", version: str, promo_file: str | None) -> tuple[str, str]:
    """The promotional text for a version, and where it came from: a file, the version's note, or
    the live version (carried over). Stops when there's none, so a release never ships without it."""
    if promo_file:
        text, source = pathlib.Path(promo_file).read_text().strip(), promo_file
    elif note_promotional_text(version):
        text, source = note_promotional_text(version), f"the {version} note"
    else:
        live = [v for v in client.versions() if v["attributes"]["appStoreState"] == "READY_FOR_SALE"]
        text, source = "", ""
        if live:
            for loc in client.call("GET", f"/appStoreVersions/{live[0]['id']}/appStoreVersionLocalizations",
                                   "reading the live version's text").get("data", []):
                if loc["attributes"].get("promotionalText"):
                    text = loc["attributes"]["promotionalText"]
                    source = f"the live version {live[0]['attributes']['versionString']} (carried over)"
                    break
    if not text:
        sys.exit(f"No promotional text for {version}: add promotional_text to its What's New note, or pass --promo-file.")
    if len(text) > PROMO_LIMIT:
        sys.exit(f"Promotional text allows {PROMO_LIMIT} characters; this has {len(text)}.")
    return text, source


def set_localized(client: "Client", version_id: str, attributes: dict) -> None:
    """Sets fields on every language the version's listing has (Mulberry's is English)."""
    for localization in client.call("GET", f"/appStoreVersions/{version_id}/appStoreVersionLocalizations",
                                    "reading the version's languages").get("data", []):
        client.call("PATCH", f"/appStoreVersionLocalizations/{localization['id']}", "setting the listing text", json={
            "data": {"type": "appStoreVersionLocalizations", "id": localization["id"], "attributes": attributes}})


def promo(version: str, promo_file: str | None, dry_run: bool) -> None:
    client = Client()
    found = client.versions(version)
    if not found:
        sys.exit(f"iOS {version} doesn't exist in App Store Connect yet")
    text, source = promotional_text(client, version, promo_file)
    print(f"promotional text ({len(text)}/{PROMO_LIMIT}, from {source}):\n{text}")
    if dry_run:
        print("dry run: nothing changed")
        return
    set_localized(client, found[0]["id"], {"promotionalText": text})
    print(f"set iOS {version}'s promotional text")


def wait(version: str, build: str, timeout_minutes: int) -> None:
    client = Client()
    deadline = time.time() + timeout_minutes * 60
    while True:
        found = client.build(version, build)
        state = found["attributes"]["processingState"] if found else "NOT_UPLOADED_YET"
        if state == "VALID":
            print(f"build {build} of {version} has processed")
            return
        if state in {"FAILED", "INVALID"}:
            sys.exit(f"build {build} of {version} failed processing ({state}); Apple emails the reason")
        if time.time() > deadline:
            sys.exit(f"build {build} of {version} still {state} after {timeout_minutes} minutes")
        print(f"build {build}: {state}, checking again in a minute", flush=True)
        time.sleep(60)


def prepare(version: str, build: str, whats_new_file: str | None, promo_file: str | None, manual_release: bool, dry_run: bool) -> None:
    client = Client()
    text = pathlib.Path(whats_new_file).read_text().strip() if whats_new_file else whats_new_text(version)
    if not text:
        sys.exit(f"No What's New text: there's no note for {version}. Pass --whats-new-file with the text for the App Store.")
    if len(text) > 4000:
        sys.exit(f"The App Store allows 4000 characters of What's New; this has {len(text)}.")

    found = client.build(version, build)
    if not found or found["attributes"]["processingState"] != "VALID":
        sys.exit(f"build {build} of {version} isn't processed in App Store Connect yet; run asc.py wait first")
    release_type = "MANUAL" if manual_release else "AFTER_APPROVAL"

    existing = client.versions(version)
    if existing and existing[0]["attributes"]["appStoreState"] not in EDITABLE:
        state = existing[0]["attributes"]["appStoreState"]
        sys.exit(f"iOS {version} is {state} in App Store Connect, so it can't take a new build or be prepared now")
    print(f"iOS {version}: {'update the existing' if existing else 'create the'} App Store version, attach build {build}, "
          f"draft a review submission for you to submit, "
          f"release {'by hand after approval' if manual_release else 'automatically after approval'}")
    promo_text, promo_source = promotional_text(client, version, promo_file)
    print("--- What's New ---\n" + text + "\n------------------")
    print(f"--- Promotional text ({len(promo_text)}/{PROMO_LIMIT}, from {promo_source}) ---\n" + promo_text + "\n------------------")
    if dry_run:
        print("dry run: nothing changed")
        return

    # The App Store version, with the build and release type.
    if existing:
        version_id = existing[0]["id"]
        client.call("PATCH", f"/appStoreVersions/{version_id}", "setting the release type", json={
            "data": {"type": "appStoreVersions", "id": version_id, "attributes": {"releaseType": release_type}}})
    else:
        version_id = client.call("POST", "/appStoreVersions", "creating the App Store version", json={
            "data": {"type": "appStoreVersions",
                     "attributes": {"platform": "IOS", "versionString": version, "releaseType": release_type},
                     "relationships": {"app": {"data": {"type": "apps", "id": client.app}}}}})["data"]["id"]
    client.call("PATCH", f"/appStoreVersions/{version_id}/relationships/build", "attaching the build",
                json={"data": {"type": "builds", "id": found["id"]}})

    # What's New and the promotional text. A new version doesn't carry promotional text over.
    set_localized(client, version_id, {"whatsNew": text, "promotionalText": promo_text})

    # The review submission, left as a draft for the user to submit: reuse an open one (as after a
    # rejection), or start one.
    open_submissions = client.call("GET", "/reviewSubmissions", "reading review submissions", params={
        "filter[app]": client.app, "filter[platform]": "IOS",
        "filter[state]": "READY_FOR_REVIEW,UNRESOLVED_ISSUES", "include": "items"}).get("data", [])
    if open_submissions:
        submission_id = open_submissions[0]["id"]
        items = client.call("GET", f"/reviewSubmissions/{submission_id}/items", "reading the submission's items",
                            params={"include": "appStoreVersion"}).get("data", [])
        has_version = any((i.get("relationships", {}).get("appStoreVersion", {}).get("data") or {}).get("id") == version_id for i in items)
    else:
        submission_id = client.call("POST", "/reviewSubmissions", "starting a review submission", json={
            "data": {"type": "reviewSubmissions", "attributes": {"platform": "IOS"},
                     "relationships": {"app": {"data": {"type": "apps", "id": client.app}}}}})["data"]["id"]
        has_version = False
    if not has_version:
        client.call("POST", "/reviewSubmissionItems", "adding the version to the submission", json={
            "data": {"type": "reviewSubmissionItems", "relationships": {
                "reviewSubmission": {"data": {"type": "reviewSubmissions", "id": submission_id}},
                "appStoreVersion": {"data": {"type": "appStoreVersions", "id": version_id}}}}})
    print(f"prepared iOS {version} build {build} for App Store review")
    print(f"now press Submit for Review: https://appstoreconnect.apple.com/apps/{client.app}/distribution/reviewsubmissions")
    print(f"  (or on the version page: https://appstoreconnect.apple.com/apps/{client.app}/distribution/ios/version/inflight)")


def status(version: str | None) -> None:
    for v in Client().versions(version):
        a = v["attributes"]
        print(f"iOS {a['versionString']}: {a['appStoreState']} (release {a.get('releaseType', '').lower().replace('_', ' ')})")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("wait"); p.add_argument("version"); p.add_argument("build"); p.add_argument("--timeout-minutes", type=int, default=60)
    p = sub.add_parser("whats-new"); p.add_argument("version")
    p = sub.add_parser("prepare"); p.add_argument("version"); p.add_argument("build")
    p.add_argument("--whats-new-file"); p.add_argument("--promo-file")
    p.add_argument("--manual-release", action="store_true"); p.add_argument("--dry-run", action="store_true")
    p = sub.add_parser("promo"); p.add_argument("version"); p.add_argument("--promo-file"); p.add_argument("--dry-run", action="store_true")
    p = sub.add_parser("status"); p.add_argument("version", nargs="?")
    args = parser.parse_args()
    if args.command == "wait":
        wait(args.version, args.build, args.timeout_minutes)
    elif args.command == "whats-new":
        print(whats_new_text(args.version) or f"(no note for {args.version})")
    elif args.command == "prepare":
        prepare(args.version, args.build, args.whats_new_file, args.promo_file, args.manual_release, args.dry_run)
    elif args.command == "promo":
        promo(args.version, args.promo_file, args.dry_run)
    else:
        status(args.version)


if __name__ == "__main__":
    main()
