#!/bin/bash
set -euo pipefail
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
IOS_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHECK_DIR="$(mktemp -d /tmp/mulberry-streak-checks.XXXXXX)"
trap 'rm -rf "$CHECK_DIR"' EXIT
xcrun swiftc "$IOS_ROOT"/../../packages/apple/Sources/ProductCore/*.swift \
  "$IOS_ROOT/Sources/App/StreakModel.swift" "$IOS_ROOT/Tests/Streak/StreakModelChecks.swift" \
  -o "$CHECK_DIR/check-streak"
"$CHECK_DIR/check-streak"
