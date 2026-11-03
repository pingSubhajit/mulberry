# Server-driven content

Mulberry's backend owns the sticker and wallpaper catalogs. Apps fetch the catalog and image URLs; adding content does not require an app release. New content kinds still need client rendering and backend support before content can be added.

This guide is the reference for agents and for adding future content kinds. The agent workflow lives in [the catalog skill](../.agents/skills/mulberry-skills/catalog/SKILL.md). Build gates remain separate in [app-versions](../.agents/skills/mulberry-skills/app-versions/SKILL.md): they use Firebase Remote Config, while catalogs use Postgres and object storage.

## Ownership and environments

Real catalog management uses `https://api.mulberry.my` and `x-admin-password`. Credentials remain server-side or in the agent's secret input; never put a Supabase service key in an app. The web admin and agents call the same APIs. Agents must not edit production tables or storage objects directly.

A local backend uses its configured `DATABASE_URL` and storage buckets. A local database does not imply local storage. In development, `STICKER_LOCAL_STORAGE_DIR` and `WALLPAPER_LOCAL_STORAGE_DIR` keep catalog assets on the backend's disk. The backend serves them under `/dev/sticker-assets/` (signed) and `/dev/wallpaper-assets/` (public, like the bucket). Production refuses both. Profile photos always use the Supabase bucket, because their URLs leave in bootstrap and push payloads. The import scripts are development tools, not the agent path for production.

Admin authorization runs before multipart processing. Service methods also check authorization. Admin responses keep their existing top-level content fields and add `message`; errors include a stable `code`. Public routes keep their existing contracts.

## Identity, visibility and caching

A sticker is identified by `(packKey, packVersion, stickerId)`. Apps cache art under that identity, and saved canvases keep those references. Once a version has been published, its sticker art, cover, membership and order cannot change. `content_locked` records that fact after unpublishing. Title, description, pack sort order, featured state and visibility can still change.

To change sticker content, copy to a new version, edit the draft, then publish it. Copy preserves sticker IDs, order and dimensions and shares the immutable asset paths. It does not duplicate files. Neither replacing a draft asset nor deleting a version removes those shared objects. Used version identities stay reserved through history; restoring a deletion is the supported way to recover one.

The migration locks all pre-existing pack versions. Older hidden versions have no reliable publication history, so old drafts are copied before art changes too. New versions start unlocked. Publishing locks them. Restoring an earlier state never removes that lock.

