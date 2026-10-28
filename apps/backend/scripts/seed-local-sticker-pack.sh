#!/bin/bash
# Seeds a sticker pack into the LOCAL backend through its admin API, so assets
# are stored wherever that backend keeps them. Use with STICKER_LOCAL_STORAGE_DIR
# to keep development assets out of the shared Supabase bucket.
#
#   apps/backend/scripts/seed-local-sticker-pack.sh <folder-of-pngs> <pack-key> "<Title>" [version]
#
# Sticker IDs come from the number in each file name ("kitty (12).png" -> "12"),
# falling back to the file name. The first sticker is also the pack cover.
set -euo pipefail
DIR="${1:?folder of PNG stickers}"; PACK_KEY="${2:?pack key}"; TITLE="${3:?title}"; VERSION="${4:-1}"
API_URL="${API_URL:-http://localhost:8080}"
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
PASSWORD="${STICKER_ADMIN_PASSWORD:-$(grep -E '^(STICKER|WALLPAPER)_ADMIN_PASSWORD=' "$ROOT/.env" | head -1 | cut -d= -f2- | tr -d "'\"")}"
[ -n "$PASSWORD" ] || { echo "Set STICKER_ADMIN_PASSWORD" >&2; exit 1; }

FILES=()
while IFS= read -r line; do FILES+=("${line#*$'\t'}"); done < <(
  for file in "$DIR"/*.png; do
    name="$(basename "$file" .png)"; number="$(echo "$name" | grep -oE '[0-9]+' | tail -1 || true)"
    printf '%s\t%s\n' "${number:-0}" "$file"
  done | sort -n -k1,1)
[ "${#FILES[@]}" -gt 0 ] || { echo "No PNG files in $DIR" >&2; exit 1; }

curl -sf -X POST "$API_URL/admin/stickers/packs" -H "x-sticker-admin-password: $PASSWORD" \
  -F packKey="$PACK_KEY" -F packVersion="$VERSION" -F title="$TITLE" \
  -F published=true -F featured=true -F sortOrder=0 \
  -F "cover=@${FILES[0]};type=image/png" > /dev/null
echo "Created $PACK_KEY v$VERSION"

for index in "${!FILES[@]}"; do
  file="${FILES[$index]}"; name="$(basename "$file" .png)"
  id="$(echo "$name" | grep -oE '[0-9]+' | tail -1 || true)"; id="${id:-$name}"
  curl -sf -X POST "$API_URL/admin/stickers/packs/$PACK_KEY/$VERSION/stickers" -H "x-sticker-admin-password: $PASSWORD" \
    -F stickerId="$id" -F sortOrder="$index" -F "image=@$file;type=image/png" > /dev/null
  echo "Uploaded sticker $id"
done
