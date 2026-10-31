#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

./Scripts/build-app.sh
open .build/xcode/Build/Products/Debug/Mulberry.app
