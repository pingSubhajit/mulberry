---
name: wallpaper-shortcut-link
description: Make, check and wire up the iCloud links that open Mulberry's iOS wallpaper Shortcut ("Mulberry Sync") straight in Shortcuts. Use when the Shortcut template changes (icon, setup question, actions, trigger), when a build needs a link for a new bundle ID or team, when a link is missing or broken, or when the user asks for the `WALLPAPER_SHORTCUT_LINKS` value for Fly or the local backend.
---

# Wallpaper Shortcut link

The app's "Add the Shortcut" step opens an iCloud link (`https://www.icloud.com/shortcuts/<id>`), which takes people straight to Shortcuts' Add screen. Without a link, it falls back to sharing the bundled template through the share sheet. The backend sends the links in bootstrap as `wallpaperShortcutLinks`, read from `WALLPAPER_SHORTCUT_LINKS`, which holds `bundle.id=link` pairs separated by commas.

**Only the Shortcuts app can make a link**, with Share → Copy iCloud Link. Apple has no API for it, so each link is made on a real device. Each link is also a frozen copy of one template build, so **any change to `apps/ios/scripts/make-shortcut.py` that changes the output needs new links for every bundle ID.**

Scripts are in `scripts/` next to this file (`.agents/skills/mulberry-skills/wallpaper-shortcut-link/scripts/`), referred to below as `$S`. Run everything from the repository root.

## The ledger

[`ledger.md`](ledger.md), beside this file, records every link: which app it belongs to, which template it was made from, where it's set, and whether it's current. **Read it first**, since the link you need may already exist. **Update it in the same session** whenever you make, set, supersede or retire a link, and add a line under "Run notes" for anything new you learned on the device. Don't leave the ledger behind reality.

## Fixed facts

| | Dev | Production |
| --- | --- | --- |
| Bundle ID | `com.subhajit.mulberry.beta.dev` (`Configuration/Debug.xcconfig`) | `com.subhajit.mulberry.beta` (`Configuration/Base.xcconfig`) |
| Team | `HMJX9736KN` (`Configuration/Base.xcconfig`) | `HMJX9736KN` (`Configuration/Base.xcconfig`) |
| Backend | local, through Hun: root `.env` | Fly app `mulberry`: secret |

- **Dev and production differ only in bundle ID.** Both sign with `HMJX9736KN`; the personal team `YGJRT89MH9` and the old dev bundle `com.subhajit.mulberry.dev` are retired. If the bundle ID or team ever changes again, every link for that build stops working: its Shortcut calls the old app. Make new links and retire the old ones in the ledger.
- **The Shortcut must be named exactly "Mulberry Sync".** The app runs it and opens it by that name.
- **`apps/ios/ShortcutTemplates/` holds the committed dev template.** When the template changes, regenerate it with the team and dev bundle ID (`make-shortcut.py --team-id HMJX9736KN --bundle-id com.subhajit.mulberry.beta.dev`) and commit it. The app's build phase (`prepare-shortcut.py`) builds each build's own bundled copy, which is the share-sheet fallback. **Never write a production template into `ShortcutTemplates/`**: always pass `--output-dir`.
- Links are public. Anyone with one can view the Shortcut, which holds nothing private. Deleting the device copy doesn't revoke the link.

## Steps

Repeat steps 1–7 for each bundle ID that needs a link.

### 1. Build the template

```sh
B=com.subhajit.mulberry.beta          # or com.subhajit.mulberry.beta.dev
OUT=/tmp/mulberry-shortcut-$B
rm -rf $OUT
python3 apps/ios/scripts/make-shortcut.py --team-id HMJX9736KN --bundle-id $B --output-dir $OUT
```

This writes `$OUT/Mulberry Sync.plist` (the source, used in step 6) and the signed `$OUT/Mulberry Sync.shortcut`, which starts with `AEA1`.

### 2. Get the phone ready

