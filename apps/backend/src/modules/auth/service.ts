import { randomUUID } from "node:crypto"
import type { Database } from "../../infra/db/database.js"
import type { GoogleTokenVerifier } from "../../infra/auth/googleTokenVerifier.js"
import type { AuthResponse } from "../../contracts/auth.js"
import type { UserRecord } from "../../contracts/dbRecords.js"
import type { GoogleIdentity } from "../../infra/auth/googleTokenVerifier.js"
import { createSession, requireSessionByRefreshToken } from "../_shared/session.js"
import { ensureProfileRow } from "../_shared/profiles.js"
import type { BootstrapService } from "../bootstrap/service.js"

export class AuthService {
  constructor(
    private readonly db: Database,
    private readonly googleVerifier: GoogleTokenVerifier,
    private readonly bootstrapService: BootstrapService,
  ) {}

  async authenticateWithGoogle(idToken: string): Promise<AuthResponse> {
    const identity = await this.googleVerifier.verify(idToken)
    const user = await this.findOrCreateUser(identity)
    const session = await createSession(this.db, user.id)
    return this.authResponseForSession(session)
  }

  async refreshSession(refreshToken: string): Promise<AuthResponse> {
    const session = await requireSessionByRefreshToken(this.db, refreshToken)
    await this.db.query(`UPDATE sessions SET revoked_at = NOW() WHERE id = $1`, [session.id])
    const nextSession = await createSession(this.db, session.user_id)
    return this.authResponseForSession(nextSession)
  }

  async logout(accessToken: string): Promise<void> {
    await this.db.query(
      `UPDATE sessions SET revoked_at = NOW() WHERE access_token = $1 AND revoked_at IS NULL`,
      [accessToken],
    )
  }

  private async authResponseForSession(session: { access_token: string; refresh_token: string; user_id: string }): Promise<AuthResponse> {
    return {
      accessToken: session.access_token,
      refreshToken: session.refresh_token,
      userId: session.user_id,
      bootstrapState: await this.bootstrapService.buildBootstrap(session.user_id),
    }
  }

  private async findOrCreateUser(identity: GoogleIdentity): Promise<UserRecord> {
    const existing = await this.db.query<UserRecord>(
      `SELECT id, google_subject, email, google_picture_url FROM users WHERE google_subject = $1`,
      [identity.subject],
    )
    if (existing.rows[0]) {
      await this.db.query(`UPDATE users SET email = $2, google_picture_url = $3 WHERE id = $1`, [
        existing.rows[0].id,
        identity.email,
        identity.pictureUrl,
      ])
      await ensureProfileRow(this.db, existing.rows[0].id, identity.name)
      return {
        ...existing.rows[0],
        email: identity.email,
        google_picture_url: identity.pictureUrl,
      }
    }

    const user: UserRecord = {
      id: randomUUID(),
      google_subject: identity.subject,
      email: identity.email,
      google_picture_url: identity.pictureUrl,
    }
    await this.db.query(
      `INSERT INTO users (id, google_subject, email, google_picture_url) VALUES ($1, $2, $3, $4)`,
      [user.id, user.google_subject, user.email, user.google_picture_url],
    )
    await ensureProfileRow(this.db, user.id, identity.name)
    return user
  }
}

