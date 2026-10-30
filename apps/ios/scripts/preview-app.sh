#!/bin/bash
# Builds the whole app shell with a cached sample account and no real server, for
# layout checks on any simulator, iPhone or iPad. Usage: preview-app.sh <device> [arguments],
# where the arguments are passed to the app ("unpaired", "signin", "wallpapers").
set -euo pipefail
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
IOS_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEVICE="${1:-booted}"; shift || true
APP="$IOS_ROOT/.build/app-preview/AppPreview.app"
mkdir -p "$APP"
python3 - "$APP" "$IOS_ROOT" <<'PY'
import plistlib,sys,pathlib,shutil
app,root=map(pathlib.Path,sys.argv[1:])
fonts=list((root/'Resources').rglob('*.ttf'))
for f in fonts: shutil.copyfile(f,app/f.name)
shutil.copytree(root/'Resources/Legal',app/'Legal',dirs_exist_ok=True)
shutil.copytree(root/'ShortcutTemplates',app/'ShortcutTemplates',dirs_exist_ok=True)
source=plistlib.loads((root/'Configuration/Info.plist').read_bytes())
plist={'CFBundleIdentifier':'my.mulberry.app-preview','CFBundleExecutable':'AppPreview','CFBundleName':'App Preview','CFBundlePackageType':'APPL',
       'CFBundleVersion':'1','CFBundleShortVersionString':'1.0','MinimumOSVersion':'27.0','LSRequiresIPhoneOS':True,'UIDeviceFamily':[1,2],
       'UILaunchScreen':{},'UIAppFonts':[f.name for f in fonts]}
for key in ('UISupportedInterfaceOrientations','UISupportedInterfaceOrientations~ipad'):
    if key in source: plist[key]=source[key]
(app/'Info.plist').write_bytes(plistlib.dumps(plist))
PY
xcrun actool "$IOS_ROOT/Resources/Assets.xcassets" --compile "$APP" --platform iphonesimulator --minimum-deployment-target 27.0 \
  --target-device iphone --target-device ipad > /tmp/mulberry-app-preview-assets.log 2>&1
# Everything except the @main app and the pieces that need Google, Firebase or a real server, which the harness stubs.
APP_FILES=()
for file in "$IOS_ROOT"/Sources/App/*.swift; do
  case "$(basename "$file")" in
    MulberryApp.swift|AppRuntime.swift|GoogleAuthentication.swift|PushRegistration.swift|NotificationBridge.swift|CrashReporting.swift) ;;
    *) APP_FILES+=("$file") ;;
  esac
done
SDK_PATH="$(xcrun --sdk iphonesimulator --show-sdk-path)"
xcrun --sdk iphonesimulator swiftc -sdk "$SDK_PATH" -target "$(uname -m)-apple-ios27.0-simulator" -parse-as-library \
  "$IOS_ROOT"/../../packages/apple/Sources/ProductCore/*.swift "$IOS_ROOT"/../../packages/apple/Sources/CanvasDrawing/*.swift "$IOS_ROOT"/../../packages/apple/Sources/StickerAssets/*.swift "$IOS_ROOT"/../../packages/apple/Sources/ProductUI/*.swift "$IOS_ROOT"/Sources/DeliveryCore/*.swift "$IOS_ROOT"/Sources/Shared/*.swift "${APP_FILES[@]}" \
  "$IOS_ROOT/Tests/AppPreview/AppPreview.swift" -o "$APP/AppPreview" 2>&1 | grep -E "error|warning: .*AppPreview" || true
test -x "$APP/AppPreview"
xcrun simctl install "$DEVICE" "$APP"
xcrun simctl launch --terminate-running-process "$DEVICE" my.mulberry.app-preview "$@"
