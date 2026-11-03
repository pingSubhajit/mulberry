#!/bin/zsh
# Mulberry TestFlight release steps.
#
#   release.sh archive <release-dir> <ios|macos>...   archive + local export (no upload)
#   release.sh verify  <release-dir> <ios|macos>...   check the exported .ipa/.pkg; exits 1 on any failure
#   release.sh upload  <release-dir> <ios|macos>...   upload the archives to App Store Connect
#
# <release-dir> is temp/apple-release/<YYYY-MM-DD>-build-<N> (created if missing).
# Logs go to <release-dir>/<platform>-<step>.log.
set -euo pipefail

SKILL_DIR="${0:A:h:h}"
ROOT="${SKILL_DIR:h:h:h:h}"
cd "$ROOT"
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer

TEAM=HMJX9736KN
BUNDLE_ID=com.subhajit.mulberry.beta
API=https://api.mulberry.my/
SP="$ROOT/apps/ios/.build/device/SourcePackages"

(( $# >= 3 )) || { print -u2 "usage: release.sh archive|verify|upload <release-dir> <ios|macos>..."; exit 2; }
STEP=$1; R=${2:A}; shift 2
PLATFORMS=("$@")
for p in $PLATFORMS; do [[ $p == ios || $p == macos ]] || { print -u2 "unknown platform: $p"; exit 2; }; done
mkdir -p "$R"

archive_ios() {
  xcodebuild archive -project apps/ios/Mulberry.xcodeproj -scheme 'Mulberry Production' -configuration Release \
    -destination 'generic/platform=iOS' -archivePath "$R/Mulberry-iOS.xcarchive" \
    -derivedDataPath "$ROOT/apps/ios/.build/distribution" -clonedSourcePackagesDirPath "$SP" \
    -disableAutomaticPackageResolution -allowProvisioningUpdates \
    DEVELOPMENT_TEAM=$TEAM CODE_SIGN_STYLE=Automatic MULBERRY_API_BASE_URL=$API \
    MULBERRY_ALLOW_LOCAL_SERVER=NO MULBERRY_LOCAL_DEVICE_BUILD=NO MULBERRY_SOURCE_PACKAGES_DIR="$SP" \
    APS_ENVIRONMENT=production ONLY_ACTIVE_ARCH=NO > "$R/ios-archive.log" 2>&1
  print "ios archive ok"
  xcodebuild -exportArchive -archivePath "$R/Mulberry-iOS.xcarchive" -exportPath "$R/iOS" \
    -exportOptionsPlist scripts/apple/ExportOptions.plist -allowProvisioningUpdates > "$R/ios-export.log" 2>&1
  print "ios export ok"
}

archive_macos() {
  xcodebuild archive -project apps/macos/MulberryMac.xcodeproj -scheme 'Mulberry Production' -configuration Release \
    -destination 'generic/platform=macOS' -archivePath "$R/Mulberry-macOS.xcarchive" \
    -derivedDataPath "$ROOT/apps/macos/.build/distribution" -clonedSourcePackagesDirPath "$SP" \
    -disableAutomaticPackageResolution -allowProvisioningUpdates \
    DEVELOPMENT_TEAM=$TEAM CODE_SIGN_STYLE=Automatic MULBERRY_MAC_API_BASE_URL=$API \
    MULBERRY_ALLOW_LOCAL_SERVER=NO MULBERRY_MAC_UNSIGNED_BUILD=NO MULBERRY_SOURCE_PACKAGES_DIR="$SP" \
    MULBERRY_APNS_ENVIRONMENT=production ONLY_ACTIVE_ARCH=NO > "$R/macos-archive.log" 2>&1
  print "macos archive ok"
  xcodebuild -exportArchive -archivePath "$R/Mulberry-macOS.xcarchive" -exportPath "$R/macOS" \
    -exportOptionsPlist scripts/apple/ExportOptions.plist -allowProvisioningUpdates > "$R/macos-export.log" 2>&1
  print "macos export ok"
}

# --- verify helpers ---------------------------------------------------------
FAILS=0
check() { # check <label> <actual> <expected>
  if [[ "$2" == "$3" ]]; then print "  ok    $1 = $2"; else print "  FAIL  $1 = '$2' (expected '$3')"; FAILS=$((FAILS + 1)); fi
}
has() { # has <label> <haystack> <needle>
  if [[ "$2" == *"$3"* ]]; then print "  ok    $1"; else print "  FAIL  $1 (missing '$3')"; FAILS=$((FAILS + 1)); fi
}
plist() { /usr/libexec/PlistBuddy -c "Print $2" "$1" 2>/dev/null || print "<missing>"; }
src_build() { plist "$ROOT/$1" CFBundleVersion; }
ents() { codesign -d --entitlements - --xml "$1" 2>/dev/null | plutil -p - 2>/dev/null || true; }
authority() { codesign -dvv "$1" 2>&1 | grep -m1 '^Authority=' | sed 's/^Authority=//' || true; }
POLICY="$ROOT/apps/mobile/app/src/main/res/raw/privacy_policy.txt"
TERMS="$ROOT/apps/mobile/app/src/main/res/raw/terms_of_use.txt"
same_file() { if cmp -s "$2" "$3"; then print "  ok    $1 matches source"; else print "  FAIL  $1 differs from $3"; FAILS=$((FAILS + 1)); fi; }

verify_ios() {
  print "== iOS"
  local X="$R/iOS-expanded"
  rm -rf "$X"; mkdir -p "$X"; (cd "$X" && unzip -q "$R/iOS/Mulberry.ipa")
  local A="$X/Payload/Mulberry.app" W
  W=$(print -l "$A"/PlugIns/*.appex | head -1)
  local want; want=$(src_build apps/ios/Configuration/Info.plist)
  check "bundle id" "$(plist "$A/Info.plist" CFBundleIdentifier)" $BUNDLE_ID
  check "version" "$(plist "$A/Info.plist" CFBundleShortVersionString)" "$(plist "$ROOT/apps/ios/Configuration/Info.plist" CFBundleShortVersionString)"
  check "build" "$(plist "$A/Info.plist" CFBundleVersion)" "$want"
  check "widget build" "$(plist "$W/Info.plist" CFBundleVersion)" "$want"
  check "api" "$(plist "$A/Info.plist" MulberryAPIBaseURL)" $API
  check "environment" "$(plist "$A/Info.plist" MulberryAppEnvironment)" prod
  local e; e=$(ents "$A")
  has "aps-environment production" "$e" '"aps-environment" => "production"'
  has "Sign in with Apple" "$e" 'com.apple.developer.applesignin'
  has "associated domains applinks:mulberry.my" "$e" 'applinks:mulberry.my'
  has "get-task-allow false" "$e" '"get-task-allow" => false'
  has "TestFlight beta-reports-active" "$e" '"beta-reports-active" => true'
  has "profile grants associated domains" "$(security cms -D -i "$A/embedded.mobileprovision" 2>/dev/null)" 'com.apple.developer.associated-domains'
  check "app signer" "$(authority "$A")" "Apple Distribution: TECHABOARD DIGI SOLUTIONS PRIVATE LIMITED ($TEAM)"
  check "widget signer" "$(authority "$W")" "Apple Distribution: TECHABOARD DIGI SOLUTIONS PRIVATE LIMITED ($TEAM)"
  has "app group" "$e" "group.$BUNDLE_ID"
  has "widget app group" "$(ents "$W")" "group.$BUNDLE_ID"
  has "widget aps-environment production" "$(ents "$W")" '"aps-environment" => "production"'
  same_file "privacy policy" "$A/Legal/privacy_policy.txt" "$POLICY"
  same_file "terms of use" "$A/Legal/terms_of_use.txt" "$TERMS"
}

verify_macos() {
  print "== macOS"
  local X="$R/macOS-expanded"
  rm -rf "$X"; mkdir -p "$X"; pkgutil --expand-full "$R/macOS/Mulberry.pkg" "$X/pkg" >/dev/null
  local M; M=$(find "$X/pkg" -maxdepth 3 -name Mulberry.app -type d | head -1)
  [[ -n "$M" ]] || { print "  FAIL  Mulberry.app not found in pkg"; FAILS=$((FAILS + 1)); return; }
  local I="$M/Contents/Info.plist"
  check "bundle id" "$(plist "$I" CFBundleIdentifier)" $BUNDLE_ID
  check "version" "$(plist "$I" CFBundleShortVersionString)" "$(plist "$ROOT/apps/macos/Configuration/Info.plist" CFBundleShortVersionString)"
  check "build" "$(plist "$I" CFBundleVersion)" "$(src_build apps/macos/Configuration/Info.plist)"
  check "environment" "$(plist "$I" MulberryAppEnvironment)" prod
  check "remote push" "$(plist "$I" MulberryRemotePushEnabled)" YES
  check "apns environment" "$(plist "$I" MulberryAPNSEnvironment)" production
  check "api" "$(grep -m1 '^MULBERRY_MAC_API_BASE_URL=' "$M/Contents/Resources/.env" | cut -d= -f2-)" $API
  local e; e=$(ents "$M")
  has "aps-environment production" "$e" '"com.apple.developer.aps-environment" => "production"'
  has "app sandbox" "$e" '"com.apple.security.app-sandbox" => true'
  has "Sign in with Apple" "$e" 'com.apple.developer.applesignin'
  check "app signer" "$(authority "$M")" "Apple Distribution: TECHABOARD DIGI SOLUTIONS PRIVATE LIMITED ($TEAM)"
  local archs; archs=$(lipo -archs "$M/Contents/MacOS/Mulberry")
  has "universal arm64" "$archs" arm64
  has "universal x86_64" "$archs" x86_64
  has "app group" "$e" "$TEAM.group.$BUNDLE_ID"
  local W="$M/Contents/PlugIns/MulberryWidgets.appex"
  check "widget build" "$(plist "$W/Contents/Info.plist" CFBundleVersion)" "$(src_build apps/macos/Configuration/Info.plist)"
  check "widget signer" "$(authority "$W")" "Apple Distribution: TECHABOARD DIGI SOLUTIONS PRIVATE LIMITED ($TEAM)"
  local we; we=$(ents "$W")
  has "widget sandbox" "$we" '"com.apple.security.app-sandbox" => true'
  has "widget app group" "$we" "$TEAM.group.$BUNDLE_ID"
  has "widget aps-environment production" "$we" '"com.apple.developer.aps-environment" => "production"'
  local warchs; warchs=$(lipo -archs "$W/Contents/MacOS/MulberryWidgets" 2>/dev/null)
  has "widget universal arm64" "$warchs" arm64
  has "widget universal x86_64" "$warchs" x86_64
  has "installer signature" "$(pkgutil --check-signature "$R/macOS/Mulberry.pkg")" "3rd Party Mac Developer Installer: TECHABOARD DIGI SOLUTIONS PRIVATE LIMITED ($TEAM)"
  same_file "privacy policy" "$M/Contents/Resources/Legal/privacy_policy.txt" "$POLICY"
  same_file "terms of use" "$M/Contents/Resources/Legal/terms_of_use.txt" "$TERMS"
  print "  info  sandbox entitlements: $(print -r -- "$e" | grep -oE 'com\.apple\.security\.[a-z.-]+' | sed 's/com.apple.security.//' | tr '\n' ' ')"
}

upload() { # upload <ios|macos>
  local name=${1/ios/iOS}; name=${name/macos/macOS}
  local log="$R/$1-upload.log"
  xcodebuild -exportArchive -archivePath "$R/Mulberry-$name.xcarchive" -exportPath "$R/$name-upload" \
    -exportOptionsPlist "$SKILL_DIR/assets/UploadOptions.plist" -allowProvisioningUpdates > "$log" 2>&1 || true
  if grep -q "Upload succeeded" "$log"; then
    print "$1 upload succeeded"
  else
    print -u2 "$1 upload FAILED — see $log"; grep -iE "error" "$log" | grep -viE "IDEDistributionLogging" | tail -5 >&2; return 1
  fi
}

case $STEP in
  archive)
    [[ -d "$SP" ]] || { print -u2 "missing package checkout $SP (resolve packages first, see SKILL.md)"; exit 1; }
    for p in $PLATFORMS; do archive_$p; done ;;
  verify)
    for p in $PLATFORMS; do verify_$p; done
    (( FAILS == 0 )) && print "ALL CHECKS PASSED" || { print "$FAILS CHECK(S) FAILED"; exit 1; } ;;
  upload)
    for p in $PLATFORMS; do upload $p; done ;;
  *) print -u2 "unknown step: $STEP"; exit 2 ;;
esac
