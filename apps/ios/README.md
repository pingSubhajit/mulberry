# Mulberry iOS

The iOS 27 product app now opens the real Google sign-in, onboarding, pairing, canvas, wallpaper and settings flow. Android supplies the original artwork, fonts, palette, drawing behavior and content. Native iOS navigation, forms, sheets and photo pickers adapt that design to iPhone. See [the v1 ledger](docs/v1-implementation.md) for tested behavior and remaining work.

`Sources/ProductCore` contains the authenticated backend client, Keychain session store, drawing reducer, durable outbox and WebSocket sync. `Sources/App` contains the product UI and local wallpaper renderer. App Intents fetch the signed-in account's canvas and combine it with that account's private background. The old delivery-lab files remain as reference; they are not the app's root UI.

## Product development

Use the repository's existing local backend and PostgreSQL database on port 8080. Do not load `.env.prod` into the app or use the prototype mock server for product testing. Real Google authentication is required.

The Xcode project uses the official Google Sign-In package. Regenerate it with `python3 apps/ios/scripts/generate-project.py`. Android's existing fonts and artwork are copied by `python3 apps/ios/scripts/prepare-assets.py`. Icons are editable Icon Composer projects under `Resources`; Debug uses purple `AppIconDev`, Release uses `AppIcon`.

Development settings live in ignored `Configuration/Local.xcconfig`. `scripts/configure-google.mjs` retrieves public Firebase client configuration from `--ios-client-id` and `--api-url`. Its server credential is used only to call Firebase Management and never enters the app. Only Debug includes local settings. Release needs its own Google iOS client configuration before sign-in can work.

Run `python3 apps/ios/scripts/test-product.py` from the repository root to check Swift against an isolated real backend with an in-memory database. It does not use the local development database or production credentials. The backend dependencies must already be installed. Delivery-state tests remain available through `python3 apps/ios/scripts/test.py`.

`apps/ios/scripts/run-simulator.sh` builds and runs the product on iPhone 17 Pro, iOS 27. Set the development team in local configuration or `MULBERRY_TEAM_ID`; keep simulator signing enabled for Keychain. `python3 apps/ios/scripts/run-device.py --team-id YOUR_TEAM_ID` builds, installs and opens the product on a connected physical iPhone. This also regenerates the signed development Shortcut for that team. `--push` requires a push-enabled provisioning profile; the default build supports local delivery tests.

The development template is `ShortcutTemplates/Mulberry Wallpaper Dev.shortcut`. The app shares it from Wallpaper setup. The user selects the target wallpaper and enables its notification automation in Shortcuts. First-run permissions still require iOS prompts. The template does not silently enable automation. A local test and matching completion receipt check the chain; neither proves remote push delivery or reads wallpaper pixels.

The material below records the earlier prototype. Its mock-service commands, lab identifiers and historical test results are not instructions for running the current product.

## Delivery prototype reference

This app targets iOS 27 or newer. It tests how a new canvas reaches a wallpaper through Shortcuts. It uses a local mock server; no production environment variables are needed. Drawing tools and the final app design are outside this POC.

The intended flow is: publish a revision, deliver a notification, let a user-enabled Shortcuts notification automation fetch the PNG, run Set Wallpaper, then record a receipt. The app itself cannot change wallpaper. A receipt only means the Set Wallpaper action returned; the tester must check the screen separately.

## Run

Use Xcode 27 or newer with an iPhone 17 Pro running iOS 27.0. Commands below run from the repository root. If Xcode lives elsewhere, set `DEVELOPER_DIR` to its `Contents/Developer` folder. The simulator script rejects older SDKs and runtimes.

For a fresh test device, install the standard iOS 27.0 runtime. The iOS 27.1 runtime offered by Xcode 27.1 build 27A9275 in this session supports only iPhone Duo; it is not the test target.

```sh
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild -downloadPlatform iOS -buildVersion 27.0
export MULBERRY_SIMULATOR="$(xcrun simctl create 'Mulberry iPhone 17 Pro iOS 27' com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro com.apple.CoreSimulator.SimRuntime.iOS-27-0)"
xcrun simctl boot "$MULBERRY_SIMULATOR"
```

Xcode 27 displays simulators in Device Hub, at `Xcode.app/Contents/Applications/DeviceHub.app`.

```sh
python3 apps/ios/mock/server.py
```

In another terminal, with an iOS 27 simulator booted:

```sh
apps/ios/scripts/run-simulator.sh
```

The default server is `http://127.0.0.1:8787`. The mock starts with a generated PNG and keeps state in memory. Use `--database /path/to/lab.sqlite` to retain it. Docker is optional:

```sh
docker compose -f apps/ios/compose.yaml up --build
```

In Mulberry POC, allow notifications and fetch the latest image. Install the supplied Shortcut through its share sheet. Use Choose wallpaper and enable updates to open the Shortcut editor. Check the wallpaper target in Set Wallpaper, expand the notification trigger, turn Automation on, and tap Back to save. Use Run once and allow access to finish its permission prompts and return to Mulberry. The chosen wallpaper must be the one currently shown on the phone.

