import type { Database } from "../../infra/db/database.js"
import type { CanvasPhotoStorage } from "./photos.js"

/** Photos younger than this stay, placed or not, so undo and redo never meet a missing image. */
export const CANVAS_PHOTO_ORPHAN_MIN_AGE_MS = 14 * 24 * 60 * 60 * 1000

const DEFAULT_SWEEP_BATCH_SIZE = 200
const DEFAULT_SWEEP_INTERVAL_MS = 15 * 60 * 1000

/** Where the last sweep stopped, in `(created_at, id)` order. */
export interface CanvasPhotoSweepCursor {
  createdAt: Date
  id: string
}

export interface CanvasPhotoSweepOptions {
  now?: Date
  minAgeMs?: number
  /** The most photos one sweep looks at. */
  batchSize?: number
  /** Carries on after this photo; from the oldest when left out. */
  after?: CanvasPhotoSweepCursor | null
}

export interface CanvasPhotoSweepResult {
  scanned: number
  /** The ids of the photos removed, file and row. */
  removed: string[]
  /** Where the next sweep carries on; null once every old photo has been looked at. */
  next: CanvasPhotoSweepCursor | null
}

interface SweepCandidateRow {
  id: string
  pair_session_id: string
  storage_key: string | null
  created_at: Date | string
}

/**
 * Removes photos no element of their pair's current canvas uses, as `photoId` or `cutoutPhotoId`,
 * once they are older than 14 days. Looks at one batch, oldest first, and returns where to carry
 * on. Files go first: a file that won't delete keeps its row, and the next pass tries again.
 */
export async function sweepOrphanedCanvasPhotos(
  db: Pick<Database, "query">,
  storage: CanvasPhotoStorage | null,
  options: CanvasPhotoSweepOptions = {},
): Promise<CanvasPhotoSweepResult> {
  const now = options.now ?? new Date()
  const cutoff = new Date(now.getTime() - (options.minAgeMs ?? CANVAS_PHOTO_ORPHAN_MIN_AGE_MS))
  const batchSize = Math.max(1, Math.floor(options.batchSize ?? DEFAULT_SWEEP_BATCH_SIZE))
  const after = options.after ?? null

  const candidates = after
    ? await db.query<SweepCandidateRow>(
      `
      SELECT id, pair_session_id, storage_key, created_at
      FROM canvas_photos
      WHERE created_at < $1 AND (created_at > $2 OR (created_at = $2 AND id > $3))
      ORDER BY created_at ASC, id ASC
      LIMIT $4
      `,
      [cutoff, after.createdAt, after.id, batchSize],
    )
    : await db.query<SweepCandidateRow>(
      `
      SELECT id, pair_session_id, storage_key, created_at
      FROM canvas_photos
      WHERE created_at < $1
      ORDER BY created_at ASC, id ASC
      LIMIT $2
      `,
      [cutoff, batchSize],
    )
  const rows = candidates.rows
  const last = rows.at(-1)
  const next = rows.length === batchSize && last ? { createdAt: new Date(last.created_at), id: last.id } : null

  const referencedByPair = new Map<string, Set<string>>()
  for (const pairSessionId of new Set(rows.map((row) => row.pair_session_id))) {
    referencedByPair.set(pairSessionId, await referencedPhotoIds(db, pairSessionId))
  }

  // Without storage, files can't be removed, so only rows that never had one can go.
  const orphans = rows.filter((row) =>
    !referencedByPair.get(row.pair_session_id)?.has(row.id) && (storage !== null || row.storage_key === null),
  )
  if (orphans.length === 0) return { scanned: rows.length, removed: [], next }

  const keys = orphans.map((row) => row.storage_key).filter((key): key is string => key !== null)
  if (storage && keys.length > 0) {
    try {
      await storage.remove(keys)
    } catch (error) {
      console.error("[canvas-photos] sweep could not remove photo files", {
        count: keys.length,
        error: error instanceof Error ? error.message : String(error),
      })
      return { scanned: rows.length, removed: [], next }
    }
  }

  const ids = orphans.map((row) => row.id)
  await db.query(
    `DELETE FROM canvas_photos WHERE id IN (${ids.map((_, index) => `$${index + 1}`).join(", ")})`,
    ids,
  )
  return { scanned: rows.length, removed: ids, next }
}

/** Every photo id the pair's materialised canvas uses, as a photo or as a sticker cut-out. */
async function referencedPhotoIds(db: Pick<Database, "query">, pairSessionId: string): Promise<Set<string>> {
  const rows = await db.query<{ snapshot_json: unknown }>(
    `SELECT snapshot_json FROM canvas_snapshots WHERE pair_session_id = $1`,
    [pairSessionId],
  )
  const ids = new Set<string>()
  const snapshot = rows.rows[0]?.snapshot_json
  const elements = isRecord(snapshot) && Array.isArray(snapshot.elements) ? snapshot.elements : []
  for (const element of elements) {
    if (!isRecord(element)) continue
    for (const field of ["photoId", "cutoutPhotoId"] as const) {
      const value = element[field]
      if (typeof value === "string" && value.trim()) ids.add(value.trim())
    }
  }
  return ids
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

export interface CanvasPhotoSweeperOptions {
  intervalMs?: number
  batchSize?: number
}

/** Runs the orphan sweep on a timer, one batch a tick, picking up where the last tick stopped. */
export class CanvasPhotoSweeper {
  private timer: ReturnType<typeof setInterval> | null = null
  private cursor: CanvasPhotoSweepCursor | null = null
  private inFlight: Promise<void> | null = null

  constructor(
    private readonly db: Pick<Database, "query">,
    private readonly storage: CanvasPhotoStorage | null,
    private readonly options: CanvasPhotoSweeperOptions = {},
  ) {}

  start(): void {
    if (this.timer) return
    this.timer = setInterval(() => void this.runOnce(), this.options.intervalMs ?? DEFAULT_SWEEP_INTERVAL_MS)
    this.timer.unref()
  }

  /** Stops the timer and waits for a sweep under way, so it doesn't outlive the database. */
  async stop(): Promise<void> {
    if (this.timer) clearInterval(this.timer)
    this.timer = null
    await this.inFlight
  }

  runOnce(): Promise<void> {
    this.inFlight ??= this.sweep().finally(() => {
      this.inFlight = null
    })
    return this.inFlight
  }

  private async sweep(): Promise<void> {
    try {
      const result = await sweepOrphanedCanvasPhotos(this.db, this.storage, {
        after: this.cursor,
        batchSize: this.options.batchSize,
      })
      this.cursor = result.next
      if (result.removed.length > 0) {
        console.info("[canvas-photos] sweep removed unused photos", { count: result.removed.length })
      }
    } catch (error) {
      console.error("[canvas-photos] sweep failed", { error: error instanceof Error ? error.message : String(error) })
    }
  }
}
