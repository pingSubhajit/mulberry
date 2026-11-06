---
name: ios-release
description: Release Mulberry's iOS app on the App Store. Choose the version, bump the build, commit and push, archive with distribution signing, verify, upload, wait for processing, attach the build to the App Store version with its What's New text, submit it for App Store review, and release it (tag and GitHub Release). Use when the user asks to release iOS, submit iOS for App Store review, or resubmit after a rejection. Not for personal TestFlight builds, which use testflight-release.
---

# Mulberry iOS release

The public iOS release procedure, from build to App Store review. It is separate from `testflight-release`, the user's personal TestFlight skill: never edit that skill or call its scripts from here. This skill has its own copies of the build scripts.

Run from the release checkout's repository root. Scripts are in `scripts/` next to this file, below `$S`; the shared ledger script is `$P`:

```sh
S=.agents/skills/mulberry-skills/ios-release/scripts
P=.agents/skills/mulberry-skills/public-release/scripts
asc() { (cd $S && uv run -q --no-project --with pyjwt --with cryptography --with requests python -I asc.py "$@"); }
```

## Fixed facts

| | |
| --- | --- |
| App Store Connect | app `6829284245`, bundle `com.subhajit.mulberry.beta`, team `HMJX9736KN` |
| API key | team key `P622AH94VK`, settings in `secrets/apple/app-store-connect.json`, key in `secrets/apple/AuthKey_P622AH94VK.p8` |
| Version | `CFBundleShortVersionString` and `CFBundleVersion` in `apps/ios/Configuration/Info.plist` and `Widgets-Info.plist`; app and widget always match |
| Signing and upload | Xcode's signed-in account, automatic signing, Apple Distribution |
| Artifacts | ignored `temp/apple-release/<YYYY-MM-DD>-ios-<version>-<build>/` |

**Secrets.** Everything under `secrets/` stays there. Never print, copy or commit the key or its settings.

## Steps

### 1. Pre-flight

```sh
git fetch -q origin && git status -sb && git log --oneline -1
```

Build only committed work based on a freshly fetched `origin/main`. Never include uncommitted work the user hasn't asked to ship; if it seems needed, stop and ask. Check that `apps/ios/.build/device/SourcePackages` exists; if it doesn't, resolve it once:

```sh
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild -resolvePackageDependencies -project apps/ios/Mulberry.xcodeproj -scheme 'Mulberry Production' -clonedSourcePackagesDirPath apps/ios/.build/device/SourcePackages
```

### 2. Version

```sh
$P/releases.py next-version ios
asc status
```

- If a version is in progress (not released yet), keep it. Otherwise tell the user the suggested version and why, and use the one they confirm (version rule in `public-release`).
- The App Store needs "What's New in This Version" text. It comes from the version's What's New note, `apps/backend/whats-new/ios/<version>.md`, if there is one (`asc whats-new <version>` shows the text). Not every version has a note; without one, ask the user for the App Store text and save it to a file for step 6.

### 3. Bump, commit and push

```sh
$S/bump-build.py ios --dry-run
$S/bump-build.py ios [--version X.Y.Z]
git add apps/ios/Configuration/Info.plist apps/ios/Configuration/Widgets-Info.plist
git commit -m "chore(ios): prepare iOS <version> build <N>" -m "Co-Authored-By: <attribution from the current system reminder>"
git push origin main
```

The build number must exceed every earlier upload, including the user's TestFlight builds; `asc status` and App Store Connect show the highest. The user's release request authorizes this commit and push.

### 4. Archive, verify, upload

```sh
R=temp/apple-release/$(date +%F)-ios-<version>-<build>
$S/release.sh archive "$R" ios      # several minutes: run in the background
$S/release.sh verify "$R" ios       # every line ok, then ALL CHECKS PASSED
$S/release.sh upload "$R" ios       # Upload succeeded
```

If verification fails, fix and rebuild; never upload a failed export.

### 5. Record the build

```sh
$P/releases.py record-build ios --version <version> --build <N> --channel app-store
git add docs/releases.json docs/releases.md
git commit -m "chore(release): record iOS <version> build <N>" -m "Co-Authored-By: <attribution from the current system reminder>"
git push origin main
```

### 6. Wait for processing, then submit for review

```sh
asc wait <version> <N>                               # usually 10–30 minutes: run in the background
asc submit <version> <N> --dry-run [--whats-new-file FILE]
asc submit <version> <N> [--whats-new-file FILE]
```

- It creates the App Store version (or reuses it while it's still editable, as after a rejection), attaches the build, sets What's New, and submits for review.
- By default the app goes live automatically once approved. `--manual-release` holds it for the user to release in App Store Connect; use it only when they ask.
- If Apple refuses the submission (a missing answer or document), the script prints Apple's reason. Relay it; the user fixes it in App Store Connect, then rerun `asc submit`.

### 7. Release

The version is now sent for public review, so release it:

```sh
$P/releases.py publish ios <version> --dry-run
$P/releases.py publish ios <version>
```

That tags the build, publishes its GitHub Release and dates its What's New note.

### 8. Report

Keep it short: version, build, source commit, verification passed, upload and submission succeeded, the tag and the GitHub Release link. Apple's review usually takes about a day; once approved, the version goes live on its own (unless `--manual-release`).

## Submitting a build that's already uploaded

If the user wants to submit a build they already have in App Store Connect, such as one they tested on TestFlight, skip steps 3–5. It must be in the ledger so the tag lands on its commit: if `releases.py show ios` doesn't list it, record it first with the commit it was built from (a `testflight-release` build's commit is `sourceCommit` in its `temp/apple-release/*/source.json`):

```sh
$P/releases.py record-build ios --version <version> --build <N> --channel testflight --commit <sha> --date <upload day>
```

Then commit and push the ledger, and continue at step 6.

## After a rejection

Keep the version. Fix what Apple asked for, then repeat steps 1 and 3–6 with the next build number (a new upload of the same version), and move the release to it:

```sh
$P/releases.py publish ios <version> --move
```

If the rejection only needs a reply or metadata change, no new build is needed: the user answers in App Store Connect and resubmits there, and nothing here changes.

## Checking App Store Connect

```sh
asc status            # every iOS version's state
asc status 1.1.0
```

`liveBuild` for the update prompts is separate: set it with the `app-versions` subskill once a version is live, typically when the user wants to nudge or force updates.