The bundled Shortcut contains the three actions and an iOS 27 Notification trigger for Mulberry POC with a title containing `Mulberry canvas updated`. Its schema was exported from a physical iPhone 17 running iOS 27.0.1. The regenerated template imports with the trigger intact but **Automation turned off**. Expand the trigger and enable Automation once. First-run app and wallpaper permissions still need approval. `make-shortcut.py --manual-only` retains the three-action fallback.

```sh
node apps/ios/scripts/publish.mjs
```

This publishes a revision and uses `simctl push`. It does not send through APNs. The app also has a local notification test with a five-second delay. Neither test proves real push delivery, locked-phone behavior, or a production refresh rate.

After the notification automation is enabled, this sends three notifications five seconds apart:

```sh
python3 apps/ios/scripts/probe-rate.py --trigger notification --interval 5 --count 3
```

Use `--trigger shortcut` to isolate the actions by running the Shortcut directly. The output records requests, not successful wallpaper changes. Compare revision IDs with the app’s event journal and check the wallpaper itself.

## Structure

`Sources/DeliveryCore` owns HTTP, immutable image revisions, file storage, and the event journal. `Sources/Intents` exposes the fetch and receipt actions. `Sources/App` owns the test UI and notification callbacks. Notification callbacks never launch a Shortcut or count as a wallpaper change.

`mock` is a Python standard-library HTTP server with SQLite storage. Image URLs are immutable per revision. `scripts/generate-project.py` creates the checked-in Xcode project without extra build dependencies.

```sh
python3 apps/ios/scripts/test.py
```

The test runner starts a separate temporary mock server and runs both the Python and Swift tests. It does not add test receipts to the manual lab.

## Shortcut template

The readable source and signed import file live in `ShortcutTemplates`. Regenerate them with:

```sh
python3 apps/ios/scripts/make-shortcut.py
```

The default team ID is for an ad-hoc simulator build. For a signed iPhone build, pass `--team-id YOUR_TEAM_ID` and rebuild the app so its bundled template matches. Apple’s `shortcuts sign` command signs the import file.

The template passes the original fetch output to the receipt action. If another revision is published in the meantime, the receipt still names the image actually passed to Set Wallpaper. Preview, smart crop, and legibility blur are off.

## Evidence and limits

Files directly in `docs/evidence` came from an earlier iOS 26.3 component check and are historical only. Simulator evidence is in `docs/evidence/ios27`, from iPhone 17 Pro on iOS 27.0 (24A434). The app built with Xcode 27.1; all five core tests and two mock tests passed. Manual image fetch, signed Shortcut import, and manual Shortcut execution worked. A simulated remote notification for revision 12 fired the enabled notification automation, fetched revision 12, and recorded `shortcut_reported_applied` without tapping the notification. See the journal and trigger log for separate evidence of execution.

The wallpaper preview and applied Home Screen were black on iOS 27.0. The PNG displayed in Mulberry and Photos. Apple's poster logs contain Vision inference and image-processing failures. This prevents a visual success claim on the simulator. Real-device rendering subsequently passed; see `docs/evidence/iphone17`. Three manual launch requests roughly five seconds apart did not yield three matching receipts: revision 9 was skipped, revision 10 was fetched without a receipt, and revision 11 completed. This is not a reliable refresh-rate measurement or a published iOS limit.

## Physical-device setup

Use a physical iPhone running iOS 27, paired with this Mac through Xcode Device Hub. The owner must complete Trust, Developer Mode, restart, and passcode prompts. Xcode 27 Device Hub supports viewing and controlling a paired physical device through View Screen. A physical iPhone 17 on iOS 27.0.1 is now connected; viewing, tapping, and keyboard input were verified.

Build with an Apple development team and a matching provisioning profile. Regenerate the Shortcut with the real team ID. The default project uses an empty local-test entitlement file. The device script selects the separate development APNs entitlement only with --push.

The device script checks for a connected physical iPhone on iOS 27 and refuses to fall back to a simulator. It checks the SDK, regenerates the bundled Shortcut for the supplied team, builds with automatic signing, installs, and launches. Sign into the intended Apple account in Xcode first. The script defaults to local notifications and omits the APNs entitlement; pass `--push` only for push-enabled provisioning. It updates the template files in `ShortcutTemplates` with the chosen team ID.

```sh
python3 apps/ios/scripts/run-device.py --check
python3 apps/ios/scripts/run-device.py --team-id YOURTEAMID
```

If several phones are connected, also pass `--device DEVICE_ID`. Start a separate mock instance on the Mac's LAN address, using a different port if the simulator mock is still running:

```sh
python3 apps/ios/mock/server.py --host MAC_LAN_IP --port 8788
```

Set the app's server URL to `http://MAC_LAN_IP:8788` in that case. Personal Team signing, installation, launch, and a LAN image fetch were verified on iPhone 17 with iOS 27.0.1. The simulator's signed template can be restored with `python3 apps/ios/scripts/make-shortcut.py` before a later simulator build.

