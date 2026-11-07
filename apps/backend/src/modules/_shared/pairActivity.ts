import type { Database } from "../../infra/db/database.js"
import type { PairSessionRecord } from "../../contracts/dbRecords.js"
import type { PairActivitySource } from "../../infra/analytics/events.js"
import { stableUuid } from "../../infra/analytics/outbox.js"
import type { Analytics } from "../../infra/analytics/recorder.js"
import { dateOnly } from "./dates.js"

/**
 * Marks the pair active for the UTC day and ISO week of `occurredAt`, sending
 * `mulberry_pair_active_day` and `_week` the first time each is marked. Their IDs are fixed per
 * pair and day or week, so two requests racing to be first still record one event.
 */
export async function recordPairActivity(
  db: Pick<Database, "query">,
  analytics: Analytics,
  input: { pairSession: PairSessionRecord; actorUserId: string; occurredAt: Date; activitySource: PairActivitySource },
): Promise<void> {
  if (!analytics.enabled) return
  const pairSessionId = input.pairSession.id
  const activityDay = dateOnly(input.occurredAt)
  const weekStartDay = weekStartFromDay(activityDay)
  const mode = input.pairSession.canvas_stroke_render_mode

  if (await markActive(db, "pair_product_activity_days", "activity_day", pairSessionId, activityDay)) {
    await analytics.track(db, {
      event: "mulberry_pair_active_day",
      id: stableUuid(`active_day:${pairSessionId}:${activityDay}`),
      distinctId: input.actorUserId,
      pairId: pairSessionId,
      occurredAt: input.occurredAt,
      properties: { activity_source: input.activitySource, activity_day: activityDay, canvas_stroke_render_mode: mode },
    })
  }
  if (await markActive(db, "pair_product_activity_weeks", "week_start_day", pairSessionId, weekStartDay)) {
    await analytics.track(db, {
      event: "mulberry_pair_active_week",
      id: stableUuid(`active_week:${pairSessionId}:${weekStartDay}`),
      distinctId: input.actorUserId,
      pairId: pairSessionId,
      occurredAt: input.occurredAt,
      properties: { activity_source: input.activitySource, week_start_day: weekStartDay, canvas_stroke_render_mode: mode },
    })
  }
}

/** True when this call marked the day or week for the first time. */
async function markActive(
  db: Pick<Database, "query">,
  table: "pair_product_activity_days" | "pair_product_activity_weeks",
  column: "activity_day" | "week_start_day",
  pairSessionId: string,
  day: string,
): Promise<boolean> {
  // Already marked is the usual case, so it takes one round trip.
  const touched = await db.query(
    `UPDATE ${table} SET last_seen_at = NOW() WHERE pair_session_id = $1 AND ${column} = $2::date RETURNING pair_session_id`,
    [pairSessionId, day],
  )
  if (touched.rows.length > 0) {
    return false
  }
  await db.query(
    `
    INSERT INTO ${table} (pair_session_id, ${column}, last_seen_at)
    VALUES ($1, $2::date, NOW())
    ON CONFLICT (pair_session_id, ${column}) DO UPDATE SET last_seen_at = NOW()
    `,
    [pairSessionId, day],
  )
  return true
}

/** The Monday starting the ISO week that holds `day`. */
export function weekStartFromDay(day: string): string {
  const date = new Date(`${day}T00:00:00.000Z`)
  const delta = (date.getUTCDay() + 6) % 7
  date.setUTCDate(date.getUTCDate() - delta)
  return dateOnly(date)
}
