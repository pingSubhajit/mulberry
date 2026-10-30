#!/bin/bash
set -euo pipefail
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
IOS_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHECK_DIR="$(mktemp -d /tmp/mulberry-offline-checks.XXXXXX)"
trap 'rm -rf "$CHECK_DIR"' EXIT
APP="$CHECK_DIR/OfflineChecks.app"
mkdir -p "$APP"
cat > "$APP/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict><key>CFBundleIdentifier</key><string>my.mulberry.offline-checks</string><key>CFBundleExecutable</key><string>OfflineChecks</string><key>CFBundleName</key><string>OfflineChecks</string><key>CFBundlePackageType</key><string>APPL</string></dict></plist>
PLIST
cp "$IOS_ROOT/../../packages/apple/Tests/ProductCoreTests/Fixtures/bootstrap.json" "$APP/"
xcrun actool "$IOS_ROOT/Resources/Assets.xcassets" --compile "$APP" --platform iphonesimulator --minimum-deployment-target 27.0 --target-device iphone > "$CHECK_DIR/assets.log" 2>&1 || { cat "$CHECK_DIR/assets.log"; exit 1; }
SDK_PATH="$(xcrun --sdk iphonesimulator --show-sdk-path)"
xcrun --sdk iphonesimulator swiftc -sdk "$SDK_PATH" -target "$(uname -m)-apple-ios27.0-simulator" \
  "$IOS_ROOT"/../../packages/apple/Sources/ProductCore/*.swift "$IOS_ROOT"/Sources/DeliveryCore/*.swift \
  "$IOS_ROOT/Sources/App/MulberryDesign.swift" "$IOS_ROOT/Sources/App/CanvasViewportStore.swift" "$IOS_ROOT/Sources/App/DoodleWallpaper.swift" "$IOS_ROOT"/../../packages/apple/Sources/CanvasDrawing/*.swift "$IOS_ROOT/Sources/App/StickerArtwork.swift" "$IOS_ROOT"/../../packages/apple/Sources/StickerAssets/*.swift \
  "$IOS_ROOT/Sources/App/DoodleEditor.swift" "$IOS_ROOT/Sources/App/MulberryModel.swift" "$IOS_ROOT/Sources/App/WallpaperLibrary.swift" \
  "$IOS_ROOT/Sources/App/ProductWallpaperService.swift" "$IOS_ROOT/Sources/App/WallpaperNotifications.swift" \
  "$IOS_ROOT/Sources/App/ReactionsModel.swift" "$IOS_ROOT/Sources/App/ReactionActivities.swift" "$IOS_ROOT"/Sources/Shared/*.swift \
  "$IOS_ROOT/Tests/Offline/OfflineWallpaperChecks.swift" -o "$APP/OfflineChecks"
xcrun simctl spawn "${1:-booted}" "$APP/OfflineChecks"
