import type { Database } from "../../infra/db/database.js"
import type { PairSessionRecord } from "../../contracts/dbRecords.js"

export async function getPairSession(
  db: Pick<Database, "query">,
  userId: string,
): Promise<PairSessionRecord | null> {
  const rows = await db.query<PairSessionRecord>(
    `
      SELECT id, user_one_id, user_two_id, canvas_stroke_render_mode, created_at
      FROM pair_sessions
      WHERE user_one_id = $1 OR user_two_id = $1
      LIMIT 1
      `,
    [userId],
  )
  return rows.rows[0] ?? null
}

/** The pair's active days, counted before the pair is deleted and its days with it. */
export async function pairActiveDays(db: Pick<Database, "query">, pairSessionId: string): Promise<number> {
  const rows = await db.query<{ days: string | number }>(
    `SELECT COUNT(*) AS days FROM pair_product_activity_days WHERE pair_session_id = $1`,
    [pairSessionId],
  )
  return Number(rows.rows[0]?.days ?? 0)
}
