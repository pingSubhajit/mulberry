#!/bin/sh
set -eu

if [ "$CONFIGURATION" = Release ] && [ "${MULBERRY_ALLOW_LOCAL_SERVER:-NO}" != YES ] && [ "${MULBERRY_LOCAL_DEVICE_BUILD:-NO}" != YES ]; then
  packages="${MULBERRY_SOURCE_PACKAGES_DIR:-${BUILD_DIR%/Build/*}/SourcePackages}"
  "$packages/checkouts/firebase-ios-sdk/Crashlytics/run"
fi
