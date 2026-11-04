import type { Database } from "../db/database.js"
import type { PairSessionRecord } from "../../contracts/dbRecords.js"
import { dateOnly, normalizeDateString } from "../../modules/_shared/dates.js"
import type { ReactionType } from "../../contracts/reactions.js"
import type { ClientContext } from "./events.js"
import { stableUuid } from "./outbox.js"
import { signInMethodOf } from "./profiles.js"
import type { Analytics } from "./recorder.js"

export const ANALYTICS_BACKFILL_NAME = "analytics.posthog.v2"

const NO_CLIENT: ClientContext = { platform: null, deviceFamily: null, appBuild: null, anonymousId: null }

/**
 * Gives PostHog the history recorded before analytics was keyed to people: every account's
 * sign-up and onboarding, every current pair, their active days and weeks, and their reactions.
 * Pairs that already ended can't be recovered, since unpairing deletes them. Runs once; event IDs
 * are fixed, so an interrupted run that starts again records nothing twice. Everything at or after
 * `cutoff` is left to live recording.
 */
export async function runAnalyticsBackfill(db: Database, analytics: Analytics, cutoff: Date): Promise<void> {
  if (!analytics.enabled) return
  const done = await db.query(`SELECT name FROM schema_backfills WHERE name = $1`, [ANALYTICS_BACKFILL_NAME])
  if (done.rows.length > 0) return

  const users = await db.query<{
    id: string
    created_at: string | Date
    google_subject: string | null
    apple_subject: string | null
    onboarding_completed_at: string | Date | null
  }>(
    `
    SELECT u.id, u.created_at, u.google_subject, u.apple_subject, p.onboarding_completed_at
    FROM users u LEFT JOIN user_profiles p ON p.user_id = u.id
    WHERE u.created_at < $1
    `,
    [cutoff.toISOString()],
  )
  for (const user of users.rows) {
    await db.transaction(async (tx) => {
      await analytics.syncPerson(tx, user.id)
      await analytics.track(tx, {
        event: "mulberry_user_signed_up",
        id: stableUuid(`signed_up:${user.id}`),
        distinctId: user.id,
        occurredAt: new Date(user.created_at),
        properties: { sign_in_method: signInMethodOf(user) },
        source: "backfill",
        client: NO_CLIENT,
      })
      if (user.onboarding_completed_at && new Date(user.onboarding_completed_at) < cutoff) {
        await analytics.track(tx, {
          event: "mulberry_onboarding_completed",
          id: stableUuid(`onboarding_completed:${user.id}`),
          distinctId: user.id,
          occurredAt: new Date(user.onboarding_completed_at),
          properties: {},
          source: "backfill",
          client: NO_CLIENT,
        })
      }
    })
  }

  const pairs = await db.query<PairSessionRecord>(
    `SELECT id, user_one_id, user_two_id, canvas_stroke_render_mode, created_at FROM pair_sessions WHERE created_at < $1`,
    [cutoff.toISOString()],
  )
  for (const pair of pairs.rows) {
    await db.transaction(async (tx) => {
      await analytics.syncPair(tx, pair)
      await analytics.track(tx, {
        event: "mulberry_pair_created",
        id: stableUuid(`pair_created:${pair.id}`),
        distinctId: pair.user_two_id,
        pairId: pair.id,
        occurredAt: new Date(pair.created_at),
        properties: { invite_id: null, inviter_user_id: pair.user_one_id },
        source: "backfill",
        client: NO_CLIENT,
      })
      await backfillPairActivity(tx, analytics, pair, cutoff)
    })
  }

  await db.query(`INSERT INTO schema_backfills (name) VALUES ($1) ON CONFLICT (name) DO NOTHING`, [ANALYTICS_BACKFILL_NAME])
}

async function backfillPairActivity(
  tx: Pick<Database, "query">,
  analytics: Analytics,
  pair: PairSessionRecord,
  cutoff: Date,
): Promise<void> {
  const mode = pair.canvas_stroke_render_mode
  const days = await tx.query<{ activity_day: string | Date; created_at: string | Date }>(
    `SELECT activity_day, created_at FROM pair_product_activity_days WHERE pair_session_id = $1 AND created_at < $2`,
    [pair.id, cutoff.toISOString()],
  )
  for (const day of days.rows) {
    const activityDay = normalizeDateString(day.activity_day) ?? dateOnly(new Date(day.created_at))
    await analytics.track(tx, {
      event: "mulberry_pair_active_day",
      id: stableUuid(`active_day:${pair.id}:${activityDay}`),
      distinctId: pair.user_one_id,
      pairId: pair.id,
      occurredAt: new Date(day.created_at),
      properties: { activity_source: "unknown", activity_day: activityDay, canvas_stroke_render_mode: mode },
      source: "backfill",
      client: NO_CLIENT,
    })
  }

  const weeks = await tx.query<{ week_start_day: string | Date; created_at: string | Date }>(
    `SELECT week_start_day, created_at FROM pair_product_activity_weeks WHERE pair_session_id = $1 AND created_at < $2`,
    [pair.id, cutoff.toISOString()],
  )
  for (const week of weeks.rows) {
    const weekStartDay = normalizeDateString(week.week_start_day) ?? dateOnly(new Date(week.created_at))
    await analytics.track(tx, {
      event: "mulberry_pair_active_week",
      id: stableUuid(`active_week:${pair.id}:${weekStartDay}`),
      distinctId: pair.user_one_id,
      pairId: pair.id,
      occurredAt: new Date(week.created_at),
      properties: { activity_source: "unknown", week_start_day: weekStartDay, canvas_stroke_render_mode: mode },
      source: "backfill",
      client: NO_CLIENT,
    })
  }

  const reactions = await tx.query<{ id: string; sender_user_id: string; reaction_type: ReactionType; sent_at: string | Date }>(
    `SELECT id, sender_user_id, reaction_type, sent_at FROM reaction_events WHERE pair_session_id = $1 AND sent_at < $2`,
    [pair.id, cutoff.toISOString()],
  )
  for (const reaction of reactions.rows) {
    await analytics.track(tx, {
      event: "mulberry_reaction_sent",
      id: stableUuid(`reaction:${reaction.id}`),
      distinctId: reaction.sender_user_id,
      pairId: pair.id,
      occurredAt: new Date(reaction.sent_at),
      properties: { reaction_type: reaction.reaction_type, canvas_stroke_render_mode: mode },
      source: "backfill",
      client: NO_CLIENT,
    })
  }
}
