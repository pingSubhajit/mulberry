#!/usr/bin/env python3
"""Uploads Mulberry's Android bundle to Google Play and rolls it out, through the Play
Developer API with the service account in the ignored secrets/google-play/.

Run through release.sh, which supplies google-auth and requests with uv.

Usage:
  play.py upload <release-dir> [--track beta] [--notes-file FILE] [--validate-only]
  play.py tracks
"""
import argparse
import json
import pathlib
import re
import sys

from google.auth.transport.requests import AuthorizedSession
from google.oauth2 import service_account

ROOT = pathlib.Path(__file__).resolve().parents[5]
KEY = ROOT / "secrets/google-play/service-account.json"
PACKAGE = "com.subhajit.mulberry"
BASE = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"
UPLOAD = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACKAGE}"


def session() -> AuthorizedSession:
    if not KEY.exists():
        sys.exit(f"Missing {KEY.relative_to(ROOT)}, the Play service account key.")
    credentials = service_account.Credentials.from_service_account_file(
        str(KEY), scopes=["https://www.googleapis.com/auth/androidpublisher"])
    return AuthorizedSession(credentials)


def ok(response, action: str) -> dict:
    if response.status_code >= 300:
        try:
            message = response.json().get("error", {}).get("message", response.text)
        except ValueError:
            message = response.text
        raise SystemExit(f"{action} failed ({response.status_code}): {message}")
    return response.json() if response.content else {}


def tracks(s: AuthorizedSession, edit: str) -> list[dict]:
    return ok(s.get(f"{BASE}/edits/{edit}/tracks"), "reading tracks").get("tracks", [])


def highest_code(all_tracks: list[dict]) -> int:
    codes = [int(c) for t in all_tracks for r in t.get("releases", []) for c in r.get("versionCodes", [])]
    return max(codes, default=0)


def show_tracks() -> None:
    s = session()
    edit = ok(s.post(f"{BASE}/edits"), "opening an edit")["id"]
    try:
        for t in tracks(s, edit):
            releases = [(r.get("name"), r.get("versionCodes"), r.get("status"), r.get("userFraction")) for r in t.get("releases", [])]
            print(t["track"], "->", releases or "empty")
    finally:
        s.delete(f"{BASE}/edits/{edit}")


def upload(release: pathlib.Path, track: str, notes_file: str | None, validate_only: bool = False) -> None:
    props = (ROOT / "apps/mobile/gradle.properties").read_text()
    code = int(re.search(r"^MULBERRY_VERSION_CODE=(\d+)$", props, re.M)[1])
    name = re.search(r"^MULBERRY_VERSION_NAME=(.+)$", props, re.M)[1].strip()
    bundle = release / "Mulberry.aab"
    if not bundle.exists():
        sys.exit(f"No bundle at {bundle}; run release.sh build first.")

    s = session()
    edit = ok(s.post(f"{BASE}/edits"), "opening an edit")["id"]
    committed = False
    try:
        highest = highest_code(tracks(s, edit))
        if code <= highest:
            sys.exit(f"versionCode {code} must exceed {highest}, the highest already on Play. Bump the build.")

        print(f"uploading {name} ({code}) …", flush=True)
        uploaded = ok(s.post(f"{UPLOAD}/edits/{edit}/bundles", params={"uploadType": "media"},
                             data=bundle.read_bytes(), headers={"Content-Type": "application/octet-stream"}, timeout=900),
                      "uploading the bundle")
        if int(uploaded.get("versionCode", 0)) != code:
            sys.exit(f"Play read versionCode {uploaded.get('versionCode')}, expected {code}")

        release_body = {"name": name, "versionCodes": [str(code)], "status": "completed"}
        if notes_file:
            text = pathlib.Path(notes_file).read_text().strip()
            if len(text) > 500:
                sys.exit(f"Play's release notes allow 500 characters; these have {len(text)}.")
            release_body["releaseNotes"] = [{"language": "en-US", "text": text}]
        ok(s.put(f"{BASE}/edits/{edit}/tracks/{track}", json={"track": track, "releases": [release_body]}),
           f"rolling out on the {track} track")

        if validate_only:
            response = s.post(f"{BASE}/edits/{edit}:validate")
            if response.status_code >= 300 and "changesNotSentForReview" in response.text:
                # Everything else checked out; only the automatic send for review is refused.
                print("note: Play won't send changes for review automatically; after a real upload, "
                      "press Send changes for review in Play Console")
            else:
                ok(response, "validating the edit")
            print(f"validate ok: Play accepted {name} ({code}) on {track}; the edit is discarded, nothing was published")
            return

        response = s.post(f"{BASE}/edits/{edit}:commit")
        sent_for_review = True
        if response.status_code >= 300 and "changesNotSentForReview" in response.text:
            # Play wants these changes sent for review by hand in Play Console.
            response = s.post(f"{BASE}/edits/{edit}:commit", params={"changesNotSentForReview": "true"})
            sent_for_review = False
        ok(response, "committing the edit")
        committed = True
    finally:
        if not committed:
            s.delete(f"{BASE}/edits/{edit}")

    result = {"version": name, "build": code, "track": track, "status": "completed", "sentForReview": sent_for_review}
    (release / "play.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
    print("upload ok" + ("" if sent_for_review else
          " (not sent for review: open Play Console → Publishing overview → Send changes for review)"))


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("upload")
    p.add_argument("release_dir"); p.add_argument("--track", default="beta"); p.add_argument("--notes-file")
    p.add_argument("--validate-only", action="store_true", help="upload and validate, then discard the edit")
    sub.add_parser("tracks")
    args = parser.parse_args()
    if args.command == "tracks":
        show_tracks()
    else:
        upload(pathlib.Path(args.release_dir).resolve(), args.track, args.notes_file, args.validate_only)


if __name__ == "__main__":
    main()
