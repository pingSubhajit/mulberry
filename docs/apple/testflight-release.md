# Apple TestFlight release handoff

## Scope and checkpoint

Production APNs preparation is complete. App Store Connect record creation, distribution signing, archives, uploads and tester invitations wait for the owner's TestFlight go-ahead. The owner will test the apps through TestFlight. Sign in with Apple is deferred; the apps keep their existing Google sign-in flow.

On 31 October 2026, the Techaboard account (`HMJX9736KN`) created **Mulberry Production Push**, key ID `LP6U5FYA39`, restricted to Production and the single topic `com.subhajit.mulberry.beta`. Firebase project `mulberry-494003` shows this key in the beta Apple app's production APNs slot. The existing sandbox key `H8HL44RC8J` remains in the development slot. Both native apps reuse that Firebase registration and bundle ID. The private key is stored only in ignored `secrets/apple/` with owner-only access; do not put it in app resources, build settings, Git, Fly secrets or this document.

Firebase accepting the key confirms configuration, not delivery to a TestFlight installation. No production push was sent in this preparation pass.

Xcode's resolved Release settings were checked for both app projects: production environment, Techaboard team, beta identity, normal icon, production APNs and no restriction to the host's active architecture. iOS resolves `https://api.mulberry.my/` with local-server access off. The Mac's release client configuration also targets that live API. The Mac archive supports arm64 and x86_64 by default; the local development packaging script intentionally builds only the host architecture and must not be used to prepare TestFlight.

Only Apple Development identities were present locally at this checkpoint. Distribution certificates/profiles, including Mac installer signing as required by export, have not been created or validated. Use Xcode's automatic distribution signing after the go-ahead; do not revoke or replace certificates belonging to other apps.

## App Store Connect, after the go-ahead

Create one app record in Techaboard for `com.subhajit.mulberry.beta`, with iOS and macOS platforms under that record. Do not create two separate records. This is the intended universal purchase arrangement; each platform still has its own archive, build processing and TestFlight build assignment. No record exists at this checkpoint. Firebase remains in the owner's account.

Read the current source version and build from each platform's Info.plist before creating its version/build. At this checkpoint iOS and its widget use version `0.1`, build `1`; macOS uses version `0.1.0`, build `1`. Different platform versions are allowed. For subsequent uploads, increment the relevant source build number, keeping each app aligned with its widget (`Widgets-Info.plist` on both platforms); the values are currently literal plist strings, not CURRENT_PROJECT_VERSION substitutions.

Provide beta test information, feedback/review contact details and the export-compliance answers. Create tester groups and assign each platform's processed build. Begin with internal testing; external testers require Apple's beta review. Full public App Store release is a separate action.

## Archive and export, after the go-ahead

Run from the repository root. These commands are prepared instructions; they were **not executed** during APNs preparation. Archive/export logs and bundles stay under ignored `temp/`. Regenerate projects first only if their generator inputs changed. Use the existing package checkouts and locked dependency versions.

```sh
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
mkdir -p temp/apple-release

xcodebuild archive \
  -project apps/ios/Mulberry.xcodeproj \
  -scheme 'Mulberry Production' -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath "$PWD/temp/apple-release/Mulberry-iOS.xcarchive" \
  -derivedDataPath "$PWD/apps/ios/.build/distribution" \
  -clonedSourcePackagesDirPath "$PWD/apps/ios/.build/device/SourcePackages" \
  -disableAutomaticPackageResolution -allowProvisioningUpdates \
  DEVELOPMENT_TEAM=HMJX9736KN CODE_SIGN_STYLE=Automatic \
  MULBERRY_API_BASE_URL=https://api.mulberry.my/ \
  MULBERRY_ALLOW_LOCAL_SERVER=NO MULBERRY_LOCAL_DEVICE_BUILD=NO \
  MULBERRY_SOURCE_PACKAGES_DIR="$PWD/apps/ios/.build/device/SourcePackages" \
  APS_ENVIRONMENT=production ONLY_ACTIVE_ARCH=NO

xcodebuild archive \
  -project apps/macos/MulberryMac.xcodeproj \
  -scheme 'Mulberry Production' -configuration Release \
  -destination 'generic/platform=macOS' \
  -archivePath "$PWD/temp/apple-release/Mulberry-macOS.xcarchive" \
  -derivedDataPath "$PWD/apps/macos/.build/distribution" \
  -clonedSourcePackagesDirPath "$PWD/apps/ios/.build/device/SourcePackages" \
  -disableAutomaticPackageResolution -allowProvisioningUpdates \
  DEVELOPMENT_TEAM=HMJX9736KN CODE_SIGN_STYLE=Automatic \
  MULBERRY_MAC_API_BASE_URL=https://api.mulberry.my/ \
  MULBERRY_ALLOW_LOCAL_SERVER=NO MULBERRY_MAC_UNSIGNED_BUILD=NO \
  MULBERRY_SOURCE_PACKAGES_DIR="$PWD/apps/ios/.build/device/SourcePackages" \
  MULBERRY_APNS_ENVIRONMENT=production ONLY_ACTIVE_ARCH=NO

xcodebuild -exportArchive \
  -archivePath "$PWD/temp/apple-release/Mulberry-iOS.xcarchive" \
  -exportPath "$PWD/temp/apple-release/iOS" \
  -exportOptionsPlist scripts/apple/ExportOptions.plist \
  -allowProvisioningUpdates

xcodebuild -exportArchive \
  -archivePath "$PWD/temp/apple-release/Mulberry-macOS.xcarchive" \
  -exportPath "$PWD/temp/apple-release/macOS" \
  -exportOptionsPlist scripts/apple/ExportOptions.plist \
  -allowProvisioningUpdates
```

Both archives upload their dSYMs to Crashlytics as they build. `MULBERRY_SOURCE_PACKAGES_DIR` tells the upload step where the Firebase package was checked out, since `-clonedSourcePackagesDirPath` puts it outside the derived data; without it the archive fails at that step.

The shared export plist selects `app-store-connect` with `destination=export`: it prepares a local distribution package and does not upload it. Xcode may re-sign an automatically signed archive during export. Verify the **exported** app's signature, embedded profiles, production APNs, beta identity, live API, normal icon, Firebase project and bundled widget before upload. Check the Mac exported architecture list and its sandbox entitlements. Do not upload a locally installed development-signed `.app` or use Developer ID distribution for TestFlight.

Validate and upload each platform through Xcode Organizer or Transporter under the same app record. Resolve actual upload/processing errors before assigning builds. Production notification delivery and the user's full flow testing then occur through TestFlight, as requested.

## Known validation limits

The existing macOS CI golden image comparison failure predates this release preparation. Local beta validation and the production backend checks are recorded separately in the platform validation documents. Neither proves that a distribution archive has passed Apple validation. No Apple runtime feature or rendering change was made in this preparation pass.

## References

[Apple: add platforms and universal purchase](https://developer.apple.com/help/app-store-connect/create-an-app-record/add-platforms), [Apple: TestFlight process](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview/), [Firebase: upload APNs authentication keys](https://firebase.google.com/docs/cloud-messaging/ios/get-started). Export option names were checked against this Mac's Xcode 27.2 `xcodebuild -help`.
