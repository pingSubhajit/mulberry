import { randomBytes, randomUUID } from "node:crypto"
import type { Database } from "../../infra/db/database.js"
import { HttpError } from "../../infra/http/HttpError.js"
import type { PairSessionRecord, SessionRecord, UserRecord } from "../../contracts/dbRecords.js"

/** The columns of a `UserRecord`. */
export const USER_COLUMNS = "id, google_subject, apple_subject, email, google_picture_url, first_drew_at"

export interface SessionContext {
  session: SessionRecord
  user: UserRecord
}

export interface CanvasSessionContext extends SessionContext {
  pairSession: PairSessionRecord
}

/** Starts a new sign-in, or continues `signInId` when a session is refreshed. */
export async function createSession(
  db: Pick<Database, "query">,
  userId: string,
  signInId?: string,
): Promise<SessionRecord> {
  const id = randomUUID()
  const session: SessionRecord = {
    id,
    sign_in_id: signInId ?? id,
    user_id: userId,
    access_token: randomBytes(24).toString("hex"),
    refresh_token: randomBytes(24).toString("hex"),
  }
  await db.query(
    `
      INSERT INTO sessions (id, sign_in_id, user_id, access_token, refresh_token)
      VALUES ($1, $2, $3, $4, $5)
      `,
    [session.id, session.sign_in_id, session.user_id, session.access_token, session.refresh_token],
  )
  return session
}

export async function requireSessionContext(db: Pick<Database, "query">, accessToken: string): Promise<SessionContext> {
  const sessionRows = await db.query<SessionRecord>(
    `
      SELECT id, sign_in_id, user_id, access_token, refresh_token
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
    `SELECT ${USER_COLUMNS} FROM users WHERE id = $1`,
    [session.user_id],
  )
  const user = userRows.rows[0]
  if (!user) {
    throw new HttpError(401, "Invalid session user")
  }
  return { session, user }
}

/** One row of `requireSessionWithPair`: the session, its user and the user's pair, if any. */
interface SessionWithPairRow {
  session_id: string
  sign_in_id: string
  user_id: string
  access_token: string
  refresh_token: string
  joined_user_id: string | null
  google_subject: UserRecord["google_subject"]
  apple_subject: UserRecord["apple_subject"]
  email: UserRecord["email"] | null
  google_picture_url: UserRecord["google_picture_url"]
  first_drew_at: UserRecord["first_drew_at"]
  pair_id: string | null
  user_one_id: string | null
  user_two_id: string | null
  canvas_stroke_render_mode: string | null
  pair_created_at: PairSessionRecord["created_at"] | null
}

/**
 * `requireSessionContext` and `getPairSession` in one database round trip, for the requests that
 * need both on every call (canvas sync, reactions). Fails the same way `requireSessionContext` does.
 */
export async function requireSessionWithPair(
  db: Pick<Database, "query">,
  accessToken: string,
): Promise<SessionContext & { pairSession: PairSessionRecord | null }> {
  const rows = await db.query<SessionWithPairRow>(
    `
      SELECT s.id AS session_id, s.sign_in_id, s.user_id, s.access_token, s.refresh_token,
        u.id AS joined_user_id, u.google_subject, u.apple_subject, u.email, u.google_picture_url, u.first_drew_at,
        p.id AS pair_id, p.user_one_id, p.user_two_id, p.canvas_stroke_render_mode, p.created_at AS pair_created_at
      FROM sessions s
      LEFT JOIN users u ON u.id = s.user_id
      LEFT JOIN pair_sessions p ON (p.user_one_id = s.user_id OR p.user_two_id = s.user_id)
      WHERE s.access_token = $1 AND s.revoked_at IS NULL
      LIMIT 1
      `,
    [accessToken],
  )
  const row = rows.rows[0]
  if (!row) {
    throw new HttpError(401, "Invalid session")
  }
  if (!row.joined_user_id || row.email === null) {
    throw new HttpError(401, "Invalid session user")
  }
  const session: SessionRecord = {
    id: row.session_id,
    sign_in_id: row.sign_in_id,
    user_id: row.user_id,
    access_token: row.access_token,
    refresh_token: row.refresh_token,
  }
  const user: UserRecord = {
    id: row.joined_user_id,
    google_subject: row.google_subject,
    apple_subject: row.apple_subject,
    email: row.email,
    google_picture_url: row.google_picture_url,
    first_drew_at: row.first_drew_at,
  }
  const pairSession: PairSessionRecord | null =
    row.pair_id && row.user_one_id && row.user_two_id && row.canvas_stroke_render_mode !== null && row.pair_created_at !== null
      ? {
          id: row.pair_id,
          user_one_id: row.user_one_id,
          user_two_id: row.user_two_id,
          canvas_stroke_render_mode: row.canvas_stroke_render_mode,
          created_at: row.pair_created_at,
        }
      : null
  return { session, user, pairSession }
}

export async function requireSessionByRefreshToken(
  db: Pick<Database, "query">,
  refreshToken: string,
): Promise<SessionRecord> {
  const rows = await db.query<SessionRecord>(
    `
      SELECT id, sign_in_id, user_id, access_token, refresh_token
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
    `SELECT ${USER_COLUMNS} FROM users WHERE id = $1`,
    [userId],
  )
  return rows.rows[0] ?? null
}

