#!/bin/bash
# Builds an isolated harness for the canvas text and sticker tools. Extra
# arguments are passed to the app: "sticker", a panel (font, size, rotation,
# colour), "pill", "edit" or "canvas". Stickers load from the local backend.
set -euo pipefail
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
IOS_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEVICE="${1:-booted}"; shift || true
APP="$IOS_ROOT/.build/canvas-editors-preview/CanvasEditorsPreview.app"
mkdir -p "$APP"
python3 - "$APP" "$IOS_ROOT" <<'PY'
import plistlib,sys,pathlib,shutil
app,root=map(pathlib.Path,sys.argv[1:])
fonts=list((root/'Resources').rglob('*.ttf'))
for f in fonts: shutil.copyfile(f,app/f.name)
plist={'CFBundleIdentifier':'my.mulberry.canvas-editors-preview','CFBundleExecutable':'CanvasEditorsPreview','CFBundleName':'Canvas Preview','NSAppTransportSecurity':{'NSAllowsLocalNetworking':True},'CFBundlePackageType':'APPL','CFBundleVersion':'1','CFBundleShortVersionString':'1.0','MinimumOSVersion':'27.0','LSRequiresIPhoneOS':True,'UIDeviceFamily':[1],'UILaunchScreen':{},'UIAppFonts':[f.name for f in fonts]}
(app/'Info.plist').write_bytes(plistlib.dumps(plist))
PY
xcrun actool "$IOS_ROOT/Resources/Assets.xcassets" --compile "$APP" --platform iphonesimulator --minimum-deployment-target 27.0 --target-device iphone > /tmp/mulberry-canvas-preview-assets.log 2>&1
SDK_PATH="$(xcrun --sdk iphonesimulator --show-sdk-path)"
xcrun --sdk iphonesimulator swiftc -sdk "$SDK_PATH" -target "$(uname -m)-apple-ios27.0-simulator" -parse-as-library \
  "$IOS_ROOT"/../../packages/apple/Sources/ProductCore/*.swift "$IOS_ROOT/Sources/App/MulberryDesign.swift" \
  "$IOS_ROOT/Sources/App/CanvasViewportStore.swift" "$IOS_ROOT/Sources/App/DoodleWallpaper.swift" \
  "$IOS_ROOT"/../../packages/apple/Sources/CanvasDrawing/*.swift "$IOS_ROOT/Sources/App/CanvasTextEditorView.swift" \
  "$IOS_ROOT/Sources/App/CanvasEditorChrome.swift" "$IOS_ROOT/Sources/App/CanvasStickerEditorView.swift" \
  "$IOS_ROOT/Sources/App/StickerArtwork.swift" "$IOS_ROOT"/../../packages/apple/Sources/StickerAssets/*.swift \
  "$IOS_ROOT/Sources/App/CustomColourSheet.swift" "$IOS_ROOT/Sources/App/ColourNames.swift" \
  "$IOS_ROOT/Sources/App/ReactionsModel.swift" "$IOS_ROOT/Sources/App/ReactionActivities.swift" "$IOS_ROOT"/Sources/Shared/*.swift "$IOS_ROOT/Sources/App/DoodleEditor.swift" "$IOS_ROOT/Sources/App/PalmRejection.swift" "$IOS_ROOT/Sources/App/PencilInput.swift" "$IOS_ROOT/Sources/App/StrokeCanvasView.swift" \
  "$IOS_ROOT/Sources/App/MulberryModel.swift" "$IOS_ROOT/Sources/App/CanvasModel.swift" \
  "$IOS_ROOT/Sources/App/WallpaperLibrary.swift" "$IOS_ROOT/Sources/App/ProductWallpaperService.swift" \
  "$IOS_ROOT/Sources/App/WallpaperNotifications.swift" "$IOS_ROOT"/Sources/DeliveryCore/*.swift \
  "$IOS_ROOT/Tests/CanvasEditors/CanvasEditorsPreview.swift" \
  -o "$APP/CanvasEditorsPreview"
xcrun simctl install "$DEVICE" "$APP"
xcrun simctl launch --terminate-running-process "$DEVICE" my.mulberry.canvas-editors-preview "$@"
