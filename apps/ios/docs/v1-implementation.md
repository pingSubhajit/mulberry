# iOS v1 delivery ledger

## Scope and authority

Android is the reference for behavior, copy, assets, and visual identity. The macOS UI is not a reference. Target iOS 27, using the physical iPhone 17 for wallpaper tests and iPhone 17 Pro simulators for layout checks.

Required: real Google authentication; Android-equivalent onboarding; local background selection and the imported Shortcut setup; pairing, acceptance, decline, expiry and unpairing; built-in and remote wallpapers; synchronized drawing with undo/redo, brush, width, palette, eyedropper, eraser and clear; settings, Canny, home-screen quick actions; partner name/photo and the server's 72-hour cooldown; production icon appearances and purple development icon.

Text and Stickers remain visible, inert menu entries. Reactions, What's New functionality, and Time Together widgets are excluded. The What's New settings row remains inert.

## Verified starting state, 2026-10-27

The previous iOS target is a delivery prototype using a mock canvas service. It is not the product app. The real backend runs locally on port 8080. Android uses real Google authentication, with development-token login disabled.

Removed `com.subhajit.mulberry.deliverylab` from the connected iPhone with devicectl. Removed `Mulberry Wallpaper POC` from Shortcuts and verified that only the existing Dev Mode and Charger shortcuts remain. The prototype sources and past test evidence remain in the repository.

Inspected the running Android unpaired home screen at 1280 × 2856 pixels. Preserve the warm near-white background, berry-red accent, Poppins text, paired avatars, prominent invite action, and Canvas/Wallpaper navigation. Use native iOS navigation, sheets, photo picker and permission prompts. New iOS-specific screens need a visual target before interface implementation, per the requested design skills.

## Delivery sequence and evidence

| Area | Current evidence | Remaining proof |
| --- | --- | --- |
| Device cleanup | Shortcut list inspected after deletion; devicectl confirmed uninstall | Complete |
| Backend contracts | Swift client passed isolated real-route tests for auth/profile/pairing/canvas/presence/Canny/gallery/logout | Device integration |
| Real authentication | Real Google sign-in with the authorized account succeeded on iPhone 17; loaded its existing profile and wallpaper onboarding | Logout, relaunch and token expiry on device |
| Pairing/onboarding | Product screens implemented; actual route tests cover create/redeem/accept/disconnect and cooldown | Real Android/iOS pairing, decline/expiry/unpairing, new-account layout |
| Canvas | Native touch canvas and all drawing tools implemented; durable pair-scoped outbox, ordered reducer and WebSocket sync; 18 core tests pass | Cross-device strokes, all tools, renderer parity, interrupted-stroke recovery |
| Wallpaper | Authenticated private-background renderer and revision-bound receipt wired to App Intents; built-ins/photos/gallery and setup/repair UI implemented | Product Shortcut import, actions and notification removal on phone; gallery/photo runtime |
| Push | Local test uses passive notification; foreground callbacks do not claim completion | Actual APNs/FCM registration, backend iOS delivery and locked-device remote test |
| Settings and identity | Profile, partner cooldown, Canny, settings and long-press actions implemented; layered production and purple dev icons compiled | Runtime audit of settings and quick actions; theme appearances on Home Screen |
| Final audit | In progress | Requirement-by-requirement code, runtime and visual evidence |

## Current implementation checkpoint

The app root now presents the product flow. `ProductWallpaperService` renders the authenticated canvas over the account's private wallpaper and records only a matching export receipt after Set Wallpaper returns. The old lab implementation remains in reference files, but the product root and intents no longer use its mock server. Notification callbacks have been moved off the old lab logger.

Public development bundle ID: `com.subhajit.mulberry.dev`. The Firebase/Google iOS registration belongs to project `mulberry-494003`. Ignored `Configuration/GoogleService-Info.dev.plist` and `Configuration/Local.xcconfig` hold public client configuration. The local API is `http://192.168.0.105:8080/`. Google ID tokens use the existing backend Web client audience. Production iOS OAuth configuration is still pending.

The personal development team YGJRT89MH9 signed the device build. The user authorized installation and trust. iPhone trust completed and the product launched on iPhone 17 / iOS 27.0.1. Google login succeeded with the specified account after local-network access was allowed. The first permission prompt caused an immediate offline error in the installed build; the next build now uses URLSession's waitsForConnectivity with a 45-second resource timeout. `docs/evidence/v1/iphone-google-signed-in.png` shows the resulting wallpaper onboarding. The profile was already complete, so this did not exercise the first-account name/date screens.

`python3 apps/ios/scripts/test-product.py` passed 18 ProductCore tests, including two real TypeScript WebSocket connections, duplicate/lost acknowledgements, stale-session isolation and undo/erase/redo identity behavior. These run with pg-mem and do not mutate development or production data. Device and simulator Debug builds passed. The device build reports a nonfatal Siri SSU archive error; product App Intent availability still needs the actual Shortcuts test.

