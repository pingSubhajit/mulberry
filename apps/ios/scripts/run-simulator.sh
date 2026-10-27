#!/bin/bash
set -euo pipefail
IOS_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
SDK_VERSION="$(xcrun --sdk iphonesimulator --show-sdk-version)"
if [ "${SDK_VERSION%%.*}" -lt 27 ]; then
  echo "iOS 27 SDK required. Select Xcode 27 or newer with DEVELOPER_DIR." >&2
  exit 1
fi
SIM_DEVICE="${MULBERRY_SIMULATOR:-booted}"
if [ "$SIM_DEVICE" = booted ]; then
  SIM_DEVICE="$(xcrun simctl list devices booted -j | python3 -c 'import json,sys; devices=[d["udid"] for a in json.load(sys.stdin)["devices"].values() for d in a if d["state"]=="Booted"]; assert len(devices)==1, "Boot one iPhone simulator, or set MULBERRY_SIMULATOR to its UDID"; print(devices[0])')"
fi
xcrun simctl list devices -j | python3 -c '
import json, sys
device = sys.argv[1]
for runtime, devices in json.load(sys.stdin)["devices"].items():
    if any(item["udid"] == device for item in devices):
        if "iOS-" not in runtime or int(runtime.split("iOS-")[1].split("-")[0]) < 27:
            sys.exit("Choose an iOS 27 or newer simulator. Older runtimes are not supported.")
        break
else:
    sys.exit("Simulator was not found")
' "$SIM_DEVICE"
xcrun simctl bootstatus "$SIM_DEVICE" -b
python3 "$IOS_ROOT/scripts/generate-project.py"
SIGNING_ARGS=(CODE_SIGN_IDENTITY=-)
if [ -n "${MULBERRY_TEAM_ID:-}" ]; then
  SIGNING_ARGS+=("DEVELOPMENT_TEAM=$MULBERRY_TEAM_ID")
fi
xcodebuild -project "$IOS_ROOT/MulberryDelivery.xcodeproj" -scheme MulberryDelivery \
  -configuration Debug -destination "platform=iOS Simulator,id=$SIM_DEVICE" \
  -derivedDataPath "$IOS_ROOT/.build/xcode" "${SIGNING_ARGS[@]}" build
xcrun simctl install "$SIM_DEVICE" "$IOS_ROOT/.build/xcode/Build/Products/Debug-iphonesimulator/MulberryDelivery.app"
xcrun simctl launch "$SIM_DEVICE" com.subhajit.mulberry.dev
