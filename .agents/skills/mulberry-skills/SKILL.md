---
name: mulberry-skills
description: Mulberry's own project workflows, each a subskill in this folder. Use for "create a new build", "upload to TestFlight", "ship a beta", "package the iOS/Mac app", "bump the build number", or any Mulberry iOS or macOS App Store Connect upload; for "release iOS", "submit to the App Store", "send iOS for review"; for "new Android build", "release Android", "upload to Play", or any Google Play upload; for "mark it released", "tag the release", "it got rejected", "what's released", "what's the next version", or "what changed since the last release"; and for "force an update", "nudge people to update", "raise the minimum build", "mark build N live", or checking which iOS/Android builds must update; and for adding, changing, hiding, ordering or restoring stickers, wallpapers or server-driven content; and for "make a new Shortcut link", "update the wallpaper Shortcut", "WALLPAPER_SHORTCUT_LINKS", or any change to the iOS Shortcut template that needs new iCloud links.
---

# Mulberry skills

Each subskill has its own `SKILL.md`. Read the matching one in full and follow it. Don't work from memory or from this index.

| Subskill | Use when |
| --- | --- |
| [`testflight-release`](testflight-release/SKILL.md) | The user asks for a new iOS and/or macOS build: archive, verify, upload to App Store Connect/TestFlight, record it. |
| [`ios-release`](ios-release/SKILL.md) | The user asks to release iOS on the App Store, or resubmit after a rejection: bump, build, verify, upload, prepare the App Store review with What's New, release (tag); the user presses Submit for Review. Separate from `testflight-release`. |
| [`android-release`](android-release/SKILL.md) | The user asks for a new Android build or release: bump, build the signed bundle, verify, upload to Play's open testing track (Mulberry's public Android channel) and roll it out, release (tag). |
| [`public-release`](public-release/SKILL.md) | The shared release step `ios-release` and `android-release` end with: tag, GitHub Release, What's New note date; `--move` after a rejection. Also the release ledger, the version rule, "what's released" and "what changed since the last release". |
| [`catalog`](catalog/SKILL.md) | Add and manage server-driven stickers and wallpapers, including draft versions, publishing, ordering, history and recovery. Reference: [`server-driven-content.md`](../../../docs/server-driven-content.md). |
| [`app-versions`](app-versions/SKILL.md) | The user asks to force or nudge an update, change the minimum, recommended or live build for iOS or Android, or check those values. |
| [`wallpaper-shortcut-link`](wallpaper-shortcut-link/SKILL.md) | The iOS wallpaper Shortcut template changed, a build needs an iCloud link for its bundle ID, a link is broken, or the user wants the `WALLPAPER_SHORTCUT_LINKS` value. Keeps [`ledger.md`](wallpaper-shortcut-link/ledger.md) of every link. |

Paths are relative to this folder: `.agents/skills/mulberry-skills/` (reached through `.claude/skills/mulberry-skills`).

To add a subskill, give it a folder with a `SKILL.md` and add a row to this table. A subskill that needs a record of past runs keeps a ledger file beside its `SKILL.md`, and its steps say when to update it.
