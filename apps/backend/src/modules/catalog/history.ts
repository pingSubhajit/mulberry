import { randomUUID } from "node:crypto"
import type { Database } from "../../infra/db/database.js"
import { HttpError } from "../../infra/http/HttpError.js"

type CatalogType = "stickers" | "wallpapers"

// Identity locks also cover creates and restores while the catalog row is absent.
// Snapshots and the write commit together; credentials and signed URLs never enter the log.
export async function catalogMutation<T>(
  db: Database, type: CatalogType, key: string, version: number | undefined,
  action: string, mutate: (db: Database) => Promise<T>,
): Promise<T> {
  if (!key || (type === "stickers" && (!Number.isInteger(version) || Number(version) < 1 || Number(version) > 2147483647))) {
    throw new HttpError(400, "Supply a content key and a positive 32-bit integer pack version", { code: "INVALID_CATALOG_INPUT" })
  }
  try {
    return await db.transaction(async (tx) => {
      const contentKey = type === "stickers" ? `${key}:${version}` : key
      await tx.query(`INSERT INTO catalog_admin.catalog_item_locks (content_type, content_key)
        VALUES ($1, $2) ON CONFLICT (content_type, content_key) DO NOTHING`, [type, contentKey])
      await tx.query(`SELECT content_key FROM catalog_admin.catalog_item_locks
        WHERE content_type = $1 AND content_key = $2 FOR UPDATE`, [type, contentKey])
      const scoped = { ...db, query: tx.query }
      const snapshot = async () => {
        if (type === "wallpapers") {
          return (await tx.query("SELECT * FROM wallpapers WHERE id = $1 FOR UPDATE", [key])).rows[0] ?? null
        }
        const pack = (await tx.query("SELECT * FROM sticker_packs WHERE pack_key = $1 AND pack_version = $2 FOR UPDATE", [key, version])).rows[0]
        if (!pack) return null
        const stickers = (await tx.query("SELECT * FROM stickers WHERE pack_key = $1 AND pack_version = $2 ORDER BY sticker_id", [key, version])).rows
        return { pack, stickers }
      }
      const previous = await snapshot()
      const result = await mutate(scoped)
      const current = await snapshot()
      await tx.query(`INSERT INTO catalog_admin.admin_catalog_changes
        (id, content_type, content_key, action, previous_json, current_json)
        VALUES ($1, $2, $3, $4, $5::jsonb, $6::jsonb)`,
      [randomUUID(), type, contentKey, action,
        JSON.stringify(previous), JSON.stringify(current)])
      return result
    })
  } catch (error) {
    if ((error as { code?: string }).code === "23505") {
      throw new HttpError(409, "This catalog item already exists. Read it and use PATCH, or choose a new version.", { code: "PACK_VERSION_EXISTS" })
    }
    throw error
  }
}

export async function catalogHistory(db: Database, type: CatalogType, limit = 50) {
  if (!Number.isInteger(limit) || limit < 1 || limit > 100) {
    throw new HttpError(400, "limit must be an integer from 1 to 100", { code: "INVALID_CATALOG_INPUT" })
  }
  const result = await db.query(`SELECT id, content_type AS "contentType", content_key AS "contentKey",
    action, actor, previous_json AS previous, current_json AS current, created_at AS "createdAt"
    FROM catalog_admin.admin_catalog_changes WHERE content_type = $1 ORDER BY created_at DESC, id DESC LIMIT $2`, [type, limit])
  return { message: `Recent ${type} changes. The actor is a shared admin credential, not a person.`, items: result.rows }
}

