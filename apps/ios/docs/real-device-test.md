# Real iPhone test handoff

## Needed from the owner

Connect an iPhone running iOS 27 to this Mac with a data-capable USB cable. Unlock it, approve Trust This Computer, and finish pairing in Xcode Device Hub. Enable Developer Mode in Settings > Privacy & Security, restart, and confirm it after restart. Complete passcode and Apple account prompts yourself. Allow the intended Mac to view/control the device when prompted.

Sign into the intended Apple account in Xcode > Settings > Accounts. Identify the development team to use for this POC. The app needs a development signing identity and provisioning profile. A free personal team can be tried with the script's local-notification mode; real APNs testing needs a team/profile that supports Push Notifications. No backend production environment is needed for the first test.

Keep the Mac and iPhone on the same Wi-Fi for the local mock. The Mac must stay awake while it serves images.

## Prepared on the Mac

The project is `apps/ios/MulberryDelivery.xcodeproj`, with an iOS 27 minimum. Xcode 27.1 is installed. `scripts/run-device.py` checks for a physical iPhone and will not silently use a simulator. It takes `--team-id`, optionally `--device`, and optionally `--push`. By default it uses the empty LocalDelivery.entitlements file and tests local notifications. It rebuilds the bundled Shortcut with the chosen team ID before building/installing the app. The account must already be available in Xcode; the script allows Xcode to obtain the required provisioning assets.

An unsigned build for the physical iPhoneOS SDK passed on 26 October 2026. A Personal Team build was signed, installed, and launched on iPhone 17 with iOS 27.0.1 on 26 October 2026. The owner trusted the development profile. Revision 1 fetched successfully from the Mac LAN mock; manual wallpaper rendering, background local-notification automation, and locked-screen local-notification automation passed. The five Swift core tests and two mock HTTP tests passed in the simulator preparation work.

## First visual test

Run the device script's `--check`, then build/install with the intended team ID. Start `mock/server.py` with `--host` set to the Mac's LAN address and `--port 8788`. This avoids the existing simulator mock on port 8787. Enter `http://MAC_LAN_IP:8788` in Mulberry, approve local-network access, and tap Fetch latest image. The phone must show the image and revision before any Shortcut testing. The phone's `127.0.0.1` is not the Mac.

Use the Setup tab to add the supplied template. Choose Shortcuts in the share sheet and tap Add Shortcut. The former wallpaper import question was removed because Add Shortcut stalled after target selection. Use Choose wallpaper and enable updates to open the editor. Tap the wallpaper name in Set Wallpaper and choose the test photo wallpaper. Expand the notification trigger and turn Automation on. Tap Back to save. Run once and allow access returns to Mulberry through x-callback-url. Approve first-run app and wallpaper permissions. Check the wallpaper visually.

The template contains Get latest Mulberry wallpaper > Set Wallpaper > Record completion. Preview, smart crop, and legibility blur are off. Do not assume a blank target creates a new wallpaper: iOS may prefill an existing choice. Check the target explicitly.

## Notification automation

Before replacing a template, disable old Mulberry automations. Keep only one enabled copy. Two older copies were found during hardware tests, and an enabled third copy initially invalidated the disabled-automation test. After all copies were off, revision 9 correctly produced no fetch or completion and the app showed Test did not finish.

Tap Test wallpaper update, then go Home or lock the phone before the five-second local notification arrives. Do not run the Shortcut or tap the notification. Match the revision to the receipt and inspect the screen. The final receipt action removes completed wallpaper update notices only. Failed tests keep their notice. Notification Center-only delivery was tested with banners, Lock Screen alerts, sounds, and badges off.

The status screen rereads settings when active. Disable Mulberry notifications, return to the app, and verify Notifications are off. Restore permission and run a new test. Disable the Shortcut, run another test, wait at least 45 seconds, and verify Test did not finish. This timeout is a diagnostic threshold, not a wallpaper rate limit.

## Real remote push

Rebuild with `run-device.py --team-id TEAM_ID --push` once push-enabled signing is available. Allow notifications and obtain the current app/device token from the app's APNs token section. Keep tokens and APNs keys out of source control and shared screenshots.

Publish a mock revision using `POST /publish` on the same server the phone uses. Use Apple's Push Notification Console with this app's bundle ID, current device token, and development environment. Send an alert whose title is `Mulberry canvas updated`. The body can name the published revision. Include `kind: wallpaper_update` and the integer `revision` alongside `aps`; use `interruption-level: passive` inside `aps` and omit sound/badge. Leave the app in the background and do not tap the notification.

The simulator's `publish.mjs` uses `simctl push` and cannot send to a physical device. Apple's console avoids needing a custom APNs sender for this first test. A later automated sender requires APNs key ID, team ID, and a local `.p8` key file, or certificate-based authentication.

## Evidence

Match each published revision against `image_ready` and `shortcut_reported_applied`. The latter only means Set Wallpaper returned. Record `user_confirmed_wallpaper` only after seeing that revision on the actual wallpaper. Fetch that exact revision in the app before using its visual-confirmation button; do not attribute an old wallpaper to the latest server revision.

Use Export event log in Mulberry, or copy `Documents/delivery-events.json` with `devicectl device copy from`, domain type `appDataContainer`, domain identifier `com.subhajit.mulberry.deliverylab`. Capture the screen separately. Start with single updates. Then measure several spaced updates with timestamps before testing short bursts. The simulator's five-second burst did not complete every requested revision and does not establish an iOS rate limit.

Local-notification delivery and rendering passed both unlocked and locked on hardware. Export and import preserved the notification trigger, which required a manual enable. A fresh combined-template import with both first-run Shortcut permission prompts subsequently passed; see `evidence/iphone17-cleanup`. Actual APNs delivery, Low Power Mode, Focus, sustained update rates, reboot behavior, a reset app onboarding flow, and quiet notification arrival while already locked remain unverified. Simulator image-processing failures are not an app-side rendering fix to carry to the phone.
