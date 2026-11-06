import type { Database } from "../../infra/db/database.js"
import type { ShareFrameLayout } from "./layout.js"

/** A `share_frames` row, with its dates as `YYYY-MM-DD` strings (see `normalizeShareFrameRow`). */
export interface ShareFrameRow {
  frame_key: string
  frame_version: number
  title: string
  sort_order: number
  available_from: string | null
  available_until: string | null
  original_path: string
  full_path: string
  thumbnail_path: string
  width: number
  height: number
  layout: ShareFrameLayout
  published_at: string | null
  content_locked: boolean
  created_at: string
  updated_at: string
}

type RawShareFrameRow = Omit<ShareFrameRow, "available_from" | "available_until"> & {
  available_from: string | Date | null
  available_until: string | Date | null
}

/**
 * node-postgres reads a DATE as local midnight and pg-mem as UTC midnight. Either way the
 * calendar date is what was stored, so history snapshots and API responses carry plain strings.
 */
export function calendarDate(value: string | Date | null): string | null {
  if (value === null) return null
  if (typeof value === "string") return value.slice(0, 10)
  const utcMidnight = value.getUTCHours() === 0 && value.getUTCMinutes() === 0 && value.getUTCSeconds() === 0
  const [year, month, day] = utcMidnight
    ? [value.getUTCFullYear(), value.getUTCMonth() + 1, value.getUTCDate()]
    : [value.getFullYear(), value.getMonth() + 1, value.getDate()]
  return `${String(year).padStart(4, "0")}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`
}

export function normalizeShareFrameRow(row: RawShareFrameRow): ShareFrameRow {
  return {
    ...row,
    frame_version: Number(row.frame_version),
    sort_order: Number(row.sort_order),
    width: Number(row.width),
    height: Number(row.height),
    content_locked: Boolean(row.content_locked),
    available_from: calendarDate(row.available_from),
    available_until: calendarDate(row.available_until),
  }
}

/** Reads one version for a history snapshot, locking its row when it exists. */
export async function shareFrameSnapshot(
  db: Pick<Database, "query">,
  key: string,
  version: number,
  forUpdate = false,
): Promise<ShareFrameRow | null> {
  const rows = await db.query<RawShareFrameRow>(
    `SELECT * FROM share_frames WHERE frame_key = $1 AND frame_version = $2${forUpdate ? " FOR UPDATE" : ""}`,
    [key, version],
  )
  const row = rows.rows[0]
  return row ? normalizeShareFrameRow(row) : null
}
