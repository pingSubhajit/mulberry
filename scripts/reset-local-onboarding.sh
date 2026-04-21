#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGES=(
  "com.subhajit.mulberry.dev"
  "com.subhajit.mulberry"
  "com.subhajit.elaris.dev"
  "com.subhajit.elaris"
)

echo "Resetting Mulberry local backend state..."
docker compose -f "$ROOT_DIR/docker-compose.yml" down -v --remove-orphans
docker compose -f "$ROOT_DIR/docker-compose.yml" up -d --build

if command -v adb >/dev/null 2>&1; then
  mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')

  if [[ ${#DEVICES[@]} -eq 0 ]]; then
    echo "No adb devices detected. Docker state was reset, but no app data was cleared."
    exit 0
  fi

  for DEVICE in "${DEVICES[@]}"; do
    echo "Clearing app data on device: $DEVICE"
    for PACKAGE in "${PACKAGES[@]}"; do
      adb -s "$DEVICE" shell pm clear "$PACKAGE" >/dev/null 2>&1 || true
    done
  done

  echo "Mulberry app data cleared on ${#DEVICES[@]} connected device(s)."
else
  echo "adb not found. Docker state was reset, but app data was not cleared."
fi

echo "Local onboarding flow is reset. Reopen the app to start from scratch."