- An iPhone running iOS 27.2 or later, connected over USB and unlocked. Find its ID with `xcrun devicectl list devices`; `devicectl` needs `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`.
- Drive it with `$S/devicehub.sh`. Run `open`, then `shot`, and read the screenshot after every action. Coordinates are points in that screenshot. **Check that the window's header names the iPhone**, not a simulator: other sessions use Device Hub too. If it doesn't, ask the user before switching the selection.
- **Make room for the name.** Open Shortcuts (`xcrun devicectl device process launch --device <id> com.apple.shortcuts`). If a "Mulberry Sync" already exists, it's the user's own. Long-press it, choose Rename, and type `Mulberry Sync dev` so the new copy can take the real name. You'll restore it in step 7. Never delete or edit the user's own copy.

### 3. Put the template on the phone

Serve it on the LAN and open it in Safari:

```sh
IP=$(ipconfig getifaddr en0)
python3 -m http.server 8799 --bind $IP --directory $OUT     # in the background
xcrun devicectl device process launch --device <id> --payload-url "http://$IP:8799/Mulberry%20Sync.shortcut" com.apple.mobilesafari
```

- **The file must be called `Mulberry Sync.shortcut`.** Shortcuts names the Shortcut after the file, not after the name inside it.
- **Tap Download straight away.** A download left waiting can stall at 0 KB; if it does, open the URL again with `?2` on the end.
- Open it from Safari's ☰ menu in the address bar → Downloads → the newest file, at the top. Earlier runs leave their files there, so Safari numbers the new one ("Mulberry Sync 4.shortcut"); fix the name in step 4.

### 4. Add it

- Tap **Set Up Shortcut**, then **Add Shortcut**. Leave Wallpaper unchosen: the shared link drops it anyway.
- If it came in under another name, go back to the library first. Long-press it there, choose Rename, and type `Mulberry Sync` with `$S/devicehub.sh type`. Selecting all sometimes misses and appends instead. Check the screenshot, run `type` again if needed, then tap Done.
- **Never rename it from the title menu** at the top of the Shortcut's own page. That saves the Shortcut again without its setup question, and the link made from it is useless. Step 6 catches it as `FAIL setup question`; delete that copy and add the file again.

### 5. Copy the link

Long-press "Mulberry Sync" in the library, then **Share → Copy iCloud Link → Copy Link**. Share from the library, not from the Shortcut's own page. Tap well clear of the contacts row in the share sheet. Then read the phone's clipboard:

```sh
xcrun devicectl device pasteboard paste --device <id>
```

### 6. Verify it

```sh
python3 -I $S/verify-link.py <link> "$OUT/Mulberry Sync.plist"
```

Every line must be `ok`, followed by `LINK MATCHES TEMPLATE`. The script checks:
- the name;
- the icon;
- the setup question;
- the trigger;
- the actions;
- that the app identity matches the template;
- that no Lock Screen from the phone came along.

If anything fails, don't use the link: fix the cause and make a new one. Record the failed link as retired in the ledger, since it stays public.

### 7. Clean up the phone

- Delete the copy you added: long-press → Delete → Delete Shortcut. The warning about deleting from all iCloud devices refers to this copy only. The link keeps working.
- Rename the user's own copy back to exactly `Mulberry Sync`, and check the library shows it.
- Stop the file server. Leave the Safari download in place unless the user asks; mention it.

### 8. Wire it up

- **Dev:** set the dev entry in the root `.env`, keeping any other entries, then restart the backend and confirm:
  ```sh
  hun restart mulberry:backend
  docker compose exec -T backend printenv WALLPAPER_SHORTCUT_LINKS
  ```
  Relaunch the app; the Add step's hint reads "Pick your Lock Screen, then tap Add Shortcut" when it has a link.
- **Production:** give the user the command; don't run it unless they ask in this conversation:
  ```sh
  fly secrets set -a mulberry WALLPAPER_SHORTCUT_LINKS="com.subhajit.mulberry.beta=<link>"
  ```
  The backend refuses to start if the value is malformed. It only takes effect once a backend with `wallpaperShortcutLinks` is deployed, and only builds that read it use it.

### 9. Update the ledger and report

- Add a row for each new link to [`ledger.md`](ledger.md).
- Mark the link it replaces as superseded.
- Record where each link is set. A production link stays "given to user" until they confirm the Fly secret.

Tell the user the links, the verification result, the exact env lines, and anything you changed on their phone and restored.
