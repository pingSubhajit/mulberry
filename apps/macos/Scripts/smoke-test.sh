#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [ -z "${DEVELOPER_DIR:-}" ] && [ -d "/Applications/Xcode.app/Contents/Developer" ]; then
  export DEVELOPER_DIR="/Applications/Xcode.app/Contents/Developer"
fi

swift test
swift build --product Mulberry
swift run OverlayRegressionCheck
# CI checks the bundle without Apple credentials or private client configuration.
MULBERRY_MAC_UNSIGNED_BUILD=YES CONFIGURATION=debug ENVIRONMENT=dev ./Scripts/build-app.sh
test -x .build/xcode/Build/Products/Debug/Mulberry.app/Contents/MacOS/Mulberry
plutil -lint .build/xcode/Build/Products/Debug/Mulberry.app/Contents/Info.plist >/dev/null

echo "macOS smoke test passed"
