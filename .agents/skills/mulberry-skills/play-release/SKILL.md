---
name: play-release
description: Build Mulberry's Android app for Google Play. Choose the version, bump the build, commit and push the bump, build the signed production bundle, verify it, upload it to Play's open testing track and roll it out to everyone, and record the build. Use when the user asks for a new Android build, an Android release, or a Play upload.
---

# Mulberry Play release

Mulberry's public Android channel is Play's **open testing** track (`beta`); the production track is unused. Every upload goes to open testing and is rolled out to everyone, so once Play has reviewed it, it is public, and the upload itself counts as the release. There are no internal or closed test uploads.

Run from the release checkout's repository root. Scripts are in `scripts/` next to this file, below `$S`; the shared ledger script is `$P`:

```sh
S=.agents/skills/mulberry-skills/play-release/scripts
P=.agents/skills/mulberry-skills/public-release/scripts
```

## Fixed facts

| | |
| --- | --- |
| Package | `com.subhajit.mulberry` (the `prod` flavor; `dev` adds `.dev`) |
| Version | `MULBERRY_VERSION_NAME` and `MULBERRY_VERSION_CODE` in `apps/mobile/gradle.properties` |
| Upload key | `secrets/android/mulberry-upload.jks` (alias `mulberry-upload`), signing values in `secrets/android/signing.env`. The original is kept in `~/keys/`. Play App Signing holds the app signing key. |
| Play API | service account `mulberry-play-release@mulberry-494003.iam.gserviceaccount.com`, key in `secrets/google-play/service-account.json` |
| Firebase | `apps/mobile/app/src/prod/google-services.json` (ignored; client config only) |
| API | `https://api.mulberry.my/`, passed to the build by `release.sh` |
| Artifacts | ignored `temp/android-release/<YYYY-MM-DD>-android-<version>-<build>/`: the bundle, its manifest and BuildConfig, logs, `play.json` |

**Secrets.** Everything under `secrets/` stays there. Never print, copy or commit the keystore, its passwords or the service account key.

If `google-services.json` is missing, fetch the `com.subhajit.mulberry` app's config from the Firebase Management API with the `FIREBASE_SERVICE_ACCOUNT_JSON` credentials in the repository's `.env` (`projects/<id>/androidApps`, then `<app>/config`, whose `configFileContents` is the file in base64).

## Steps

### 1. Pre-flight

```sh
git fetch -q origin && git status -sb && git log --oneline -1
```

The same rules as `testflight-release`: build only committed work based on a fresh `origin/main`, and stop and ask if uncommitted work seems needed.

### 2. Version and build number

```sh
$P/releases.py next-version android
$S/bump.py --dry-run
```

- If `next-version` says a version is in progress, keep it. Otherwise tell the user the suggested version and why, and use the one they confirm (see the version rule in `public-release`).
- Check for the version's What's New note, `apps/backend/whats-new/android/<version>.md`. Not every version has one. If there's none, mention it once and carry on; don't stop. A note for this version must be committed before the build reaches people, or the app marks the version seen and never shows it.
- Bump, commit only `gradle.properties`, and push. The user's request for a build authorizes this commit and push.

```sh
$S/bump.py [--version X.Y.Z]
git add apps/mobile/gradle.properties
git commit -m "chore(mobile): prepare Android <version> build <N>" -m "Co-Authored-By: <attribution from the current system reminder>"
git push origin main
```

### 3. Build

```sh
R=temp/android-release/$(date +%F)-android-<version>-<build>
$S/release.sh build "$R"
```

This takes a few minutes, so run it as a background command and wait for it. It prints `android build ok`; on failure read the end of `$R/android-build.log`.

### 4. Verify

```sh
$S/release.sh verify "$R"
```

Every line must be `ok`, then `ALL CHECKS PASSED`: the signature verifies and is the upload key's, the package, versionCode and versionName match `gradle.properties`, the bundle isn't debuggable and allows no cleartext traffic, and it's a release build of the `prod` environment on the live API with the debug menu off. Fix anything that fails and rebuild; never upload a failed bundle.

### 5. Upload and roll out

```sh
$S/release.sh upload "$R" [--notes-file FILE]
```

- It refuses a versionCode that isn't above the highest already on Play, uploads the bundle, rolls it out to everyone on the open testing track, and commits the change, which sends it for Google's review.
- `--notes-file` sets Play's "What's new in this version" text (en-US, 500 characters at most). Use it only when the user gives that text.
- Play currently refuses to send Mulberry's changes for review automatically (tested 2026-11-06), so the script commits them without review and says so. Tell the user to press **Send changes for review** in Play Console → Publishing overview; the rollout waits for that. If Play starts accepting automatic review, the script sends it straight away and says nothing.
- `--validate-only` uploads into a Play edit, lets Play check the bundle and the rollout, and discards the edit: nothing is published. Use it to test the pipeline, with a bundle whose versionCode is above everything on Play.

### 6. Record

```sh
$P/releases.py record-build android --version <version> --build <N> --channel play-beta
git add docs/releases.json docs/releases.md
git commit -m "chore(release): record Android <version> build <N>" -m "Co-Authored-By: <attribution from the current system reminder>"
git push origin main
```

### 7. Report

Keep it short: the version, build and source commit, that verification passed, and that the upload was rolled out to everyone on open testing.

Then remind the user:
- Press **Send changes for review** in Play Console → Publishing overview (unless the script said it was sent automatically).
- Google reviews the update before people get it, usually within a few hours, sometimes a few days. Play Console shows the review state.

### 8. Release

The upload is the submission, so release the version straight away with the `public-release` subskill:

```sh
$P/releases.py publish android <version>
```

That tags the build, publishes its GitHub Release and dates its What's New note. If the version was already released and this build replaces a rejected one, use `--move` instead. Report the tag and the release link with the rest.

## Checking Play

```sh
(cd $S && uv run -q --no-project --with google-auth --with requests python -I play.py tracks)
```

Lists every track's releases. A permission error means the service account lost its Play Console access (Users and permissions → the service account → Mulberry: "View app information" and "Release to production, exclude devices, and use Play App Signing").
