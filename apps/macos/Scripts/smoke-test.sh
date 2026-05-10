#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

swift build --product MulberryMac
swift run OverlayRegressionCheck
./Scripts/build-app.sh
test -x .build/app/Mulberry.app/Contents/MacOS/MulberryMac
plutil -lint .build/app/Mulberry.app/Contents/Info.plist >/dev/null

echo "macOS smoke test passed"
