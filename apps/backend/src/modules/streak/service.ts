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
        week: this.weekDaysFor(today).map((day) => ({ day, hasActivity: false })),
      }
    }

    const activityDays = await this.listPairActivityDays(pairSession.id)
    const days = new Set(activityDays)
    const lastActivityDay = activityDays[0] ?? null
    const currentStreakDays = this.computeCurrentStreakDays(days, today)
    const previousStreakDays = lastActivityDay ? this.computeStreakLengthEndingAt(days, lastActivityDay) : 0
    const week = this.weekDaysFor(today).map((day) => ({ day, hasActivity: days.has(day) }))

    return {
      today,
      currentStreakDays,
      previousStreakDays,
      hasActivityToday: days.has(today),
      lastActivityDay,
      week,
    }
  }

  async currentStreakDays(pairSessionId: string): Promise<number> {
    const rows = await this.db.query<{ activity_day: string | Date }>(
      `
      SELECT activity_day
      FROM pair_activity_days
      WHERE pair_session_id = $1
      ORDER BY activity_day DESC
      `,
      [pairSessionId],
    )
    if (rows.rows.length === 0) return 0

    const days = new Set(rows.rows.map((row) => normalizeDateString(row.activity_day)).filter(Boolean))
    const today = dateOnly(new Date())
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

