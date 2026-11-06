---
name: public-release
description: Release a Mulberry iOS, macOS or Android version when it's sent for public review, and keep the release ledger. Tags the build, publishes its GitHub Release and dates its What's New note; after a rejection, moves the tag to the resubmitted build. Also answers "what's released", "what's the next version", and "what changed since the last release".
---

# Mulberry public release

Builds and releases are different things.

- A **build** is any upload: a TestFlight build (`testflight-release`) or a Play upload (`play-release`). A version usually has several builds. Builds are recorded in the ledger, never tagged.
- A version is **released** when it's sent for public review: submitted for App Store review, or uploaded and rolled out on Play's open testing track (Mulberry's public Android channel). Only then does it get a git tag and a GitHub Release.
- If Apple or Google rejects that build, a newer build of the **same version** goes in, and the tag and GitHub Release move to it (`--move`). The version number doesn't change.

Run from the repository root. The script is `scripts/releases.py` next to this file, below `$S`:

```sh
S=.agents/skills/mulberry-skills/public-release/scripts
```

## The ledger

`docs/releases.json` lists every uploaded build of each platform: version, build, the commit it came from, the channel, the upload day, and the release day and tag for the one that went public. `docs/releases.md` is rendered from it for people. Both are committed. Never edit them by hand; the scripts write them.

```sh
$S/releases.py show            # each platform: version in the repo, last public release, builds of the current version
$S/releases.py show android
```

## Version rule

Semantic versioning, separately for each platform. iOS, macOS and Android keep their own version numbers and build counters; they never need to match.

- **Minor** (1.1.x → 1.2.0) when the release brings a headline feature.
- **Patch** (1.1.0 → 1.1.1) when it only fixes or polishes.
- **Major** only when the user asks.

A version is chosen once, at the first build after the platform's last public release, and kept for every later build until it goes public. Before that first build, ask:

```sh
$S/releases.py next-version ios
```

It answers either `inProgress` (the repository's version isn't public yet: keep it) or a `suggestion` from the commits since the last tag (`feat` means minor, otherwise patch), with those commits listed. Tell the user the suggestion and the reason, and use the version they confirm.

## Releasing a version

Run this when the version is sent for public review: when the user says they've submitted an iOS or macOS version for App Store review, and right after `play-release` uploads an Android build.

1. Dry run, and show the user what will happen:
   ```sh
   $S/releases.py publish <ios|macos|android> <version> --dry-run
   ```
   It picks the newest uploaded build of that version (`--build N` picks another, if a different build was submitted) and prints the tag and the GitHub Release text.
2. Run it:
   ```sh
   $S/releases.py publish <platform> <version>
   ```
   - It records the release in the ledger. If the version has a What's New note, it writes today's date into the note's `released_at`, so What's New history lists it. It commits both and pushes; a pushed note deploys the backend.
   - It tags the commit the build came from (`ios-v1.1.0`, `android-v1.2.0`, `macos-v0.2.0`), pushes the tag, and creates the GitHub Release. The release text is the What's New note when there is one; otherwise it lists the `feat`, `fix` and `perf` commits since the previous tag.
   - `--date YYYY-MM-DD` records a different day, for a release recorded late.
3. Report the tag and the GitHub Release link.

### After a rejection

The user uploads a newer build of the same version (`testflight-release` or `play-release` as usual) and resubmits it. Then move the release to it:

```sh
$S/releases.py publish <platform> <version> --move --dry-run
$S/releases.py publish <platform> <version> --move
```

It moves the ledger's release to the newest build of that version, force-moves the tag to that build's commit, and refreshes the GitHub Release text. The note's date stays.

### The live build

Releasing doesn't touch the update prompts. `liveBuild` caps how high `recommendedBuild` and `minimumBuild` can go, so it must only name a build people can download. Set it with the `app-versions` subskill once the build is actually live in the store, normally when the user wants to nudge or force an update.

## What changed since the last release

```sh
git log <platform>-v<last version>..HEAD -- <paths>
```

Paths: iOS `apps/ios packages/apple`; macOS `apps/macos packages/apple`; Android `apps/mobile`. `releases.py next-version` prints the same list.
