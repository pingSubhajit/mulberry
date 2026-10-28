#!/bin/bash
# Builds an isolated Settings harness with a sample account. Extra arguments are
# passed to the app: "unpaired", "hidden", or a page ("profile", "partner", "about").
set -euo pipefail
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
IOS_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEVICE="${1:-booted}"; shift || true
APP="$IOS_ROOT/.build/settings-preview/SettingsPreview.app"
mkdir -p "$APP"
python3 - "$APP" "$IOS_ROOT" <<'PY'
import plistlib,sys,pathlib,shutil
app,root=map(pathlib.Path,sys.argv[1:])
fonts=list((root/'Resources').rglob('*.ttf'))
for f in fonts: shutil.copyfile(f,app/f.name)
shutil.copytree(root/'Resources/Legal',app/'Legal',dirs_exist_ok=True)
plist={'CFBundleIdentifier':'my.mulberry.settings-preview','CFBundleExecutable':'SettingsPreview','CFBundleName':'Settings Preview','CFBundlePackageType':'APPL','CFBundleVersion':'12','CFBundleShortVersionString':'1.0','MinimumOSVersion':'27.0','LSRequiresIPhoneOS':True,'UIDeviceFamily':[1],'UILaunchScreen':{},'UIAppFonts':[f.name for f in fonts]}
(app/'Info.plist').write_bytes(plistlib.dumps(plist))
PY
xcrun actool "$IOS_ROOT/Resources/Assets.xcassets" --compile "$APP" --platform iphonesimulator --minimum-deployment-target 27.0 --target-device iphone > /tmp/mulberry-settings-preview-assets.log 2>&1
SDK_PATH="$(xcrun --sdk iphonesimulator --show-sdk-path)"
APP_SOURCES=(MulberryDesign CanvasGeometry DoodleRenderer CanvasElementRenderer CanvasEditorChrome CanvasTextEditorView
  CanvasStickerEditorView StickerAssets CustomColourSheet DoodleEditor MulberryModel StreakModel StreakViews CelebrationConfetti
  WallpaperLibrary ProductWallpaperService WallpaperNotifications PairingViews OnboardingViews SettingsViews)
APP_FILES=(); for name in "${APP_SOURCES[@]}"; do APP_FILES+=("$IOS_ROOT/Sources/App/$name.swift"); done
xcrun --sdk iphonesimulator swiftc -sdk "$SDK_PATH" -target "$(uname -m)-apple-ios27.0-simulator" -parse-as-library \
  "$IOS_ROOT"/Sources/ProductCore/*.swift "$IOS_ROOT"/Sources/DeliveryCore/*.swift \
  "${APP_FILES[@]}" \
  "$IOS_ROOT/Tests/SettingsPreview/SettingsPreview.swift" \
  -o "$APP/SettingsPreview" 2>&1 | grep -v "^$" || true
test -x "$APP/SettingsPreview"
xcrun simctl install "$DEVICE" "$APP"
xcrun simctl launch --terminate-running-process "$DEVICE" my.mulberry.settings-preview "$@"
