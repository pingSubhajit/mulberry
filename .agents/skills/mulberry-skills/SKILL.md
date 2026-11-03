---
name: mulberry-skills
description: Mulberry's own project workflows, each a subskill in this folder. Use for "create a new build", "upload to TestFlight", "ship a beta", "package the iOS/Mac app", "bump the build number", or any Mulberry iOS or macOS App Store Connect upload; and for "force an update", "nudge people to update", "raise the minimum build", "mark build N live", or checking which iOS/Android builds must update.
---

# Mulberry skills

Each subskill has its own `SKILL.md`. Read the matching one in full and follow it. Don't work from memory or from this index.

| Subskill | Use when |
| --- | --- |
| [`testflight-release`](testflight-release/SKILL.md) | The user asks for a new iOS and/or macOS build: archive, verify, upload to App Store Connect/TestFlight, record it. |
| [`app-versions`](app-versions/SKILL.md) | The user asks to force or nudge an update, change the minimum, recommended or live build for iOS or Android, or check those values. |

Paths are relative to this folder: `.agents/skills/mulberry-skills/` (reached through `.claude/skills/mulberry-skills`).

To add a subskill, give it a folder with a `SKILL.md` and add a row to this table.
