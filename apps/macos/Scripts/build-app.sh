#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

CONFIGURATION="${CONFIGURATION:-debug}"
APP_NAME="Mulberry"
# One App Store record with the iPhone and iPad app, as Universal Purchase: the same bundle IDs,
# and the same icons from Icon Composer, the development build's purple.
if [ "${CONFIGURATION}" = "release" ]; then
  BUNDLE_ID="com.subhajit.mulberry"
  APP_ICON="AppIcon"
  DEFAULT_ENTITLEMENTS="Configuration/Release.entitlements"
else
  BUNDLE_ID="com.subhajit.mulberry.dev"
  APP_ICON="AppIconDev"
  DEFAULT_ENTITLEMENTS="Configuration/Debug.entitlements"
fi
# actool takes absolute paths only.
ICON_SOURCE="$(pwd)/../ios/Resources/${APP_ICON}.icon"
BUILD_DIR=".build/${CONFIGURATION}"
APP_DIR=".build/app/${APP_NAME}.app"
CONTENTS_DIR="${APP_DIR}/Contents"
MACOS_DIR="${CONTENTS_DIR}/MacOS"
RESOURCES_DIR="${CONTENTS_DIR}/Resources"
EXECUTABLE_PATH="${MACOS_DIR}/${APP_NAME}"
INFO_PLIST_TEMPLATE="Configuration/Info.plist"
INFO_PLIST_PATH="${CONTENTS_DIR}/Info.plist"
ENTITLEMENTS_PATH="${ENTITLEMENTS_PATH:-${DEFAULT_ENTITLEMENTS}}"
# Signing with a stable identity lets the Keychain keep trusting rebuilds; ad hoc signatures change
# every build, so macOS asks for the login password again. Use an Apple Development certificate when
# this Mac has one, unless MULBERRY_MAC_CODESIGN_IDENTITY names another ("-" signs ad hoc).
if [ -z "${MULBERRY_MAC_CODESIGN_IDENTITY:-}" ]; then
  MULBERRY_MAC_CODESIGN_IDENTITY="$(security find-identity -v -p codesigning 2>/dev/null | sed -n 's/.*"\(Apple Development: [^"]*\)".*/\1/p' | head -n 1)"
fi
SIGNING_IDENTITY="${MULBERRY_MAC_CODESIGN_IDENTITY:--}"

swift build -c "${CONFIGURATION}" --product "${APP_NAME}"

rm -rf "${APP_DIR}"
mkdir -p "${MACOS_DIR}" "${RESOURCES_DIR}"
cp "${BUILD_DIR}/${APP_NAME}" "${EXECUTABLE_PATH}"
cp "${INFO_PLIST_TEMPLATE}" "${INFO_PLIST_PATH}"

if [ -d "Resources" ]; then
  rsync -a --exclude ".DS_Store" Resources/ "${RESOURCES_DIR}/"
fi

if [ -f ".env" ]; then
  cp ".env" "${RESOURCES_DIR}/.env"
fi

# The icon, compiled for macOS: a Liquid Glass icon in Assets.car, and an .icns for older places.
ICON_PLIST="$(mktemp -t mulberry-icon).plist"
xcrun actool --compile "$(pwd)/${RESOURCES_DIR}" --platform macosx --minimum-deployment-target 26.0 \
  --app-icon "${APP_ICON}" --output-partial-info-plist "${ICON_PLIST}" "${ICON_SOURCE}" >/dev/null
/usr/libexec/PlistBuddy -c "Set :CFBundleIconFile ${APP_ICON}" -c "Set :CFBundleIconName ${APP_ICON}" "${INFO_PLIST_PATH}" >/dev/null
rm -f "${ICON_PLIST}"

/usr/libexec/PlistBuddy -c "Set :CFBundleIdentifier ${BUNDLE_ID}" "${INFO_PLIST_PATH}" >/dev/null
/usr/libexec/PlistBuddy -c "Set :CFBundleExecutable ${APP_NAME}" "${INFO_PLIST_PATH}" >/dev/null

codesign --force --sign "${SIGNING_IDENTITY}" --entitlements "${ENTITLEMENTS_PATH}" "${APP_DIR}"

echo "Built ${APP_DIR}"
