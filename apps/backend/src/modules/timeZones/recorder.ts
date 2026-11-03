import { randomUUID } from "node:crypto"
import type { FastifyInstance, FastifyRequest } from "fastify"
import type { Database } from "../../infra/db/database.js"

/** The apps name the device's IANA time zone, as "Asia/Kolkata", in this header on every request. */
export const TIME_ZONE_HEADER = "x-time-zone"

/** How many sessions' last recorded zone the server remembers, so most requests skip the database. */
const REMEMBERED_SESSIONS_LIMIT = 10_000

/**
 * Keeps each account's time zone current from the header the apps send. A change updates
 * `users.time_zone` and adds a row to `user_time_zone_changes`, so a past moment can be read in
 * the local time the person was in then. Requests without a session or a valid zone pass
 * untouched, and a failure to record never fails the request.
 */
export function registerTimeZoneRecorder(app: FastifyInstance, db: Database): void {
  const recordedBySession = new Map<string, string>()

  app.addHook("onRequest", async (request) => {
    const accessToken = bearerToken(request)
    const timeZone = canonicalTimeZone(header(request, TIME_ZONE_HEADER))
    if (!accessToken || !timeZone || recordedBySession.get(accessToken) === timeZone) return

    try {
      if (await recordTimeZone(db, accessToken, timeZone)) {
        recordedBySession.delete(accessToken)
        recordedBySession.set(accessToken, timeZone)
        if (recordedBySession.size > REMEMBERED_SESSIONS_LIMIT) {
          const oldest = recordedBySession.keys().next().value
          if (oldest !== undefined) recordedBySession.delete(oldest)
        }
      }
    } catch (error) {
      request.log.warn({ err: error }, "Couldn't record the time zone")
    }
  })
}

/** Records `timeZone` for the session's account. False when the session isn't valid. */
async function recordTimeZone(db: Database, accessToken: string, timeZone: string): Promise<boolean> {
  return db.transaction(async (tx) => {
    const sessions = await tx.query<{ user_id: string }>(
      `SELECT user_id FROM sessions WHERE access_token = $1 AND revoked_at IS NULL`,
      [accessToken],
    )
    const userId = sessions.rows[0]?.user_id
    if (!userId) return false

    const changed = await tx.query<{ id: string }>(
      `
      UPDATE users
      SET time_zone = $2
      WHERE id = $1 AND (time_zone IS NULL OR time_zone <> $2)
      RETURNING id
      `,
      [userId, timeZone],
    )
    if (changed.rows.length > 0) {
      await tx.query(
        `INSERT INTO user_time_zone_changes (id, user_id, time_zone) VALUES ($1, $2, $3)`,
        [randomUUID(), userId, timeZone],
      )
    }
    return true
  })
}

/**
 * The zone's canonical IANA name, so aliases from different devices ("Asia/Kolkata" and
 * "Asia/Calcutta") record as one zone. Null for anything that isn't a time zone.
 */
export function canonicalTimeZone(raw: string | undefined): string | null {
  const value = raw?.trim()
  if (!value || value.length > 64) return null
  try {
    return new Intl.DateTimeFormat("en-US", { timeZone: value }).resolvedOptions().timeZone
  } catch {
    return null
  }
}

function bearerToken(request: FastifyRequest): string | null {
  const authorization = request.headers.authorization
  return authorization?.startsWith("Bearer ") ? authorization.slice("Bearer ".length) || null : null
}

function header(request: FastifyRequest, name: string): string | undefined {
  const value = request.headers[name]
  return Array.isArray(value) ? value[0] : value
}
