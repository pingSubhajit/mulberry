#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [ -z "${DEVELOPER_DIR:-}" ] && [ -d "/Applications/Xcode.app/Contents/Developer" ]; then
  export DEVELOPER_DIR="/Applications/Xcode.app/Contents/Developer"
fi

swift test
swift build --product MulberryMac
swift run OverlayRegressionCheck
swift run CanvasRenderFixtureCheck
./Scripts/build-app.sh
test -x .build/app/Mulberry.app/Contents/MacOS/MulberryMac
plutil -lint .build/app/Mulberry.app/Contents/Info.plist >/dev/null

echo "macOS smoke test passed"
