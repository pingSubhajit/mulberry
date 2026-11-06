---
name: testflight-release
description: Build Mulberry's iOS and/or macOS app for App Store Connect. Bump the build number, commit and push the bump, archive with distribution signing, verify the exports, upload to TestFlight, and record the build. Use when the user asks for a new build, a TestFlight upload, or a beta for one or both Apple platforms.
---

# Mulberry TestFlight release

Run from the release checkout's repository root. The scripts are in `scripts/` next to this file (`.agents/skills/mulberry-skills/testflight-release/scripts/`), referred to below as `$S`. The shared release ledger script is `$P`, `.agents/skills/mulberry-skills/public-release/scripts/`.

A TestFlight build is a **build**, not a release: a version usually gets several before one goes public. Builds are recorded in the ledger (`docs/releases.md`) and never tagged. When a version goes live in the App Store, the `public-release` subskill tags it and publishes its GitHub Release.

## Fixed facts

| | |
| --- | --- |
| App Store Connect record | one record, ID `6829284245`, bundle `com.subhajit.mulberry.beta`, covering iOS and macOS (universal purchase) |
| Team | Techaboard `HMJX9736KN`, automatic signing, Apple Distribution certificate, Mac installer certificate "3rd Party Mac Developer Installer" |
| Scheme | `Mulberry Production`, Release configuration, live API `https://api.mulberry.my/`, production APNs |
| Build numbers | literal `CFBundleVersion` strings in `apps/ios/Configuration/Info.plist`, `apps/ios/Configuration/Widgets-Info.plist`, `apps/macos/Configuration/Info.plist` and `apps/macos/Configuration/Widgets-Info.plist`. Each app and its widget must always match. |
| Artifacts | ignored `temp/apple-release/<YYYY-MM-DD>-ios-<version>-<build>/` (or `macos`, or a unique joint-release name): archives, packages, logs and `source.json` |
| Background | `docs/apple/testflight-release.md` |

**Secrets.** The Apple keys stay only in the ignored `secrets/apple/` folder. Never copy, print, commit or reference their contents. This process doesn't need them, because Xcode's signed-in account does the signing and the upload.

## Choosing the platforms

Build exactly the platforms the user names: "iOS", "Mac" or "macOS", or both. If they say "a new build" without naming any, build both. Pass the choice as `ios`, `macos`, or `ios macos` to every script below.

iOS and macOS have independent marketing versions and build counters. Each app and its own widget must match both values; the two platforms need not match. Never change or upload the other platform just to align numbers. A platform already in App Review stays untouched unless the user asks to replace it. Read the requested App Store submission version before building; set that platform's marketing version to it. Do not assume TestFlight availability means a build can be attached to a later App Store update. Apple's build picker excludes uploads made before the previous App Store release once it is Ready for Distribution.

## Steps

### 1. Pre-flight

```sh
git fetch -q origin && git status -sb && git log --oneline -1
```

- The release source must be committed and based on freshly fetched `origin/main`. Never include uncommitted work the user has not asked to ship. If the main workspace is dirty, use a clean detached release worktree at `origin/main`, applying only the requested release changes and committing them before archive. Keep the user's files intact. Push the release commit to `main` without force; stop if the remote advanced and resolve before uploading. If uncommitted work is required for the requested release and its scope is unclear, stop and ask.
- In a release worktree, use existing main-project caches with `MULBERRY_RELEASE_CACHE_ROOT=/absolute/path/to/main/project`. Do not install dependencies or run dev servers there. Do not resolve missing packages from the worktree. Run package resolution from the main project if needed. Skip browser validation in worktrees.
- Before archiving from a clean worktree, copy only the selected platform's existing ignored production client settings from the main project: iOS `Configuration/GoogleService-Info.prod.plist` and `Configuration/Production.local.xcconfig`; macOS equivalents and `.env.release` as required by its build scripts. These stay ignored. Never copy private Apple keys or backend credentials. Verify the exported Firebase bundle/project IDs and production OAuth client settings against the existing production configuration.
- Check that the shared package checkout exists: `apps/ios/.build/device/SourcePackages`. If it doesn't, resolve it once:
  ```sh
  DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild -resolvePackageDependencies -project apps/ios/Mulberry.xcodeproj -scheme 'Mulberry Production' -clonedSourcePackagesDirPath apps/ios/.build/device/SourcePackages
  ```
