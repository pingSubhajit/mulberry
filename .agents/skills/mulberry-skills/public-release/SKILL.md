---
name: public-release
description: Mark a Mulberry iOS, macOS or Android version as publicly released, and keep the release ledger. Tags the build that went public, publishes its GitHub Release, dates its What's New note, and sets the live build for the update prompts. Also answers "what's live", "what's the next version", and "what changed since the last release".
---

# Mulberry public release

Builds and releases are different things.

- A **build** is any upload: a TestFlight build (`testflight-release`) or a Play upload (`play-release`). A version usually has several builds before one goes public. Builds are recorded in the ledger, never tagged.
- A **release** is the moment a version goes public: the App Store version is live, or the Android build is rolled out on Play's open testing track, which is Mulberry's public Android channel. Only a release gets a git tag and a GitHub Release, and a GitHub Release always means a public release.

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

When the user says a version is public (it's showing in the App Store, or Play has finished reviewing and rolled out the Android build):

1. Dry run, and show the user what will happen:
   ```sh
   $S/releases.py publish <ios|macos|android> <version> --dry-run
   ```
   It picks the newest uploaded build of that version (`--build N` picks another, if a different build went public) and prints the tag, the GitHub Release text and the live-build change.
2. Run it:
   ```sh
   $S/releases.py publish <platform> <version>
   ```
   - It records the release in the ledger. If the version has a What's New note, it writes today's date into the note's `released_at`, so What's New history starts listing it. It commits both and pushes. A pushed note deploys the backend.
   - It tags the commit the public build came from (`ios-v1.1.0`, `android-v1.1.10`, `macos-v0.2.0`), pushes the tag, and creates the GitHub Release. The release text is the What's New note when there is one; otherwise it lists the `feat`, `fix` and `perf` commits since the previous tag.
   - For iOS and Android it sets `liveBuild` through the app-versions API if the new build is higher. macOS has no update gate.
   - `--date YYYY-MM-DD` records a different release day, for a release recorded late.
3. Report the tag, the GitHub Release link and the `liveBuild` change.

Raising `recommendedBuild` or `minimumBuild` is a separate decision: use the `app-versions` subskill, only when the user asks.

## What changed since the last release

```sh
git log <platform>-v<last version>..HEAD -- <paths>
```

Paths: iOS `apps/ios packages/apple`; macOS `apps/macos packages/apple`; Android `apps/mobile`. `releases.py next-version` prints the same list.
