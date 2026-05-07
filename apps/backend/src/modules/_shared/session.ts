import { randomBytes, randomUUID } from "node:crypto"
import type { Database } from "../../infra/db/database.js"
import { HttpError } from "../../infra/http/HttpError.js"
import type { PairSessionRecord, SessionRecord, UserRecord } from "../../contracts/dbRecords.js"

export interface SessionContext {
  session: SessionRecord
  user: UserRecord
}

export interface CanvasSessionContext extends SessionContext {
  pairSession: PairSessionRecord
}

export async function createSession(db: Pick<Database, "query">, userId: string): Promise<SessionRecord> {
  const session: SessionRecord = {
    id: randomUUID(),
    user_id: userId,
    access_token: randomBytes(24).toString("hex"),
    refresh_token: randomBytes(24).toString("hex"),
  }
  await db.query(
    `
      INSERT INTO sessions (id, user_id, access_token, refresh_token)
      VALUES ($1, $2, $3, $4)
      `,
    [session.id, session.user_id, session.access_token, session.refresh_token],
  )
  return session
}

export async function requireSessionContext(db: Pick<Database, "query">, accessToken: string): Promise<SessionContext> {
  const sessionRows = await db.query<SessionRecord>(
    `
      SELECT id, user_id, access_token, refresh_token
      FROM sessions
      WHERE access_token = $1 AND revoked_at IS NULL
      `,
    [accessToken],
  )
  const session = sessionRows.rows[0]
  if (!session) {
    throw new HttpError(401, "Invalid session")
  }

  const userRows = await db.query<UserRecord>(
    `SELECT id, google_subject, email, google_picture_url FROM users WHERE id = $1`,
    [session.user_id],
  )
  const user = userRows.rows[0]
  if (!user) {
    throw new HttpError(401, "Invalid session user")
  }
  return { session, user }
}

export async function requireSessionByRefreshToken(
  db: Pick<Database, "query">,
  refreshToken: string,
): Promise<SessionRecord> {
  const rows = await db.query<SessionRecord>(
    `
      SELECT id, user_id, access_token, refresh_token
      FROM sessions
      WHERE refresh_token = $1 AND revoked_at IS NULL
      `,
    [refreshToken],
  )
  if (!rows.rows[0]) {
    throw new HttpError(401, "Invalid refresh token")
  }
  return rows.rows[0]
}

export async function getUserById(db: Pick<Database, "query">, userId: string): Promise<UserRecord | null> {
  const rows = await db.query<UserRecord>(
    `SELECT id, google_subject, email, google_picture_url FROM users WHERE id = $1`,
    [userId],
  )
  return rows.rows[0] ?? null
}

