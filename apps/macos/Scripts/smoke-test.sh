#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [ -z "${DEVELOPER_DIR:-}" ] && [ -d "/Applications/Xcode.app/Contents/Developer" ]; then
  export DEVELOPER_DIR="/Applications/Xcode.app/Contents/Developer"
fi

swift test
swift build --product Mulberry
swift run OverlayRegressionCheck
swift run CanvasRenderFixtureCheck
./Scripts/build-app.sh
test -x .build/app/Mulberry.app/Contents/MacOS/Mulberry
plutil -lint .build/app/Mulberry.app/Contents/Info.plist >/dev/null

echo "macOS smoke test passed"
