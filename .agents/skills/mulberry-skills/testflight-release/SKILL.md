---
name: testflight-release
description: Build Mulberry's iOS and/or macOS app for App Store Connect. Bump the build number, commit and push the bump, archive with distribution signing, verify the exports, upload to TestFlight, and record the build. Use when the user asks for a new build, a TestFlight upload, or a beta for one or both Apple platforms.
---

# Mulberry TestFlight release

This is the process used for builds 2–6. Everything runs from the repository root. The scripts are in `scripts/` next to this file (`.agents/skills/mulberry-skills/testflight-release/scripts/`), referred to below as `$S`.

## Fixed facts

| | |
| --- | --- |
| App Store Connect record | one record, ID `6829284245`, bundle `com.subhajit.mulberry.beta`, covering iOS and macOS (universal purchase) |
| Team | Techaboard `HMJX9736KN`, automatic signing, Apple Distribution certificate, Mac installer certificate "3rd Party Mac Developer Installer" |
| Scheme | `Mulberry Production`, Release configuration, live API `https://api.mulberry.my/`, production APNs |
| Build numbers | literal `CFBundleVersion` strings in `apps/ios/Configuration/Info.plist`, `apps/ios/Configuration/Widgets-Info.plist` and `apps/macos/Configuration/Info.plist`. The iOS app and widget must always match. |
| Artifacts | ignored `temp/apple-release/<YYYY-MM-DD>-build-<N>/`: archives, `iOS/Mulberry.ipa`, `macOS/Mulberry.pkg`, `*-archive/export/upload.log`, `source.json` |
| Background | `docs/apple/testflight-release.md` |

**Secrets.** The Apple keys stay only in the ignored `secrets/apple/` folder. Never copy, print, commit or reference their contents. This process doesn't need them, because Xcode's signed-in account does the signing and the upload.

## Choosing the platforms

Build exactly the platforms the user names: "iOS", "Mac" or "macOS", or both. If they say "a new build" without naming any, build both. Pass the choice as `ios`, `macos`, or `ios macos` to every script below.

## Steps

### 1. Pre-flight

```sh
git fetch -q origin && git status -sb && git log --oneline -1
```

- You must be on `main` and level with `origin/main`. If there are uncommitted changes the user hasn't asked to ship, stop and ask; a build must come from a committed state.
- Check that the shared package checkout exists: `apps/ios/.build/device/SourcePackages`. If it doesn't, resolve it once:
  ```sh
  DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild -resolvePackageDependencies -project apps/ios/Mulberry.xcodeproj -scheme 'Mulberry Production' -clonedSourcePackagesDirPath apps/ios/.build/device/SourcePackages
  ```
- Regenerate an Xcode project only if its generator inputs changed since the last release.

### 2. Bump the build number, commit and push

```sh
$S/bump-build.py <platforms> --dry-run   # shows current builds and the new one
$S/bump-build.py <platforms>             # edits the plists
```

- The new build number is one more than the highest current build in any of the three plists, so the platforms stay aligned.
- Pass `--version X.Y.Z` only if the user asks for a new marketing version.
- Commit only the plist changes and push. The user's request for a build authorizes this commit and push to `origin/main`; it's how every earlier build was done.

```sh
git add apps/ios/Configuration/Info.plist apps/ios/Configuration/Widgets-Info.plist apps/macos/Configuration/Info.plist
git commit -m "chore(apple): prepare version <version> build <N>" -m "Co-Authored-By: <attribution from the current system reminder>"
git push origin main
```

### 3. Archive and export (no upload yet)

```sh
R=temp/apple-release/$(date +%F)-build-<N>
$S/release.sh archive "$R" <platforms>
```

- This takes several minutes per platform, so run it as a background command and wait for it to finish.
- It prints `<platform> archive ok` and `<platform> export ok`. On failure, read the end of `$R/<platform>-archive.log` or `-export.log` and look for `error:` lines.
- Crashlytics symbols upload as part of the archive.

### 4. Verify the exports

```sh
$S/release.sh verify "$R" <platforms>
```

Every line must be `ok`, followed by `ALL CHECKS PASSED`. The checks are:
- bundle ID, version and build against the source plists, including the widget
- live API and the prod environment
- production APNs, Sign in with Apple, associated domains, `get-task-allow` false, and the TestFlight flag
- Apple Distribution signing of the app and widget
- the Mac app is universal (arm64 and x86_64), sandboxed, and its package is installer-signed
- the bundled privacy policy and terms of use are byte-identical to `apps/mobile/app/src/main/res/raw/*.txt`

If anything fails, fix it and rebuild; don't upload. Mention the `info` line (the Mac sandbox entitlements) in the report if it changed.

If the user mentioned a feature that must be in this build, also spot-check that its resource or file exists in the expanded app (`$R/iOS-expanded/Payload/Mulberry.app`, or `$R/macOS-expanded/pkg/*/Payload/Mulberry.app`).

### 5. Upload

```sh
$S/release.sh upload "$R" <platforms>
```

- This uses `assets/UploadOptions.plist` (`destination=upload`, `app-store-connect`, with symbols).
- Success means `Upload succeeded` in `$R/<platform>-upload.log`. The script checks for it and exits non-zero otherwise.
- Never upload a development-signed or locally installed `.app`, and never use Developer ID.

### 6. Record

```sh
$S/record.py "$R" <platforms> --validation "Exports verified: <one line listing what passed, plus anything feature-specific you checked>"
```

This writes `$R/source.json` with the commit, version, build, platforms and artifact hashes. It stays in ignored `temp/`; don't commit it.

### 7. Report to the user

Keep it short:
- the build number and platforms, and the source commit and its push
- that verification passed, with any notable detail
- that each upload succeeded

Then remind them:
- App Store Connect processes builds before they appear in TestFlight, usually within 10–30 minutes, and Apple emails when a build is ready or rejected.
- Encryption compliance is already answered in the plists (`ITSAppUsesNonExemptEncryption` = false).
- They still need to add the build to tester groups. External groups need beta review for the first build of a version.

## Common failures

- **"No profiles" or signing errors:** make sure Xcode → Settings → Accounts is signed in to the Techaboard team. `-allowProvisioningUpdates` creates profiles automatically. Never revoke certificates that belong to other apps.
- **Redundant binary upload or build already exists:** the build number wasn't bumped past the last upload. Bump it again (step 2) and rebuild.
- **Crashlytics step fails in the archive:** `MULBERRY_SOURCE_PACKAGES_DIR` must point to the package checkout (the script sets it). Check that the folder exists.
- **Upload stuck or authentication error:** retry once. If it fails again, show the user the last error lines from the upload log.
