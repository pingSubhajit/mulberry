import type { FastifyInstance, FastifyRequest } from "fastify"
import type { Database } from "../db/database.js"
import { clientContextOf } from "./context.js"
import type { ClientContext } from "./events.js"
import type { Analytics } from "./recorder.js"

/** How many session and client combinations are remembered, so most requests skip the database. */
const REMEMBERED_LIMIT = 10_000

/**
 * Keeps `user_clients` current from the app headers, and the PostHog person's platforms, device
 * families and build with it. Only a change touches the database. A failure never fails the request.
 */
export function registerClientRecorder(app: FastifyInstance, db: Database, analytics: Analytics): void {
  if (!analytics.enabled) return
  const remembered = new Set<string>()

  app.addHook("onRequest", async (request) => {
    const accessToken = bearerToken(request)
    const client = clientContextOf(request)
    const platform = client.platform ?? platformOfFamily(client)
    if (!accessToken || !platform) return
    const deviceFamily = client.deviceFamily ?? "unknown"
    const key = `${accessToken}|${platform}|${deviceFamily}|${client.appBuild ?? ""}`
    if (remembered.has(key)) return

    try {
      await db.transaction(async (tx) => {
        const sessions = await tx.query<{ user_id: string }>(
          `SELECT user_id FROM sessions WHERE access_token = $1 AND revoked_at IS NULL`,
          [accessToken],
        )
        const userId = sessions.rows[0]?.user_id
        if (!userId) return
        const existing = await tx.query<{ app_build: number | null }>(
          `SELECT app_build FROM user_clients WHERE user_id = $1 AND platform = $2 AND device_family = $3`,
          [userId, platform, deviceFamily],
        )
        const now = new Date().toISOString()
        if (existing.rows.length === 0) {
          await tx.query(
            `
            INSERT INTO user_clients (user_id, platform, device_family, app_build, first_seen_at, last_seen_at)
            VALUES ($1, $2, $3, $4, $5, $5)
            ON CONFLICT (user_id, platform, device_family) DO UPDATE SET app_build = EXCLUDED.app_build, last_seen_at = EXCLUDED.last_seen_at
            `,
            [userId, platform, deviceFamily, client.appBuild, now],
          )
        } else {
          await tx.query(
            `UPDATE user_clients SET app_build = COALESCE($4, app_build), last_seen_at = $5 WHERE user_id = $1 AND platform = $2 AND device_family = $3`,
            [userId, platform, deviceFamily, client.appBuild, now],
          )
        }
        await analytics.syncPerson(tx, userId)
      })
      remembered.add(key)
      if (remembered.size > REMEMBERED_LIMIT) {
        const oldest = remembered.values().next().value
        if (oldest !== undefined) remembered.delete(oldest)
      }
    } catch (error) {
      request.log.warn({ err: error }, "Couldn't record the client")
    }
  })
}

/** Android debug builds leave out the platform header but still name an Android device family. */
function platformOfFamily(client: ClientContext): ClientContext["platform"] {
  return client.deviceFamily === "android_phone" || client.deviceFamily === "android_tablet" ? "android" : null
}

function bearerToken(request: FastifyRequest): string | null {
  const authorization = request.headers.authorization
  return authorization?.startsWith("Bearer ") ? authorization.slice("Bearer ".length) || null : null
}