The mock server must be reachable from the phone. Run it bound to the Mac's LAN address and set the app's server URL to `http://MAC_LAN_IP:8787`, with phone and Mac on the same network. `127.0.0.1` on a physical phone refers to the phone. Allow the app's local-network prompt. Start with the app's delayed local notification to check the Shortcut and wallpaper, then use real APNs delivery to test server-driven updates. `simctl push` does not test a physical phone.

Real push testing needs the app's push-enabled App ID, development provisioning, a device token, and APNs authentication (or Apple's Push Notification Console). Check the actual wallpaper with the phone locked and unlocked, and repeat with the debugger detached. Focus, Low Power Mode, and rapid updates remain separate test cases.

The complete device test procedure and current handoff are in [docs/real-device-test.md](docs/real-device-test.md). The iPhoneOS target now builds with Personal Team signing and runs on the real iPhone. Hardware evidence is in `docs/evidence/iphone17`: a manual run changed the Home Screen, a local notification changed it in the background, and another changed the Lock Screen while locked. No notification was tapped. APNs, rate limits, Focus, and Low Power Mode are not established by these tests.

The event journal keeps the latest 500 events in the app’s Documents folder. Dates are seconds since 2001-01-01 UTC, as encoded by Swift’s default JSON date strategy. `image_ready`, `shortcut_reported_applied`, and `user_confirmed_wallpaper` are distinct outcomes. The last one is an explicit tester confirmation, not an OS readback.

This is a local lab. HTTP transport and an unauthenticated mock endpoint are intentional here. Production needs HTTPS, authentication, real APNs credentials, and physical-device checks. An APNs token is kept only on the device; it is not sent to the mock server.

Apple documents the notification trigger’s app and text filters in [the Shortcuts guide](https://support.apple.com/en-ng/guide/shortcuts/apd932ff833f/ios), and demonstrates the trigger in [What’s new in Shortcuts](https://developer.apple.com/videos/play/wwdc2026/310/).

## Setup health and notification cleanup

The Setup tab reads notification permission and presentation settings whenever the app is active. It shows permission loss immediately on return, and asks for another test after delivery settings change. A local test has a 45-second diagnostic window; this is a UI timeout, not an iOS limit. No matching completion produces “Test did not finish.” A late receipt can still resolve it. Manual runs started from the app do not count as notification-trigger tests.

The app cannot enumerate, inspect, enable, or repair saved Shortcuts through a public API used by this POC. It cannot distinguish disabled, deleted, renamed, malformed, or permission-blocked Shortcuts from a missing test receipt. Nor can it read the actual wallpaper pixels or prove an edited Shortcut still contains Set Wallpaper before the receipt. Success is labelled “Last test completed,” with a timestamp, never continuous live validity.

The receipt action removes delivered notifications marked `kind: wallpaper_update` with a positive revision at or below the completed image. Unknown, unrelated, and newer notices remain. Notifications use passive interruption level with no sound or badge. Local quiet delivery with Notification Center only passed on the phone; see `docs/evidence/iphone17-cleanup`. Remote APNs still requires separate validation.

The wallpaper import question was removed after reproducing a stuck Add Shortcut screen. Select the target in the editor instead. Existing phone wallpaper choices can be prefilled; do not assume the first run always creates a new pair. Only one Mulberry automation should be enabled. Turn old copies off before replacement. The app uses Apple's x-callback-url flow to return after a manual run or report an error; the callback alone never records wallpaper success.

## Remote wallpaper delivery

The iOS app registers an FCM token only after APNs registration succeeds. The backend accepts `IOS` device tokens and sends passive APNs alerts titled `Mulberry canvas updated`, which match the supplied Shortcut trigger. Android continues receiving its existing data-only payload. A completed Shortcut removes only matching canvas notifications through the rendered revision; it does not remove newer updates or account notices.

Local personal-team provisioning does not include `aps-environment`. Local Shortcut tests can run with that profile, but a passing local test does not prove remote partner delivery. For remote delivery, enroll the same personal identity in the Apple Developer Program, enable Push Notifications for the app ID, add its APNs authentication key to the corresponding Firebase iOS app, and build with push-enabled provisioning:

```sh
python3 apps/ios/scripts/run-device.py --device 00008150-001455913C83C01C --team-id YGJRT89MH9 --push
```

The APNs key belongs in Firebase, never in the app bundle or repository. The build copies only `Configuration/GoogleService-Info.dev.plist` for Debug or `GoogleService-Info.prod.plist` for Release and rejects a mismatched bundle ID. Release requires the production client file. Configure production OAuth client IDs separately before distributing a release; development OAuth configuration must not be copied into production.

Physical remote testing still needs a drawing from the paired Android account while the iPhone is locked, the debug connection detached, and the Shortcut automation enabled. Verify the wallpaper, matching receipt revision, and notification removal. Do not infer this result from simulator screenshots or foreground local tests.
