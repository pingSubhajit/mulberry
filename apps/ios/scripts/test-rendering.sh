#!/bin/bash
set -euo pipefail
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
IOS_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHECK_DIR="$(mktemp -d /tmp/mulberry-render-checks.XXXXXX)"
trap 'rm -rf "$CHECK_DIR"' EXIT
SDK_PATH="$(xcrun --sdk iphonesimulator --show-sdk-path)"
xcrun --sdk iphonesimulator swiftc -sdk "$SDK_PATH" -target "$(uname -m)-apple-ios27.0-simulator" \
  "$IOS_ROOT"/Sources/ProductCore/*.swift \
  "$IOS_ROOT/Sources/App/MulberryDesign.swift" \
  "$IOS_ROOT/Sources/App/CanvasGeometry.swift" "$IOS_ROOT/Sources/App/DoodleRenderer.swift" \
  "$IOS_ROOT/Sources/App/CanvasElementRenderer.swift" \
  "$IOS_ROOT/Tests/Rendering/WallpaperRenderingChecks.swift" \
  -o "$CHECK_DIR/check-rendering"
xcrun simctl spawn "${1:-booted}" "$CHECK_DIR/check-rendering"
