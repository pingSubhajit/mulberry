import type { Database } from "../../infra/db/database.js"
import { HttpError } from "../../infra/http/HttpError.js"
import type { StreakResponse } from "../../contracts/streak.js"
import { requireSessionContext } from "../_shared/session.js"
import { getPairSession } from "../_shared/pairs.js"
import { addDays, dateOnly, isLocalDateString, normalizeDateString } from "../_shared/dates.js"

export class StreakService {
  constructor(private readonly db: Database) {}

  async getStreak(accessToken: string, todayInput: string): Promise<StreakResponse> {
    const context = await requireSessionContext(this.db, accessToken)
    const today = todayInput.trim()
    if (!isLocalDateString(today)) {
      throw new HttpError(400, "today is required")
    }

    const pairSession = await getPairSession(this.db, context.user.id)
    if (!pairSession) {
      return {
        today,
        currentStreakDays: 0,
        previousStreakDays: 0,
        hasActivityToday: false,
        lastActivityDay: null,
        lostStreakSeen: false,
        week: this.weekDaysFor(today).map((day) => ({ day, hasActivity: false })),
      }
    }

    const activityDays = await this.listPairActivityDays(pairSession.id)
    const days = new Set(activityDays)
    const lastActivityDay = activityDays[0] ?? null
    const currentStreakDays = this.computeCurrentStreakDays(days, today)
    const previousStreakDays = lastActivityDay ? this.computeStreakLengthEndingAt(days, lastActivityDay) : 0
    const week = this.weekDaysFor(today).map((day) => ({ day, hasActivity: days.has(day) }))
    const seenDay = lastActivityDay ? await this.streakLossSeenDay(context.user.id) : null

    return {
      today,
      currentStreakDays,
      previousStreakDays,
      hasActivityToday: days.has(today),
      lastActivityDay,
      lostStreakSeen: lastActivityDay !== null && seenDay === lastActivityDay,
      week,
    }
  }

  /** Records that the account watched the bolt break for the streak that ended on `day`. */
  async markLostStreakSeen(accessToken: string, dayInput: string): Promise<{ ok: true }> {
    const context = await requireSessionContext(this.db, accessToken)
    const day = dayInput.trim()
    if (!isLocalDateString(day)) {
      throw new HttpError(400, "day is required")
    }
    await this.db.query(`UPDATE users SET streak_loss_seen_day = $2::date WHERE id = $1`, [context.user.id, day])
    return { ok: true }
  }

  async resetLostStreakSeen(accessToken: string): Promise<{ ok: true }> {
    const context = await requireSessionContext(this.db, accessToken)
    await this.db.query(`UPDATE users SET streak_loss_seen_day = NULL WHERE id = $1`, [context.user.id])
    return { ok: true }
  }

  private async streakLossSeenDay(userId: string): Promise<string | null> {
    const rows = await this.db.query<{ streak_loss_seen_day: string | Date | null }>(
      `SELECT streak_loss_seen_day FROM users WHERE id = $1`,
      [userId],
    )
    const day = rows.rows[0]?.streak_loss_seen_day
    return day ? normalizeDateString(day) : null
  }

  async clearActivityDay(accessToken: string, dayInput: string): Promise<{ ok: true }> {
    const context = await requireSessionContext(this.db, accessToken)
    const day = dayInput.trim()
    if (!isLocalDateString(day)) {
      throw new HttpError(400, "day is required")
    }

    const pairSession = await getPairSession(this.db, context.user.id)
    if (!pairSession) {
      throw new HttpError(400, "User is not paired", { code: "NOT_PAIRED" })
    }

    await this.db.query(
      `
      DELETE FROM pair_activity_days
      WHERE pair_session_id = $1
        AND activity_day = $2::date
      `,
      [pairSession.id, day],
    )

    return { ok: true }
  }

  async currentStreakDays(pairSessionId: string): Promise<number> {
    return (await this.currentStreak(pairSessionId)).days
  }

  /** The pair's running streak and the day it began; `startDay` is null with no streak. */
  async currentStreak(pairSessionId: string): Promise<{ days: number; startDay: string | null }> {
    const rows = await this.db.query<{ activity_day: string | Date }>(
      `
      SELECT activity_day
      FROM pair_activity_days
      WHERE pair_session_id = $1
      ORDER BY activity_day DESC
      `,
      [pairSessionId],
    )
    if (rows.rows.length === 0) return { days: 0, startDay: null }

    const days = new Set(rows.rows.map((row) => normalizeDateString(row.activity_day)).filter(Boolean))
    const today = dateOnly(new Date())
    const yesterday = addDays(today, -1)
    let cursor = days.has(today) ? today : days.has(yesterday) ? yesterday : null
    if (!cursor) return { days: 0, startDay: null }

    let streak = 0
    let startDay = cursor
    while (cursor && days.has(cursor)) {
      streak += 1
      startDay = cursor
      cursor = addDays(cursor, -1)
    }
    return { days: streak, startDay }
  }

  async listPairActivityDays(pairSessionId: string): Promise<string[]> {
    const rows = await this.db.query<{ activity_day: string | Date }>(
      `
      SELECT activity_day
      FROM pair_activity_days
      WHERE pair_session_id = $1
      ORDER BY activity_day DESC
      `,
      [pairSessionId],
    )
    return rows.rows.map((row) => normalizeDateString(row.activity_day)).filter(Boolean) as string[]
  }

  async recordPairActivityDay(
    tx: Pick<Database, "query">,
    pairSessionId: string,
    clientLocalDate: string | null | undefined,
  ): Promise<void> {
    const activityDay = isLocalDateString(clientLocalDate)
      ? clientLocalDate
      : new Date().toISOString().slice(0, 10)
    await tx.query(
      `
      INSERT INTO pair_activity_days (pair_session_id, activity_day)
      VALUES ($1, $2::date)
      ON CONFLICT (pair_session_id, activity_day) DO NOTHING
      `,
      [pairSessionId, activityDay],
    )
  }

  private computeCurrentStreakDays(days: Set<string>, today: string): number {
    const yesterday = addDays(today, -1)
    let cursor = days.has(today) ? today : days.has(yesterday) ? yesterday : null
    if (!cursor) return 0

    let streak = 0
    while (cursor && days.has(cursor)) {
      streak += 1
      cursor = addDays(cursor, -1)
    }
    return streak
  }

  private computeStreakLengthEndingAt(days: Set<string>, endDay: string): number {
    let cursor: string | null = endDay
    let streak = 0
    while (cursor && days.has(cursor)) {
      streak += 1
      cursor = addDays(cursor, -1)
    }
    return streak
  }

  private weekDaysFor(today: string): string[] {
    const weekStart = addDays(today, -this.dayOfWeek(today))
    return Array.from({ length: 7 }, (_, index) => addDays(weekStart, index))
  }

  private dayOfWeek(day: string): number {
    return new Date(`${day}T00:00:00.000Z`).getUTCDay()
  }
}