Wallpapers retain their ID when their art is replaced. All variants use a new UUID path. Metadata and visibility stay intact. Old URLs remain available for saved references and recovery. Already-downloaded wallpapers do not change on devices at once; clients must fetch or select them again. New paths also avoid stale CDN content, as described in [Supabase's upload guide](https://supabase.com/docs/guides/storage/uploads/standard-uploads#overwriting-files).

Unpublishing hides content from discovery without removing files. Deleting removes catalog rows and may break saved canvas references or links. Both pack and wallpaper deletion require a literal JSON `confirm:true`. The first call without confirmation returns 409 with the impact and the safer hide operation. The web admin asks before sending confirmation; agents need explicit approval for that exact effect.

## API reference

Every route below requires `x-admin-password`. JSON updates accept only the named fields and correct types. Numeric fields are integers. Multipart booleans are `true` or `false`; JSON booleans are actual booleans. `sortOrder` sorts low to high.

### Sticker packs

| Method and route | Input | Result |
| --- | --- | --- |
| `GET /admin/stickers/packs` | None | `items`: all versions, including drafts, with counts, visibility and `contentLocked` |
| `GET /admin/stickers/packs/:key/:version` | Positive integer version | Pack detail with sticker IDs, dimensions, order and signed URLs |
| `POST /admin/stickers/packs` | Multipart: `packKey`, `packVersion` (default 1), `title`, optional `description`, `sortOrder`, `featured`, `published`; one cover file | New version; defaults to draft. Duplicate/used versions return 409 |
| `PATCH /admin/stickers/packs/:key/:version` | JSON: any of `title`, `description`, `sortOrder`, `featured`, `published` | Updated metadata; publishing locks content |
| `PUT /admin/stickers/packs/:key/:version/cover` | Multipart `image` | New cover paths; draft only |
| `POST /admin/stickers/packs/:key/:version/stickers` | Multipart: `stickerId`, optional `sortOrder`, `image` | Adds/replaces one sticker in a draft |
| `PATCH /admin/stickers/packs/:key/:version/stickers/:id` | JSON `sortOrder` | Reorders a draft sticker without uploading art |
| `DELETE /admin/stickers/packs/:key/:version/stickers/:id` | None | Removes one draft sticker; retains assets |
| `POST /admin/stickers/packs/:key/:version/copy` | JSON `newVersion`, greater than source and never used | Full copy as an unpublished, unlocked draft |
| `DELETE /admin/stickers/packs/:key/:version` | JSON `confirm:true` after approval | Removes pack/sticker rows; retains assets and history |

Pack keys are normalized to lowercase hyphenated keys; use the key returned by the API. Choose a new version above every known version of that key. Sticker images are decoded and converted to PNG with alpha, plus 256×256 transparent thumbnails. Each file is limited to 20 MB. Use PNG for transparent source art.

Published discovery is `GET /stickers/packs`; detail is `GET /stickers/packs/:key?version=N`. Both require a bearer token. `GET /stickers/assets/url?packKey=KEY&version=N&stickerId=ID&variant=full` resolves saved references even when a pack is hidden; it also requires a bearer token. Signed URLs last ten minutes and must not be saved as durable asset identity.

### Wallpapers

| Method and route | Input | Result |
| --- | --- | --- |
| `GET /admin/wallpapers` | None | `items`: all wallpapers, including drafts, with metadata and visibility |
| `POST /admin/wallpapers` | Multipart: `title`, optional `description`, `sortOrder`, `published`, one image file | New wallpaper; default draft |
| `PATCH /admin/wallpapers/:id` | JSON: any of `title`, `description`, `sortOrder`, `published` | Updated metadata |
| `PUT /admin/wallpapers/:id/image` | Multipart `image` | Replaces original/thumbnail/preview/full at new paths, keeps metadata |
| `DELETE /admin/wallpapers/:id` | JSON `confirm:true` after approval | Removes row; retains assets and history |

Images must be valid JPEG, PNG or WebP, at least 800px on both sides and at most 20 MB. Processing stores the original and generates WebP variants: a 720×560 thumbnail, a 900×1650 preview, and a full image fitting within 2160×3840. Processing avoids enlargement. The API reports source dimensions and a dominant color.

Public discovery is `GET /wallpapers?limit=24&cursor=...`, with a maximum page size of 50. It returns published items and `nextCursor`. File URLs are public, so hiding or deleting a row is not access revocation for anyone who already has a URL.

### History and restore

| Method and route | Input | Result |
| --- | --- | --- |
| `GET /admin/stickers/history` | Optional `limit` (1–100, default 50) | Recent sticker changes |
| `GET /admin/wallpapers/history` | Same | Recent wallpaper changes |
| `POST /admin/stickers/history/:changeId/restore` | JSON `confirm:true` after approval | Restores the previous state and writes a new history entry |
| `POST /admin/wallpapers/history/:changeId/restore` | Same | Same for wallpapers |

`catalog_admin.admin_catalog_changes` is in a private schema, outside the usual public Data API schema. It stores `id`, `content_type`, `content_key`, `action`, `actor`, `previous_json`, `current_json`, and `created_at`. The shared password cannot identify a person; `actor` is honestly recorded as `shared-admin`. Never log the password, service keys, request headers or signed URLs.

Each mutation takes a persistent identity lock in `catalog_admin.catalog_item_locks` (including while the catalog row is absent), locks its existing parent row, captures prior state, performs the write, and saves the resulting snapshot in one database transaction. Publishing, uploads, copying, deletion and restore use this path. Duplicate creation is also guarded by the database primary key. History failure rolls back the catalog write. Failed requests are not recorded as successful changes.

Restore first compares live state with that entry's `current` snapshot under the parent lock. A mismatch returns `CATALOG_STATE_CHANGED`; later edits are preserved. Undo newer changes first. Restoring a create deletes that created item and therefore also needs explicit confirmation. Restoring a delete reconstructs the rows and their asset paths. Restore does not re-upload missing objects, force devices to discard caches, or unlock published sticker identities.

Old objects are retained indefinitely for history and version copies. There is no automatic garbage collection in this change. Database and object storage do not share a transaction: a failed upload, database commit or history insert can leave an unreferenced UUID object. It cannot replace the old referenced art. Any future cleanup job must account for all live rows, copies and history snapshots and use a retention window. Hard storage purge must be a distinct operation with its own impact check.

### Errors

| Status/code | Meaning and response |
| --- | --- |
| 400 `INVALID_CATALOG_INPUT` | Fix the field, type, version, file or dimensions named in `message` |
| 401 `ADMIN_UNAUTHORIZED` | Missing/wrong admin password; stop |
| 404 `CATALOG_NOT_FOUND`, `PACK_NOT_FOUND`, `STICKER_NOT_FOUND`, `CATALOG_CHANGE_NOT_FOUND` | Re-read exact identifiers |
| 409 `PACK_VERSION_EXISTS` | Existing or previously used version; use metadata PATCH, restore deletion, or a new version |
| 409 `PACK_VERSION_IMMUTABLE` | Copy published/locked content into a new draft |
| 409 `CONFIRMATION_REQUIRED` | Show the impact; confirm only after explicit approval |
| 409 `CATALOG_STATE_CHANGED` | Live state differs from history; read and resolve newer edits |
| 503 `CATALOG_UNAVAILABLE` | Storage/admin configuration unavailable |
| 5xx `CATALOG_FAILURE` | Storage or server failed; inspect current state before retrying |

## Agent examples

Load `ADMIN_PASSWORD` as shown in the [catalog skill](../.agents/skills/mulberry-skills/catalog/SKILL.md), without printing it. All examples use production; test assets belong in isolated development storage.

Create and populate a draft:

```sh
curl --fail-with-body -sS "$API/admin/stickers/packs" \
  -H "x-admin-password: $ADMIN_PASSWORD" \
  -F packKey=cats -F packVersion=3 -F title=Cats -F published=false \
  -F 'cover=@/absolute/path/cover.png;type=image/png'
curl --fail-with-body -sS "$API/admin/stickers/packs/cats/3/stickers" \
  -H "x-admin-password: $ADMIN_PASSWORD" \
  -F stickerId=cat -F sortOrder=0 -F 'image=@/absolute/path/cat.png;type=image/png'
curl --fail-with-body -sS "$API/admin/stickers/packs/cats/3" \
  -H "x-admin-password: $ADMIN_PASSWORD"
```

After checking the draft and its art, publish:

```sh
curl --fail-with-body -sS -X PATCH "$API/admin/stickers/packs/cats/3" \
  -H "x-admin-password: $ADMIN_PASSWORD" -H 'content-type: application/json' \
  -d '{"published":true}'
```

Copy a published pack, reorder one sticker, then remove another from the draft:

```sh
curl --fail-with-body -sS "$API/admin/stickers/packs/cats/3/copy" \
  -H "x-admin-password: $ADMIN_PASSWORD" -H 'content-type: application/json' \
  -d '{"newVersion":4}'
curl --fail-with-body -sS -X PATCH "$API/admin/stickers/packs/cats/4/stickers/cat" \
  -H "x-admin-password: $ADMIN_PASSWORD" -H 'content-type: application/json' \
  -d '{"sortOrder":5}'
curl --fail-with-body -sS -X DELETE "$API/admin/stickers/packs/cats/4/stickers/other" \
  -H "x-admin-password: $ADMIN_PASSWORD"
```

Create a wallpaper, or replace its art using the returned ID:

```sh
curl --fail-with-body -sS "$API/admin/wallpapers" \
  -H "x-admin-password: $ADMIN_PASSWORD" -F title=Forest -F published=false \
  -F 'image=@/absolute/path/forest.jpg;type=image/jpeg'
curl --fail-with-body -sS -X PUT "$API/admin/wallpapers/WALLPAPER_ID/image" \
  -H "x-admin-password: $ADMIN_PASSWORD" \
  -F 'image=@/absolute/path/new-forest.jpg;type=image/jpeg'
```

Hide content, then inspect history:

```sh
curl --fail-with-body -sS -X PATCH "$API/admin/wallpapers/WALLPAPER_ID" \
  -H "x-admin-password: $ADMIN_PASSWORD" -H 'content-type: application/json' \
  -d '{"published":false}'
curl --fail-with-body -sS "$API/admin/wallpapers/history?limit=50" \
  -H "x-admin-password: $ADMIN_PASSWORD"
```

Read back the target and history after each write. On a timeout, read first: a response failure does not prove the transaction failed. Do not automatically retry create, copy, delete or restore.

## Adding a future content type

Start by naming its stable identity and where apps will retain it. Define what changes can happen under the same identity, how clients cache it, and which old references must keep working. A new version or URL is required whenever cached bytes change.

Add public discovery/detail contracts and a renderer on every supported client. Define pagination, ordering, fallback behavior and what old app builds do with an unknown type. A backend catalog cannot teach an old client a new renderer. Use app-version policy only when a compatible release is fully live and an update gate is truly required.

Add one admin API used by the web UI and agents. Authenticate before processing files; validate unknown fields, types, size, dimensions and decoded format. Return stable error codes and useful messages. Create drafts, preview them, then publish. Separate hide from delete and storage purge. Require explicit confirmation for destructive effects.

Use immutable object paths and keep row changes plus history in one transaction. Lock a stable identity for all concurrent mutations of one item, including creates/restores of absent rows, and lock its parent row. Decide how to reserve deleted identities, handle missing rows and retry a request whose outcome is unknown. Object storage needs compensation or a safe orphan cleanup policy because it does not join the database transaction.

Extend the history snapshot and restore code explicitly; do not accept client-supplied table names or column names. Keep the log private. If shared admin auth is replaced later, record the authenticated actor. Define retention before garbage collection, including historic snapshots and copied versions.

Add API tests for auth, draft creation, invalid input, publication, cache identity, copies, hiding, confirmed deletion, history rollback, stale restore and missing assets. Use a real Postgres test for lock/race behavior when changing concurrency rules; pg-mem does not prove those rules. Verify existing web/admin/script callers still follow draft-first publication. Add the content workflow to the skill index and link it here.

## Release and validation

Schema changes run through `runMigrations` when the backend starts, following the repository's existing migration model. Deploy the backend before agents use the new operations. Coordinate the web admin release because delete now needs a JSON confirmation body and returns 200 with `message` rather than 204. The bundled sticker importers now publish only after all uploads complete.

The catalog API tests use in-memory Postgres emulation, real image decoding and recording storage. They verify API behavior and rollback with snapshots; they do not prove live Supabase delivery, real Postgres row-lock scheduling, or physical device refresh behavior. No production catalog writes are needed to validate this implementation.
