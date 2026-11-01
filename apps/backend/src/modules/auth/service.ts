import { randomUUID } from "node:crypto"
import type { Database } from "../../infra/db/database.js"
import type { GoogleTokenVerifier } from "../../infra/auth/googleTokenVerifier.js"
import type { AppleIdentity, AppleTokenVerifier } from "../../infra/auth/appleTokenVerifier.js"
import type { AppleCredentialClient } from "../../infra/auth/appleCredentialClient.js"
import type { AppleSignInRequest, AuthResponse } from "../../contracts/auth.js"
import type { BootstrapResponse } from "../../contracts/bootstrap.js"
import type { UserRecord } from "../../contracts/dbRecords.js"
import type { GoogleIdentity } from "../../infra/auth/googleTokenVerifier.js"
import { HttpError } from "../../infra/http/HttpError.js"
import { createSession, requireSessionByRefreshToken, requireSessionContext, USER_COLUMNS } from "../_shared/session.js"
import { ensureProfileRow } from "../_shared/profiles.js"
import type { BootstrapService } from "../bootstrap/service.js"

/** The column holding each sign-in method's subject. */
type SubjectColumn = "google_subject" | "apple_subject"

export class AuthService {
  constructor(
    private readonly db: Database,
    private readonly googleVerifier: GoogleTokenVerifier,
    private readonly bootstrapService: BootstrapService,
    private readonly appleVerifier: AppleTokenVerifier,
    private readonly appleCredentials: AppleCredentialClient,
  ) {}

  async authenticateWithGoogle(idToken: string): Promise<AuthResponse> {
    const identity = await this.googleVerifier.verify(idToken)
    const user = await this.findOrCreateUser(identity)
    const session = await createSession(this.db, user.id)
    return this.authResponseForSession(session)
  }

  async authenticateWithApple(request: AppleSignInRequest): Promise<AuthResponse> {
    const identity = await this.appleVerifier.verify(request.identityToken, request.nonce)
    const user = await this.findOrCreateAppleUser(identity, request.displayName ?? null)
    await this.storeAppleCredential(user.id, identity.clientId, request.authorizationCode)
    const session = await createSession(this.db, user.id)
    return this.authResponseForSession(session)
  }

  /** Lets the signed-in account sign in with Google too. */
  async linkGoogle(accessToken: string, idToken: string): Promise<BootstrapResponse> {
    const { user } = await requireSessionContext(this.db, accessToken)
    const identity = await asBadRequest(this.googleVerifier.verify(idToken))
    await this.requireUnclaimed("google_subject", identity.subject, user.id)
    await this.db.query(
      `UPDATE users SET google_subject = $2, google_picture_url = COALESCE(google_picture_url, $3) WHERE id = $1`,
      [user.id, identity.subject, identity.pictureUrl],
    )
    return this.bootstrapService.buildBootstrap(user.id)
  }

  /** Lets the signed-in account sign in with Apple too. */
  async linkApple(accessToken: string, request: AppleSignInRequest): Promise<BootstrapResponse> {
    const { user } = await requireSessionContext(this.db, accessToken)
    const identity = await asBadRequest(this.appleVerifier.verify(request.identityToken, request.nonce))
    await this.requireUnclaimed("apple_subject", identity.subject, user.id)
    await this.db.query(`UPDATE users SET apple_subject = $2 WHERE id = $1`, [user.id, identity.subject])
    await this.storeAppleCredential(user.id, identity.clientId, request.authorizationCode)
    return this.bootstrapService.buildBootstrap(user.id)
  }

  async refreshSession(refreshToken: string): Promise<AuthResponse> {
    const session = await requireSessionByRefreshToken(this.db, refreshToken)
    await this.db.query(`UPDATE sessions SET revoked_at = NOW() WHERE id = $1`, [session.id])
    const nextSession = await createSession(this.db, session.user_id, session.sign_in_id)
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
    const existing = await this.userWithSubject("google_subject", identity.subject)
    if (existing) {
      await this.db.query(`UPDATE users SET email = $2, google_picture_url = $3 WHERE id = $1`, [
        existing.id,
        identity.email,
        identity.pictureUrl,
      ])
      await ensureProfileRow(this.db, existing.id, identity.name)
      return {
        ...existing,
        email: identity.email,
        google_picture_url: identity.pictureUrl,
      }
    }

    const linked = await this.linkByEmail(identity.email, identity.emailVerified, "google_subject", identity.subject)
    if (linked) {
      await this.db.query(`UPDATE users SET google_picture_url = COALESCE(google_picture_url, $2) WHERE id = $1`, [
        linked.id,
        identity.pictureUrl,
      ])
      await ensureProfileRow(this.db, linked.id, identity.name)
      return { ...linked, google_picture_url: linked.google_picture_url ?? identity.pictureUrl }
    }

    const user: UserRecord = {
      id: randomUUID(),
      google_subject: identity.subject,
      apple_subject: null,
      email: identity.email,
      google_picture_url: identity.pictureUrl,
      first_drew_at: null,
    }
    await this.db.query(
      `INSERT INTO users (id, google_subject, email, google_picture_url) VALUES ($1, $2, $3, $4)`,
      [user.id, user.google_subject, user.email, user.google_picture_url],
    )
    await ensureProfileRow(this.db, user.id, identity.name)
    return user
  }