- Regenerate an Xcode project only if its generator inputs changed since the last release.

### 2. Bump the build number, commit and push

Choose the version first, for each selected platform:

```sh
$P/releases.py next-version <ios|macos>
```

- If it says a version is in progress (not public yet), keep it.
- Otherwise it suggests the next version from the commits since the last release (a `feat` means minor, otherwise patch). Tell the user the suggestion and why, and use the version they confirm. The full rule is in `public-release`.
- Check for the version's What's New note, `apps/backend/whats-new/<ios|macos>/<version>.md`. Not every version has one. If there's none, mention it once and carry on; don't stop. A note for this version must be committed before the first build reaches people, or the app marks the version seen and never shows it.

```sh
$S/bump-build.py <platforms> --dry-run   # shows current builds and the new one
$S/bump-build.py <platforms>             # edits the plists
```

- Each selected platform advances its own highest app/widget build by one. Unselected platforms stay unchanged. `--build N` must exceed each selected platform's current build. Also check known uploads so a locally stale counter cannot reuse an uploaded number.
- Pass `--version X.Y.Z` for the requested marketing version. With both platforms selected it sets the same version on both; to use different versions, run the bump command separately for each platform, then archive either separately or together. Dry-run output shows the planned versions and per-platform builds.
- Example: `bump-build.py ios --version 1.0.1` changes only iOS and its widget, leaving macOS untouched. Never set macOS to 1.0.1 merely because iOS is shipping that version.
- Commit only the plist changes and push. The user's request for a build authorizes this commit and push to `origin/main`; it's how every earlier build was done.

```sh
git add apps/ios/Configuration/Info.plist apps/ios/Configuration/Widgets-Info.plist apps/macos/Configuration/Info.plist apps/macos/Configuration/Widgets-Info.plist
git commit -m "chore(apple): prepare version <version> build <N>" -m "Co-Authored-By: <attribution from the current system reminder>"
git push origin main
```

### 3. Archive and export (no upload yet)

```sh
R=temp/apple-release/$(date +%F)-ios-<version>-<build>
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

This writes `$R/source.json` with the commit, platforms, and each artifact's own version, build and hash. Shared top-level version/build fields appear only when all selected platforms match. It stays in ignored `temp/`; don't commit it.

Then add each uploaded platform to the release ledger, and commit and push it:

```sh
$P/releases.py record-build <ios|macos> --version <version> --build <N> --channel testflight
git add docs/releases.json docs/releases.md
git commit -m "chore(release): record <iOS|macOS> <version> build <N>" -m "Co-Authored-By: <attribution from the current system reminder>"
git push origin main
```

### 7. Report to the user

Keep it short:
- the build number and platforms, and the source commit and its push
- that verification passed, with any notable detail
- that each upload succeeded

Then remind them:
- App Store Connect processes builds before they appear in TestFlight, usually within 10–30 minutes, and Apple emails when a build is ready or rejected.
- Encryption compliance is already answered in the plists (`ITSAppUsesNonExemptEncryption` = false).
- They still need to add the build to tester groups. External groups need beta review for the first build of a version.
- When a version goes live in the App Store, mark it public with the `public-release` subskill (`releases.py publish <platform> <version>`). That tags the build that shipped, publishes its GitHub Release, dates its What's New note and sets the live build.

## Common failures

- **"No profiles" or signing errors:** make sure Xcode → Settings → Accounts is signed in to the Techaboard team. `-allowProvisioningUpdates` creates profiles automatically. Never revoke certificates that belong to other apps.
- **Redundant binary upload or build already exists:** the build number wasn't bumped past the last upload. Bump it again (step 2) and rebuild.
- **Crashlytics step fails in the archive:** `MULBERRY_SOURCE_PACKAGES_DIR` must point to the package checkout (the script sets it). Check that the folder exists.
- **Upload stuck or authentication error:** retry once. If it fails again, show the user the last error lines from the upload log.