/** Undo only the latest matching state. A stale restore must never erase a later edit. */
export async function restoreCatalogChange(db: Database, type: CatalogType, changeId: string, confirm?: boolean) {
  const result = await db.query<{
    content_key: string; previous_json: Record<string, unknown> | null; current_json: Record<string, unknown> | null
  }>("SELECT content_key, previous_json, current_json FROM catalog_admin.admin_catalog_changes WHERE id = $1 AND content_type = $2", [changeId, type])
  const change = result.rows[0]
  if (!change) throw new HttpError(404, "Catalog change not found", { code: "CATALOG_CHANGE_NOT_FOUND" })
  if (confirm !== true) throw new HttpError(409, "Restoring this change affects live content. Read previous and current in history, then resend with confirm:true after explicit approval.",
    { code: "CONFIRMATION_REQUIRED", current: change.current_json, proposed: change.previous_json })
  const [key, version] = type === "stickers" ? change.content_key.split(":") : [change.content_key, undefined]
  return catalogMutation(db, type, key, version === undefined ? undefined : Number(version), `restore:${changeId}`, async (tx) => {
    const current = type === "wallpapers"
      ? (await tx.query("SELECT * FROM wallpapers WHERE id = $1", [key])).rows[0] ?? null
      : await stickerSnapshot(tx, key, Number(version))
    if (stableJson(current) !== stableJson(change.current_json)) {
      throw new HttpError(409, "The item changed after this history entry. Read current content and undo newer changes first.", { code: "CATALOG_STATE_CHANGED" })
    }
    const previous = change.previous_json
    if (type === "wallpapers") {
      if (previous) await upsertSnapshot(tx, "wallpapers", ["id"], previous)
      else await tx.query("DELETE FROM wallpapers WHERE id = $1", [key])
    } else {
      const pack = previous?.pack as Record<string, unknown> | undefined
      if (pack) {
        // Undoing a publish must not make a once-published identity editable again.
        const currentPack = (current as { pack?: Record<string, unknown> } | null)?.pack
        pack.content_locked = Boolean(pack.content_locked || currentPack?.content_locked)
        await upsertSnapshot(tx, "sticker_packs", ["pack_key", "pack_version"], pack)
        await tx.query("DELETE FROM stickers WHERE pack_key = $1 AND pack_version = $2", [key, Number(version)])
        for (const sticker of previous!.stickers as Record<string, unknown>[]) {
          await upsertSnapshot(tx, "stickers", ["pack_key", "pack_version", "sticker_id"], sticker)
        }
      } else await tx.query("DELETE FROM sticker_packs WHERE pack_key = $1 AND pack_version = $2", [key, Number(version)])
    }
    return { message: "Catalog change restored. Assets and history retained." }
  })
}

async function stickerSnapshot(db: Pick<Database, "query">, key: string, version: number) {
  const pack = (await db.query("SELECT * FROM sticker_packs WHERE pack_key = $1 AND pack_version = $2", [key, version])).rows[0]
  if (!pack) return null
  const stickers = (await db.query("SELECT * FROM stickers WHERE pack_key = $1 AND pack_version = $2 ORDER BY sticker_id", [key, version])).rows
  return { pack, stickers }
}

function stableJson(value: unknown): string {
  // Normalize pg Date objects to the same strings used in JSONB snapshots first.
  const normalized = JSON.parse(JSON.stringify(value)) as unknown
  const sort = (item: unknown): unknown => Array.isArray(item) ? item.map(sort)
    : item && typeof item === "object" ? Object.fromEntries(Object.entries(item).sort(([a], [b]) => a.localeCompare(b)).map(([key, val]) => [key, sort(val)])) : item
  return JSON.stringify(sort(normalized))
}

// Table names are fixed in code; columns must also come from these fixed allowlists.
const SNAPSHOT_COLUMNS = {
  wallpapers: ["id", "title", "description", "storage_path", "thumbnail_path", "preview_path", "full_path", "width", "height", "dominant_color", "sort_order", "published_at", "created_at", "updated_at"],
  sticker_packs: ["pack_key", "pack_version", "title", "description", "cover_thumbnail_path", "cover_full_path", "sort_order", "featured", "published_at", "content_locked", "created_at", "updated_at"],
  stickers: ["pack_key", "pack_version", "sticker_id", "sort_order", "thumbnail_path", "full_path", "width", "height", "created_at", "updated_at"],
}
async function upsertSnapshot(db: Pick<Database, "query">, table: keyof typeof SNAPSHOT_COLUMNS, keys: string[], row: Record<string, unknown>) {
  const columns = SNAPSHOT_COLUMNS[table]
  await db.query(`INSERT INTO ${table} (${columns.join(", ")}) VALUES (${columns.map((_, i) => `$${i + 1}`).join(", ")})
    ON CONFLICT (${keys.join(", ")}) DO UPDATE SET ${columns.filter((key) => !keys.includes(key)).map((key) => `${key} = EXCLUDED.${key}`).join(", ")}`,
  columns.map((key) => row[key]))
}