  private async findOrCreateAppleUser(identity: AppleIdentity, displayName: string | null): Promise<UserRecord> {
    const existing = await this.userWithSubject("apple_subject", identity.subject)
    if (existing) return existing

    if (!identity.email) {
      throw new HttpError(401, "Apple didn’t share an email address. Please try again.")
    }
    const linked = await this.linkByEmail(identity.email, identity.emailVerified, "apple_subject", identity.subject)
    if (linked) {
      await ensureProfileRow(this.db, linked.id, displayName)
      return linked
    }

    const user: UserRecord = {
      id: randomUUID(),
      google_subject: null,
      apple_subject: identity.subject,
      email: identity.email,
      google_picture_url: null,
      first_drew_at: null,
    }
    await this.db.query(`INSERT INTO users (id, apple_subject, email) VALUES ($1, $2, $3)`, [
      user.id,
      user.apple_subject,
      user.email,
    ])
    await ensureProfileRow(this.db, user.id, displayName)
    return user
  }

  private async userWithSubject(column: SubjectColumn, subject: string): Promise<UserRecord | null> {
    const rows = await this.db.query<UserRecord>(`SELECT ${USER_COLUMNS} FROM users WHERE ${column} = $1`, [subject])
    return rows.rows[0] ?? null
  }

  /**
   * Signs a new Google or Apple identity in to the account that already uses its email address,
   * so one person keeps one account whichever button they press. Only an address the provider has
   * verified can claim an account. A Hide My Email address never matches one.
   */
  private async linkByEmail(
    email: string,
    emailVerified: boolean,
    column: SubjectColumn,
    subject: string,
  ): Promise<UserRecord | null> {
    const rows = await this.db.query<UserRecord>(
      `SELECT ${USER_COLUMNS} FROM users WHERE LOWER(email) = LOWER($1)`,
      [email],
    )
    const match = rows.rows[0]
    if (!match) return null
    if (!emailVerified || match[column] !== null) {
      throw new HttpError(409, "This email already belongs to another Mulberry account. Sign in the way you did before.")
    }
    await this.db.query(`UPDATE users SET ${column} = $2 WHERE id = $1`, [match.id, subject])
    return { ...match, [column]: subject }
  }

  private async requireUnclaimed(column: SubjectColumn, subject: string, userId: string): Promise<void> {
    const owner = await this.userWithSubject(column, subject)
    if (owner && owner.id !== userId) {
      const provider = column === "google_subject" ? "Google" : "Apple"
      throw new HttpError(409, `That ${provider} account already signs in to another Mulberry account.`)
    }
  }

  /**
   * Keeps Apple's refresh token, to revoke it if the account is deleted. Sign-in doesn't depend on
   * it: a failed exchange is logged, and the next sign-in tries again.
   */
  private async storeAppleCredential(userId: string, clientId: string, authorizationCode: string | undefined): Promise<void> {
    if (!authorizationCode) return
    try {
      const refreshToken = await this.appleCredentials.exchangeAuthorizationCode(authorizationCode, clientId)
      if (!refreshToken) return
      await this.db.query(`UPDATE users SET apple_client_id = $2, apple_refresh_token = $3 WHERE id = $1`, [
        userId,
        clientId,
        refreshToken,
      ])
    } catch (error) {
      console.error("[auth] Apple authorization code exchange failed", {
        userId,
        error: error instanceof Error ? error.message : String(error),
      })
    }
  }
}

/**
 * A refused Google or Apple token while linking is a bad request, not a bad session: the apps
 * read a 401 as their own session ending.
 */
async function asBadRequest<T>(verification: Promise<T>): Promise<T> {
  try {
    return await verification
  } catch (error) {
    if (error instanceof HttpError && error.statusCode === 401) throw new HttpError(400, error.message)
    throw error
  }
}
