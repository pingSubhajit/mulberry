# Wallpaper Shortcut link ledger

Kept by agents running the [`wallpaper-shortcut-link`](SKILL.md) skill. Update it in the same session whenever a link is made, set, superseded or retired. Newest first. "Template" is the first 12 characters of `shasum -a 256 apps/ios/scripts/make-shortcut.py` when the link was made, so a changed script means the link is out of date.

## Links

| Made | App | Link | Template | Set in | Status |
| --- | --- | --- | --- | --- | --- |
| 2026-11-03 | `com.subhajit.mulberry.beta.dev` (HMJX9736KN) | https://www.icloud.com/shortcuts/676f7b028124448ba79f173e419bd063 | `59fd71514842` | root `.env` | current |
| 2026-11-03 | `com.subhajit.mulberry.beta.dev` (HMJX9736KN) | https://www.icloud.com/shortcuts/30712d162dcf4281bf1791b70f213836 | `59fd71514842` | nowhere | retired: lost its setup question (renamed from the title menu). Never use it. |
| 2026-11-03 | `com.subhajit.mulberry.beta` (HMJX9736KN) | https://www.icloud.com/shortcuts/ff0a1afd21634bae9d833cf7c06513ae | `9269c33eb502` | given to user for Fly `mulberry`; not yet confirmed | current. Rechecked against template `59fd71514842`: still matches, since that change only touched the script's default bundle ID. |
| 2026-11-03 | `com.subhajit.mulberry.dev` (YGJRT89MH9) | https://www.icloud.com/shortcuts/70223302d3224a658264822a9c8e3684 | `9269c33eb502` | removed from root `.env` | superseded: that bundle ID and team are retired. |
| 2026-11-03 | `com.subhajit.mulberry.beta` | https://www.icloud.com/shortcuts/f4803410f4004ee398bd1eedb8d4ea54 | earlier test template | nowhere | retired: "Mulberry Setup Test", the purple iPad test copy. Never use it. |

Current links: red (`4282601983`) with the cartoon heart glyph (`61577`), and the setup question "Pick a photo Lock Screen without a Focus. Mulberry replaces its photo with your drawings."

## Run notes

Add a dated line for anything the skill didn't already say.

- 2026-11-03: First run, on an iPhone 17 (iOS 27.2) through Device Hub. The setup question and the notification trigger both survive the iCloud round trip, and opening a link from inside the app shows Set Up Shortcut with the question. The Shortcut was first served as `MulberrySync.shortcut` and imported as "MulberrySync", and Safari then saved the next download as "Mulberry Sync 2". Both had to be renamed, which is why the skill fixes the file name.
- 2026-11-03: The dev app moved to `com.subhajit.mulberry.beta.dev` and every build to team `HMJX9736KN`, so the old dev link opened a Shortcut for an app that no longer exists. The first new link lost its setup question: the copy had been renamed from the title menu on the Shortcut's own page, which saves it again without import questions. Renaming from the library's long-press menu kept it. Safari's Downloads now numbers new files up to "Mulberry Sync 4"; the number doesn't matter once the rename is done right.
