---
name: catalog
description: Read and manage Mulberry's server-driven sticker packs and wallpapers through the admin API, including draft uploads, version copies, publishing, ordering, history and recovery. Use when the user asks to add, change, hide, remove, reorder or restore stickers or wallpapers, or manage server-driven content.
---

# Catalog

Read [the server-driven content guide](../../../../docs/server-driven-content.md) for exact routes, fields, examples and future content design rules. The backend must include these routes before using this skill; a 404 is not permission to bypass the API.

## Connection

Use `https://api.mulberry.my` for real catalog work. Read `ADMIN_PASSWORD` from the repo-root `.env` without printing it. Do not enable shell tracing or put credentials in files, messages or history.

```sh
API=https://api.mulberry.my
ADMIN_PASSWORD="$(node --input-type=module -e 'process.loadEnvFile(".env"); process.stdout.write(process.env.ADMIN_PASSWORD ?? "")')"
curl --fail-with-body -sS "$API/admin/stickers/packs" -H "x-admin-password: $ADMIN_PASSWORD"
curl --fail-with-body -sS "$API/admin/wallpapers" -H "x-admin-password: $ADMIN_PASSWORD"
```

An empty password or 401 means stop. Use the runtime's secret input tool if available. Never guess credentials.

Never use local services, direct SQL, seed scripts or storage consoles for real catalog management.

## Local catalog

Use the local backend only when the user asks for the local database. Local work needs a local database and local storage. The local `.env` points at the production Supabase project and bucket, so do not use Supabase from a local backend.

1. Check that the repo-root `.env` sets `STICKER_LOCAL_STORAGE_DIR=/data/sticker-assets` and `WALLPAPER_LOCAL_STORAGE_DIR=/data/wallpaper-assets`. docker-compose mounts a volume at each path. If either is missing, add it. The backend refuses both in production.
2. Start or restart the backend with `hun` (`hun status`, then `hun restart mulberry:backend`; this rebuilds the image). Wait until `docker inspect -f '{{.State.Health.Status}}' mulberry-backend-1` says `healthy`.
3. Use `API=http://localhost:8080` with the same password, routes and rules as production. Run loops under `bash`, not zsh: zsh arrays start at 1.
4. Check that every returned URL starts with `http://localhost:8080/dev/sticker-assets/` or `http://localhost:8080/dev/wallpaper-assets/`, and that each URL loads. A Supabase URL means local storage is off: stop and fix it before uploading anything else.

Local stickers and wallpapers can be published without asking, since nothing outside this machine sees them. Clients get local URLs on whichever host they called, such as a LAN IP for a device. Profile photos still use the Supabase bucket.

## Working rules

Read the current catalog and exact pack version before each change. State the target and the change briefly. Keep the user's requested scope.

Create sticker packs as drafts (`published=false`), upload all stickers, check detail and image URLs, then PATCH `published:true`. A published version's art, cover, membership and order are fixed forever, even when hidden. Metadata, pack order, featured status and visibility remain editable.

To edit published stickers, POST `/admin/stickers/packs/:key/:version/copy` with `{"newVersion":N}`. Pick a new integer above the source and every known version of this key. The copy contains the same art and starts as a draft. Edit it, read it back, then publish. Hide the old version only if requested; its saved canvas art must stay resolvable. Used version identities cannot be reused after deletion.

To hide content, PATCH `{"published":false}`. This keeps its files and references. Delete only when the user explicitly requests deletion and accepts the saved-link or saved-canvas impact. A 409 `CONFIRMATION_REQUIRED` means show its message and wait for explicit approval before resending `{"confirm":true}`. Never add confirmation automatically. Prior approval counts only if it covers this exact content and effect.

Stickers use `image` multipart uploads; pack creation uses a `cover` file (the existing route accepts one file field). PNG is preferred for transparent art. Files must be at most 20 MB. The backend makes full PNGs and 256px thumbnails while preserving alpha. Wallpapers require valid JPEG, PNG or WebP, at least 800px on each side and at most 20 MB. Upload wallpaper replacements to `PUT /admin/wallpapers/:id/image` using the `image` field. Existing devices may keep a downloaded wallpaper until they select or fetch it again.

## Wallpaper titles and descriptions

Look at each image before naming it; file names such as Pexels IDs are not titles. Send `title` and `description` in the same create request as the image. If the user supplies text, use theirs.

- **Title:** two or three words in title case. Name the mood or the subject, not the file.
- **Description:** one or two sentences, in the present tense, describing only what is in the image. Lead with the subject, then light, color and texture, and end on the feeling the scene leaves. The tone is calm and sensory. Don't sell it: no "stunning" or "cute", no resolution or "wallpaper", no emoji, no exclamation marks. Don't name places or brands you can't confirm from the image.

Production examples to match:

> **Gilded Alley:** Warm lantern light spills across a narrow cobblestone path, clinging to ivy and brick as darkness recedes into silence. The scene holds a quiet intimacy, where glow and shadow meet in a fragile balance.

> **Ember Geometry:** Parallel bands of orange light curve and repeat into darkness, forming a precise, almost mechanical rhythm of heat and shadow.

> **Polar Veil:** A soft surge of green aurora stretches across a star-filled sky, diffusing like light through water and fading into the cold expanse of space.

Numeric fields must be integers. Booleans are JSON booleans, or the strings `true` and `false` in multipart forms. Unknown fields fail; do not rely on silent defaults for invalid values.

## History and recovery

GET `/admin/stickers/history` or `/admin/wallpapers/history` (`?limit=50`, max 100) before recovery. Each entry records the shared admin actor, action, exact key, time and full previous/current database snapshots. Paths are durable; signed image URLs are not history.

POST `/admin/:type/history/:changeId/restore` to inspect the confirmation response. Restore needs explicit approval and `{"confirm":true}`. It writes a new history entry and refuses to erase a later edit with `CATALOG_STATE_CHANGED`. Undo newer changes first. Undoing publication never unlocks a sticker version. Assets are retained, including after deletion, so copies and history remain usable. A missing storage object still needs separate recovery; do not claim history alone repairs it.

After every write, read the exact target and history. For a new pack, verify sticker IDs/count, draft/published state and URLs. For deletion, verify the row is absent and history has its prior state. Relay the response's `message` and report only what was checked. Stop on storage failures; inspect current state before any retry. A timeout does not prove the write failed.

## Error handling

`PACK_VERSION_EXISTS`: read the version, then PATCH metadata or choose a never-used version. `PACK_VERSION_IMMUTABLE`: copy to a draft. `CONFIRMATION_REQUIRED`: obtain explicit approval for the stated effect. `CATALOG_STATE_CHANGED`: read current state and history; never force it. `INVALID_CATALOG_INPUT`: fix the named field or image. `CATALOG_NOT_FOUND`, `PACK_NOT_FOUND`, `STICKER_NOT_FOUND` or `CATALOG_CHANGE_NOT_FOUND`: re-read identifiers. `ADMIN_UNAUTHORIZED`: stop. `CATALOG_UNAVAILABLE` or `CATALOG_FAILURE`: report the failure, inspect state and avoid retry loops.
