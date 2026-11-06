#!/bin/bash
# Builds, checks and uploads Mulberry's Android app.
#
#   release.sh build  <release-dir>   signed production bundle, plus what verify reads
#   release.sh verify <release-dir>   checks the bundle before it goes anywhere
#   release.sh upload <release-dir> [--notes-file FILE] [--validate-only]
#                                     uploads to Play's open testing track and rolls it out to everyone
#
# Signing comes from the ignored secrets/android/signing.env; Firebase's client config from the
# ignored apps/mobile/app/src/prod/google-services.json.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../../../.." && pwd)"
SCRIPTS="$ROOT/.agents/skills/mulberry-skills/android-release/scripts"
MOBILE="$ROOT/apps/mobile"
SIGNING="$ROOT/secrets/android/signing.env"
PROD_API_BASE_URL="https://api.mulberry.my/"

command="${1:-}"; release="${2:-}"
[ -n "$command" ] && [ -n "$release" ] || { sed -n '2,9p' "$0"; exit 2; }
mkdir -p "$release"; release="$(cd "$release" && pwd)"

case "$command" in
  build)
    [ -f "$SIGNING" ] || { echo "Missing $SIGNING (the upload keystore's signing values)." >&2; exit 1; }
    [ -f "$MOBILE/app/src/prod/google-services.json" ] || { echo "Missing apps/mobile/app/src/prod/google-services.json." >&2; exit 1; }
    # Read literally: a password may hold characters the shell would expand.
    while IFS='=' read -r key value; do
      case "$key" in RELEASE_*) export "$key=$value" ;; esac
    done < "$SIGNING"
    export PROD_API_BASE_URL
    export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
    (cd "$MOBILE" && ./gradlew --console=plain :app:clean :app:bundleProdRelease) > "$release/android-build.log" 2>&1 \
      || { tail -30 "$release/android-build.log" >&2; echo "android build failed; see $release/android-build.log" >&2; exit 1; }
    cp "$MOBILE/app/build/outputs/bundle/prodRelease/app-prod-release.aab" "$release/Mulberry.aab"
    manifest="$(find "$MOBILE/app/build/intermediates" -path '*prodRelease*' -name AndroidManifest.xml -path '*merged_manifest*' | head -1)"
    [ -n "$manifest" ] || manifest="$(find "$MOBILE/app/build/intermediates" -path '*prodRelease*' -name AndroidManifest.xml | head -1)"
    cp "$manifest" "$release/AndroidManifest.xml"
    cp "$(find "$MOBILE/app/build/generated/source/buildConfig/prod/release" -name BuildConfig.java | head -1)" "$release/BuildConfig.java"
    echo "android build ok"
    ;;
  verify)
    python3 -I "$SCRIPTS/verify.py" "$release"
    ;;
  upload)
    shift 2
    (cd "$SCRIPTS" && uv run -q --no-project --with google-auth --with requests \
      python -I play.py upload "$release" --track beta "$@")
    ;;
  *)
    sed -n '2,9p' "$0"; exit 2
    ;;
esac