The original Android artwork and fonts are in the native auth UI. Standard text and largest Dynamic Type were inspected in the simulator; the largest-size contrast adjustment is now in source and requires a new capture. Generated design references are in `docs/design`; these are not bundled screen images. Native evidence is in `docs/evidence/v1`.

Both editable Icon Composer projects preserve the original Android mark. Default, dark, clear light/dark and tinted light/dark outputs were exported with Apple's icon tool and visually inspected. Debug uses the purple icon. Actual Home Screen theme switching is not yet checked.

The backend accepts IOS_SHORTCUT presence separately from Android wallpaper fields. Its focused presence test and TypeScript build passed earlier. The local Docker backend was rebuilt from current source and its health endpoint passed, preserving the existing PostgreSQL volume. Product presence reporting and remote push delivery still need integration.

## Physical product checkpoint, 14:43 IST

The signed product Shortcut imported with all three App Intents resolved. It retained the Mulberry Dev notification/title filter and initially disabled automation. Selected wallpaper pair **2**, the guitar pair, at the user's direction. Enabled automation, completed the two first-run permission prompts, and ran the manual update through the app's callback flow. Both Home and Lock Screen then displayed the selected Mulberry background. The original guitar image identifies the selected system wallpaper slot; it is replaced by the rendered background when the Shortcut runs.

A local notification probe at 09:04:11.936 UTC reached image-ready at 09:04:17.720 and a matching completion receipt at 09:04:21.598. No notification was tapped. Notification Center then contained unrelated notices but no Mulberry update notice. The product setup screen showed Last test completed after reinstall, and the real Google session survived. Evidence: `iphone-delivery-events.json`, `iphone-wallpaper-home.png`, `iphone-automatic-test-passed.png`, and `iphone-shortcut-imported.png`. The exported PNG measured 1206 × 2622 pixels. This is local notification delivery, not APNs, and does not establish a general refresh rate.

Current provisioning is the personal team YGJRT89MH9, local provision, expiry 2026-11-03, with no aps-environment entitlement. Remote delivery remains unimplemented/unverified.

Pairing runtime revealed the missing partner-details step after an earlier unpair. Both existing test accounts had completed onboarding but no partner name or anniversary. Added the Android-equivalent form before invite creation, using the existing partner update endpoint, then creating the invite. The initial broken screen was observed; the repaired device build passed, was installed, and created an invite after saving Cignus and the explicit local-test date 2026-10-27. Redeemed and accepted that invite through the Android account’s existing authenticated session. The iPhone then displayed its paired canvas. Also fixed an iOS toolbar overflow that hid the Mulberry title and added logging of the exact count of removed wallpaper notifications.

## Important invariants

Store backend tokens in Keychain. No service account, OAuth client secret, or production environment file may enter the app bundle. Use the real local backend and database; do not reset Android sessions or existing data. Use the personal Apple team only.

Persist drawing work under both user and pair-session identities. Never replay old pending operations into a new pair. Deduplicate acknowledged operations and recover gaps before advancing the durable revision. Use the backend's cooldown response, not an independent client-only timer.

Shortcut setup health must distinguish notification permission from an actual same-revision completion receipt. iOS cannot enumerate arbitrary Shortcuts or directly prove wallpaper pixels. A stale receipt must not be presented as proof that the automation is currently enabled. Remote background delivery is not verified by a foreground local notification.

## Product checkpoint after paired-device testing

The iPhone paired with the existing Android test account. Two physical touch strokes rendered in berry red and blue. Undo removed the first stroke, redo restored it, and the colour palette selected blue. This proves iPhone touch and server sync; Android acceptance used its real authenticated API session, not automated Android UI.

Removed the per-stroke “Saving your drawing…” row at the user’s request. Pending edits no longer change the canvas layout. Also split the paired toolbar into separate Settings and streak items because the previous combined item hid Settings.

Remote delivery code now adds iOS FCM token registration/revocation, passive APNs alerts with the exact Shortcut trigger title, revision-scoped remote notification cleanup, and visibility reporting based on completed render receipts. Own foreground canvas changes schedule a debounced local update after setup. Firebase Messaging 12.19.3 is resolving for compatibility with Google Sign-In 9.0.0. Remote delivery is not yet physically verified; the personal-team profile still has no APNs entitlement. The updated device build remains pending SDK resolution.

The backend TypeScript build and four focused push/presence tests passed. Nineteen ProductCore tests passed, including actual backend token registration/revocation and durable recovery of an interrupted local stroke after server acknowledgement without finishing a partner’s active stroke. Tests use an isolated in-memory database.

## Installed checkpoint, 15:16 IST

Firebase Messaging 12.19.3 and Google Sign-In 9.0.0 resolved together. The signed device build passed, installed, and launched on the physical iPhone. The build retains two Firebase API deprecation warnings and Xcode's nonfatal SSU archive diagnostic; actual remote notification delivery is still unverified.

