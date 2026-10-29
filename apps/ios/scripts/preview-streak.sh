#!/bin/bash
set -euo pipefail
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
IOS_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$IOS_ROOT/.build/streak-preview/StreakPreview.app"
mkdir -p "$APP"
python3 - "$APP" "$IOS_ROOT" <<'PY'
import plistlib,sys,pathlib,shutil
app,root=map(pathlib.Path,sys.argv[1:])
fonts=list((root/'Resources').rglob('*.ttf'))
for f in fonts: shutil.copyfile(f,app/f.name)
plist={'CFBundleIdentifier':'my.mulberry.streak-preview','CFBundleExecutable':'StreakPreview','CFBundleName':'Streak Preview','CFBundlePackageType':'APPL','CFBundleVersion':'1','CFBundleShortVersionString':'1.0','MinimumOSVersion':'27.0','LSRequiresIPhoneOS':True,'UIDeviceFamily':[1],'UILaunchScreen':{},'UIAppFonts':[f.name for f in fonts]}
(app/'Info.plist').write_bytes(plistlib.dumps(plist))
PY
xcrun actool "$IOS_ROOT/Resources/Assets.xcassets" --compile "$APP" --platform iphonesimulator --minimum-deployment-target 27.0 --target-device iphone > /tmp/mulberry-streak-preview-assets.log 2>&1
SDK_PATH="$(xcrun --sdk iphonesimulator --show-sdk-path)"
xcrun --sdk iphonesimulator swiftc -sdk "$SDK_PATH" -target "$(uname -m)-apple-ios27.0-simulator" \
  "$IOS_ROOT"/../../packages/apple/Sources/ProductCore/*.swift "$IOS_ROOT/Sources/App/MulberryDesign.swift" \
  "$IOS_ROOT/Sources/App/StreakModel.swift" "$IOS_ROOT/Sources/App/StreakViews.swift" \
  "$IOS_ROOT/Sources/App/CelebrationConfetti.swift" "$IOS_ROOT/Tests/Streak/StreakPreview.swift" \
  -o "$APP/StreakPreview"
xcrun simctl install "${1:-booted}" "$APP"
xcrun simctl launch --terminate-running-process "${1:-booted}" my.mulberry.streak-preview
