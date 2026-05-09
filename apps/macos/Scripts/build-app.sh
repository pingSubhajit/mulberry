#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

CONFIGURATION="${CONFIGURATION:-debug}"
APP_NAME="Mulberry"
BUNDLE_ID="com.mulberry.mac"
BUILD_DIR=".build/${CONFIGURATION}"
APP_DIR=".build/app/${APP_NAME}.app"
CONTENTS_DIR="${APP_DIR}/Contents"
MACOS_DIR="${CONTENTS_DIR}/MacOS"
RESOURCES_DIR="${CONTENTS_DIR}/Resources"
EXECUTABLE_PATH="${MACOS_DIR}/MulberryMac"
INFO_PLIST_TEMPLATE="Configuration/Info.plist"
INFO_PLIST_PATH="${CONTENTS_DIR}/Info.plist"
ENTITLEMENTS_PATH="${ENTITLEMENTS_PATH:-Configuration/Debug.entitlements}"
SIGNING_IDENTITY="${MULBERRY_MAC_CODESIGN_IDENTITY:--}"

swift build -c "${CONFIGURATION}" --product MulberryMac

rm -rf "${APP_DIR}"
mkdir -p "${MACOS_DIR}" "${RESOURCES_DIR}"
cp "${BUILD_DIR}/MulberryMac" "${EXECUTABLE_PATH}"
cp "${INFO_PLIST_TEMPLATE}" "${INFO_PLIST_PATH}"

if [ -d "Resources" ]; then
  rsync -a --exclude ".DS_Store" Resources/ "${RESOURCES_DIR}/"
fi

/usr/libexec/PlistBuddy -c "Set :CFBundleIdentifier ${BUNDLE_ID}" "${INFO_PLIST_PATH}" >/dev/null
/usr/libexec/PlistBuddy -c "Set :CFBundleExecutable MulberryMac" "${INFO_PLIST_PATH}" >/dev/null

codesign --force --sign "${SIGNING_IDENTITY}" --entitlements "${ENTITLEMENTS_PATH}" "${APP_DIR}"

echo "Built ${APP_DIR}"