Verified the Settings avatar and streak are visible in separate toolbar items. Drew a test stroke on the phone: the saving row is absent and the tool bar remains at the same position. Saved `iphone-canvas-no-saving-row.png`, then undid that test stroke. The local backend was rebuilt with iOS push support and `/health` passed. No database reset occurred.

Own canvas updates are scheduled when the app goes to the background, after an existing receipt for that pair, rather than interrupting foreground drawing. Presence reporting reads small saved flags rather than reloading wallpaper photos. Remaining runtime checks include paired Shortcut notification cleanup, locked remote delivery once push provisioning exists, eraser/eyedropper/width, quick actions, gallery/photos, cooldown and full onboarding layouts. The Android emulator was no longer running at the last cross-device API probe; its session was not reset.

## Canvas polish and wallpaper sharpness, 2026-10-27

Moved Profile into the native bottom tab bar and removed the header avatar. Replaced the extra Draw/Text/Stickers row with Android's single horizontal tool tray. The tray uses the original Android action artwork, circular selection rings, a width-sensitive dot, and a compact anchored slider with no numeric label. Text and Stickers remain disabled placeholders within the row. Clear no longer changes its enabled state while each stroke append is being saved.

Saved the user's Icon Composer edits, promoted the berry automatic gradient from the former Dark appearance to Default, and made a separate deep berry Dark appearance. Applied the same glass/gradient/opacity treatment to the purple development icon with a deep purple Dark appearance. Apple's icon tool exported all six appearances for both projects; default/dark/clear/tinted samples were inspected. The installed development icon was visible in iPhone search.

The wallpaper problem had two distinct causes. The iOS canvas rendered in UIKit points while the export passed physical pixels into the dry-brush algorithm. This changed its minimum line widths, bristle count, and width-seeded texture. Export now draws in screen points with the phone's native raster scale, then returns the existing bitmap at scale 1 so PNG metadata describes physical pixels. It does not upscale a canvas screenshot. `scripts/test-rendering.sh` passed 18 exact canvas/export pixel comparisons across round/dry strokes, widths 4/10/42, and 2x/3x devices, including PNG serialization. Physical output remains 1206 × 2622.

The remaining softness was reproduced in an actual iPhone screenshot, not just Device Hub's video. Home Screen Blur was already off. Spatial Scene was enabled on the selected system wallpaper pair 2; turning it off immediately removed the extra softness and zoom. Saved that change on pair 2. Added an in-app troubleshooting item explaining this setting. Native screenshot evidence is `docs/evidence/v1/iphone-wallpaper-sharp-native.png`; popover, Profile tab, Blur off, and Spatial Scene off captures are alongside it. No source wallpaper replacement was needed.

Built and installed with personal team YGJRT89MH9. The user's existing drawings were preserved.

A fresh manual Shortcut run after disabling Spatial Scene preserved the sharp result on pair 2. Verified the new 15:58 export and captured the actual iPhone screen at 15:59 (`iphone-wallpaper-repeat-update-native.png`).

Follow-up UI request: removed every connection/error status row above the canvas tools. Connection state remains in Profile; local save errors and their retry action also live there. The editor retries a failed save when the app becomes active. The bottom Profile tab now renders the user's actual photo as a circular native tab image, with a standard icon only while unavailable. The header uses Android's owned wordmark on the left and its bolt artwork, day/day-plural wording, and outlined streak pill on the right. Dark appearance follows Android's white wordmark/text. No second profile button is needed in the header.

Final device check at 16:01: header is left aligned with the Android bolt and “1 day” pill; the Profile tab displays the actual colour photo. UIKit required the underlying tab image's `.alwaysOriginal` rendering mode as well as SwiftUI's original mode. Saved `iphone-final-header-photo-tab.png`. The final personal-team build succeeded and was installed. All canvas status rows are absent by construction; no network outage was forced on the user's phone.

### Offline wallpaper correction — 2026-10-27

Cold launch now restores the saved drawing session before the server responds. Local wallpaper triggers compare rendered content rather than only the confirmed server revision, so unsent strokes, clears, private photo changes and visibility changes invalidate the wallpaper. Shortcut preparation uses a short online refresh with an offline fallback; recording completion and notification cleanup need no server. Account and pairing guards still apply, and reconnect uploads remain bound to the original pair. Runtime and setup UI were separated from the delivery service so the actual model, editor and export path can run in a deterministic simulator harness.

The original offline failures were reproduced before the fix. The expanded native harness, real-backend reconnect contract, Swift product tests, 18 native renderer comparisons and signed device build pass. Physical-device testing subsequently passed on the connected iPhone 17: backend-stopped drawing, cold restart, undo/redo, manual image changes, a locked photo-only update, airplane-mode erase, notification cleanup, offline clear, and reconnect upload to the Android peer. All 93 previously pending operations were preserved; 103 operations uploaded once on the first reconnect. Final cleanup reached revision 878 with no pending edits. See [offline wallpaper testing](offline-wallpaper.md) for evidence and the suspended-app limitation.
