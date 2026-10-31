#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
HOST_ARCH="$(uname -m)"
CONFIGURATION="${CONFIGURATION:-debug}"
if [ "$CONFIGURATION" = release ]; then DEFAULT_ENV=prod; else DEFAULT_ENV=dev; fi
ENVIRONMENT="${ENVIRONMENT:-$DEFAULT_ENV}"
UNSIGNED_BUILD="${MULBERRY_MAC_UNSIGNED_BUILD:-NO}"
if [ "$UNSIGNED_BUILD" = YES ] && [ "$ENVIRONMENT" != dev ]; then
  echo 'Unsigned packaging is available only for development builds.' >&2
  exit 1
fi
case "$ENVIRONMENT:$CONFIGURATION" in
  dev:debug) XCODE_CONFIGURATION=Debug ;;
  dev:release) XCODE_CONFIGURATION=DevRelease ;;
  prod:debug) XCODE_CONFIGURATION=ProdDebug ;;
  prod:release) XCODE_CONFIGURATION=Release ;;
  *) echo 'Use ENVIRONMENT=dev|prod and CONFIGURATION=debug|release' >&2; exit 1 ;;
esac
if [ "$ENVIRONMENT" = prod ]; then DEFAULT_TEAM=HMJX9736KN; else DEFAULT_TEAM=YGJRT89MH9; fi
python3 Scripts/generate-project.py
args=(-project MulberryMac.xcodeproj -scheme 'Mulberry Production' -configuration "$XCODE_CONFIGURATION"
  -destination "platform=macOS,arch=$HOST_ARCH" -derivedDataPath .build/xcode
  ONLY_ACTIVE_ARCH=YES "ARCHS=$HOST_ARCH"
  -clonedSourcePackagesDirPath ../ios/.build/device/SourcePackages
  MULBERRY_APNS_ENVIRONMENT=development)
if [ "$UNSIGNED_BUILD" = YES ]; then
  args+=(CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO DEVELOPMENT_TEAM=
    MULBERRY_MAC_UNSIGNED_BUILD=YES)
else
  args+=(-allowProvisioningUpdates -allowProvisioningDeviceRegistration
    CODE_SIGN_IDENTITY='Apple Development' "DEVELOPMENT_TEAM=${MULBERRY_MAC_DEVELOPMENT_TEAM:-$DEFAULT_TEAM}")
fi
if [ -n "${MULBERRY_ALLOW_LOCAL_SERVER:-}" ]; then args+=("MULBERRY_ALLOW_LOCAL_SERVER=$MULBERRY_ALLOW_LOCAL_SERVER"); fi
if [ -n "${MULBERRY_MAC_API_BASE_URL:-}" ]; then args+=("MULBERRY_MAC_API_BASE_URL=$MULBERRY_MAC_API_BASE_URL"); fi
xcodebuild "${args[@]}" build
# Separate from the old .build/app bundle, which may still be running.
echo "Built $(pwd)/.build/xcode/Build/Products/$XCODE_CONFIGURATION/Mulberry.app"
