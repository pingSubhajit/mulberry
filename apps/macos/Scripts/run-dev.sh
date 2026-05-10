#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

./Scripts/build-app.sh
open .build/app/Mulberry.app
