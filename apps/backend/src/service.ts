import { randomBytes, randomInt, randomUUID } from "node:crypto"
import type { Database } from "./db.js"
import type {
  AcceptInviteResponse,
  AuthResponse,
  BootstrapResponse,
  CreateInviteResponse,
  GoogleIdentity,
  InviteRecord,
  InviteStatus,
  PairSessionRecord,
  ProfileRecord,
  RedeemInviteResponse,
  SessionRecord,
  UserRecord,
} from "./domain.js"
import type { GoogleTokenVerifier } from "./googleAuth.js"

export class HttpError extends Error {
  constructor(
    readonly statusCode: number,
    message: string,
  ) {
    super(message)
    this.name = "HttpError"
  }
}

interface SessionContext {
  session: SessionRecord
  user: UserRecord
}

export class ElarisService {
  constructor(
    private readonly db: Database,
    private readonly googleVerifier: GoogleTokenVerifier,
  ) {}

  async authenticateWithGoogle(idToken: string): Promise<AuthResponse> {
    const identity = await this.googleVerifier.verify(idToken)
    const user = await this.findOrCreateUser(identity)
    const session = await this.createSession(user.id)
    return this.authResponseForSession(session)
  }

  async refreshSession(refreshToken: string): Promise<AuthResponse> {
    const session = await this.requireSessionByRefreshToken(refreshToken)
    await this.db.query(`UPDATE sessions SET revoked_at = NOW() WHERE id = $1`, [session.id])
    const nextSession = await this.createSession(session.user_id)
    return this.authResponseForSession(nextSession)
  }

  async logout(accessToken: string): Promise<void> {
    await this.db.query(
      `UPDATE sessions SET revoked_at = NOW() WHERE access_token = $1 AND revoked_at IS NULL`,
      [accessToken],
    )
  }

  async getBootstrap(accessToken: string): Promise<BootstrapResponse> {
    const context = await this.requireSessionContext(accessToken)
    return this.buildBootstrap(context.user.id)
  }

  async updateProfile(
    accessToken: string,
    profile: {
      displayName: string
      partnerDisplayName: string
      anniversaryDate: string
    },
  ): Promise<BootstrapResponse> {
    const context = await this.requireSessionContext(accessToken)
    if (!profile.displayName.trim() || !profile.partnerDisplayName.trim() || !profile.anniversaryDate.trim()) {
      throw new HttpError(400, "All onboarding fields are required")
    }

    await this.db.query(
      `
      INSERT INTO user_profiles (
        user_id,
        display_name,
        partner_display_name,
        anniversary_date,
        updated_at
      ) VALUES ($1, $2, $3, $4, NOW())
      ON CONFLICT (user_id) DO UPDATE SET
        display_name = EXCLUDED.display_name,
        partner_display_name = EXCLUDED.partner_display_name,
        anniversary_date = EXCLUDED.anniversary_date,
        updated_at = NOW()
      `,
      [
        context.user.id,
        profile.displayName.trim(),
        profile.partnerDisplayName.trim(),
        profile.anniversaryDate.trim(),
      ],
    )

    return this.buildBootstrap(context.user.id)
  }

  async createInvite(accessToken: string): Promise<CreateInviteResponse> {
    const context = await this.requireSessionContext(accessToken)
    const bootstrap = await this.buildBootstrap(context.user.id)
    if (!bootstrap.onboardingCompleted) {
      throw new HttpError(400, "Complete onboarding before creating an invite")
    }
    if (bootstrap.pairingStatus === "PAIRED") {
      throw new HttpError(400, "Already paired")
    }

    const existing = await this.findActiveInviteForInviter(context.user.id)
    if (existing) {
      return {
        inviteId: existing.id,
        code: existing.code,
        expiresAt: new Date(existing.expires_at).toISOString(),
      }
    }

    const expiresAt = new Date(Date.now() + 24 * 60 * 60 * 1000)
    for (let attempt = 0; attempt < 10; attempt += 1) {
      const inviteId = randomUUID()
      const code = `${randomInt(0, 1_000_000)}`.padStart(6, "0")
      try {
        await this.db.query(
          `
          INSERT INTO invites (
            id,
            inviter_user_id,
            recipient_user_id,
            code,
            status,
            expires_at
          ) VALUES ($1, $2, NULL, $3, 'PENDING', $4)
          `,
          [inviteId, context.user.id, code, expiresAt.toISOString()],
        )
        return {
          inviteId,
          code,
          expiresAt: expiresAt.toISOString(),
        }
      } catch (error) {
        if (!isUniqueViolation(error)) {
          throw error
        }
      }
    }
    throw new HttpError(500, "Unable to create a unique invite code")
  }

  async redeemInvite(accessToken: string, code: string): Promise<RedeemInviteResponse> {
    const context = await this.requireSessionContext(accessToken)
    const bootstrap = await this.buildBootstrap(context.user.id)
    if (bootstrap.pairingStatus === "PAIRED") {
      throw new HttpError(400, "Already paired")
    }

    const invite = await this.requireInviteByCode(code)
    const inviteStatus = this.normalizeInviteStatus(invite)
    if (inviteStatus !== "PENDING") {
      throw new HttpError(400, "Invite code is no longer valid")
    }
    if (invite.inviter_user_id === context.user.id) {
      throw new HttpError(400, "You cannot redeem your own invite")
    }

    await this.db.query(
      `
      UPDATE invites
      SET recipient_user_id = $1, status = 'REDEEMED'
      WHERE id = $2
      `,
      [context.user.id, invite.id],
    )

    const inviterProfile = await this.getProfile(invite.inviter_user_id)
    const recipientProfile = await this.getProfile(context.user.id)
    const nextBootstrap = await this.buildBootstrap(context.user.id)
    return {
      inviteId: invite.id,
      inviterDisplayName: inviterProfile?.display_name ?? "Your partner",
      recipientDisplayName: recipientProfile?.display_name ?? context.user.email,
      code: invite.code,
      status: "REDEEMED",
      bootstrapState: nextBootstrap,
    }
  }

  async acceptInvite(accessToken: string, inviteId: string): Promise<AcceptInviteResponse> {
    const context = await this.requireSessionContext(accessToken)
    const invite = await this.requireInviteById(inviteId)
    const inviteStatus = this.normalizeInviteStatus(invite)
    if (inviteStatus !== "REDEEMED" || invite.recipient_user_id !== context.user.id) {
      throw new HttpError(400, "Invite cannot be accepted")
    }

    const pairId = randomUUID()
    await this.db.query(
      `
      INSERT INTO pair_sessions (id, user_one_id, user_two_id)
      VALUES ($1, $2, $3)
      `,
      [pairId, invite.inviter_user_id, context.user.id],
    )
    await this.db.query(
      `
      UPDATE invites
      SET status = 'ACCEPTED', consumed_at = NOW()
      WHERE id = $1
      `,
      [invite.id],
    )

    return {
      pairSessionId: pairId,
      bootstrapState: await this.buildBootstrap(context.user.id),
    }
  }

  async declineInvite(accessToken: string, inviteId: string): Promise<BootstrapResponse> {
    const context = await this.requireSessionContext(accessToken)
    const invite = await this.requireInviteById(inviteId)
    const inviteStatus = this.normalizeInviteStatus(invite)
    if (inviteStatus !== "REDEEMED" || invite.recipient_user_id !== context.user.id) {
      throw new HttpError(400, "Invite cannot be declined")
    }

    await this.db.query(
      `
      UPDATE invites
      SET status = 'DECLINED', consumed_at = NOW()
      WHERE id = $1
      `,
      [invite.id],
    )
    return this.buildBootstrap(context.user.id)
  }

  private async authResponseForSession(session: SessionRecord): Promise<AuthResponse> {
    return {
      accessToken: session.access_token,
      refreshToken: session.refresh_token,
      userId: session.user_id,
      bootstrapState: await this.buildBootstrap(session.user_id),
    }
  }

  private async findOrCreateUser(identity: GoogleIdentity): Promise<UserRecord> {
    const existing = await this.db.query<UserRecord>(
      `SELECT id, google_subject, email FROM users WHERE google_subject = $1`,
      [identity.subject],
    )
    if (existing.rows[0]) {
      await this.db.query(`UPDATE users SET email = $2 WHERE id = $1`, [
        existing.rows[0].id,
        identity.email,
      ])
      await this.ensureProfileRow(existing.rows[0].id, identity.name)
      return existing.rows[0]
    }

    const user: UserRecord = {
      id: randomUUID(),
      google_subject: identity.subject,
      email: identity.email,
    }
    await this.db.query(
      `INSERT INTO users (id, google_subject, email) VALUES ($1, $2, $3)`,
      [user.id, user.google_subject, user.email],
    )
    await this.ensureProfileRow(user.id, identity.name)
    return user
  }

  private async ensureProfileRow(userId: string, displayName: string | null): Promise<void> {
    await this.db.query(
      `
      INSERT INTO user_profiles (user_id, display_name)
      VALUES ($1, $2)
      ON CONFLICT (user_id) DO NOTHING
      `,
      [userId, displayName],
    )
  }

  private async createSession(userId: string): Promise<SessionRecord> {
    const session: SessionRecord = {
      id: randomUUID(),
      user_id: userId,
      access_token: randomBytes(24).toString("hex"),
      refresh_token: randomBytes(24).toString("hex"),
    }
    await this.db.query(
      `
      INSERT INTO sessions (id, user_id, access_token, refresh_token)
      VALUES ($1, $2, $3, $4)
      `,
      [session.id, session.user_id, session.access_token, session.refresh_token],
    )
    return session
  }

  private async requireSessionContext(accessToken: string): Promise<SessionContext> {
    const sessionRows = await this.db.query<SessionRecord>(
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

    const userRows = await this.db.query<UserRecord>(
      `SELECT id, google_subject, email FROM users WHERE id = $1`,
      [session.user_id],
    )
    const user = userRows.rows[0]
    if (!user) {
      throw new HttpError(401, "Invalid session user")
    }
    return { session, user }
  }

  private async requireSessionByRefreshToken(refreshToken: string): Promise<SessionRecord> {
    const rows = await this.db.query<SessionRecord>(
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

  private async buildBootstrap(userId: string): Promise<BootstrapResponse> {
    const profile = await this.getProfile(userId)
    const pairSession = await this.getPairSession(userId)
    const pendingInvite = await this.getPendingInvite(userId)
    const onboardingCompleted = Boolean(
      profile?.display_name &&
        profile.partner_display_name &&
        profile.anniversary_date,
    )

    return {
      authStatus: "SIGNED_IN",
      onboardingCompleted,
      hasWallpaperConfigured: false,
      userId,
      userDisplayName: profile?.display_name ?? null,
      partnerDisplayName: profile?.partner_display_name ?? null,
      anniversaryDate: profile?.anniversary_date ?? null,
      pairingStatus: pairSession
        ? "PAIRED"
        : pendingInvite
          ? "INVITE_PENDING_ACCEPTANCE"
          : "UNPAIRED",
      pairSessionId: pairSession?.id ?? null,
      invite: pendingInvite
        ? {
            inviteId: pendingInvite.id,
            code: pendingInvite.code,
            inviterDisplayName:
              (await this.getProfile(pendingInvite.inviter_user_id))?.display_name ?? "Your partner",
            recipientDisplayName: profile?.display_name ?? "You",
            status: "REDEEMED",
          }
        : null,
    }
  }

  private async getProfile(userId: string): Promise<ProfileRecord | null> {
    const rows = await this.db.query<{
      user_id: string
      display_name: string | null
      partner_display_name: string | null
      anniversary_date: string | Date | null
    }>(
      `
      SELECT user_id, display_name, partner_display_name, anniversary_date
      FROM user_profiles
      WHERE user_id = $1
      `,
      [userId],
    )
    const row = rows.rows[0]
    if (!row) {
      return null
    }
    return {
      user_id: row.user_id,
      display_name: row.display_name,
      partner_display_name: row.partner_display_name,
      anniversary_date: row.anniversary_date
        ? String(row.anniversary_date).slice(0, 10)
        : null,
    }
  }

  private async getPairSession(userId: string): Promise<PairSessionRecord | null> {
    const rows = await this.db.query<PairSessionRecord>(
      `
      SELECT id, user_one_id, user_two_id
      FROM pair_sessions
      WHERE user_one_id = $1 OR user_two_id = $1
      LIMIT 1
      `,
      [userId],
    )
    return rows.rows[0] ?? null
  }

  private async getPendingInvite(userId: string): Promise<InviteRecord | null> {
    const rows = await this.db.query<InviteRecord>(
      `
      SELECT id, inviter_user_id, recipient_user_id, code, status, expires_at
      FROM invites
      WHERE recipient_user_id = $1 AND status = 'REDEEMED'
      ORDER BY created_at DESC
      LIMIT 1
      `,
      [userId],
    )
    const invite = rows.rows[0]
    if (!invite) {
      return null
    }
    if (this.normalizeInviteStatus(invite) === "EXPIRED") {
      await this.expireInvite(invite.id)
      return null
    }
    return invite
  }

  private async findActiveInviteForInviter(userId: string): Promise<InviteRecord | null> {
    const rows = await this.db.query<InviteRecord>(
      `
      SELECT id, inviter_user_id, recipient_user_id, code, status, expires_at
      FROM invites
      WHERE inviter_user_id = $1 AND status = 'PENDING'
      ORDER BY created_at DESC
      LIMIT 1
      `,
      [userId],
    )
    const invite = rows.rows[0]
    if (!invite) {
      return null
    }
    if (this.normalizeInviteStatus(invite) === "EXPIRED") {
      await this.expireInvite(invite.id)
      return null
    }
    return invite
  }

  private async requireInviteByCode(code: string): Promise<InviteRecord> {
    const rows = await this.db.query<InviteRecord>(
      `
      SELECT id, inviter_user_id, recipient_user_id, code, status, expires_at
      FROM invites
      WHERE code = $1
      LIMIT 1
      `,
      [code],
    )
    const invite = rows.rows[0]
    if (!invite) {
      throw new HttpError(404, "Invite code not found")
    }
    if (this.normalizeInviteStatus(invite) === "EXPIRED") {
      await this.expireInvite(invite.id)
      throw new HttpError(400, "Invite code has expired")
    }
    return invite
  }

  private async requireInviteById(inviteId: string): Promise<InviteRecord> {
    const rows = await this.db.query<InviteRecord>(
      `
      SELECT id, inviter_user_id, recipient_user_id, code, status, expires_at
      FROM invites
      WHERE id = $1
      LIMIT 1
      `,
      [inviteId],
    )
    const invite = rows.rows[0]
    if (!invite) {
      throw new HttpError(404, "Invite not found")
    }
    return invite
  }

  private normalizeInviteStatus(invite: InviteRecord): InviteStatus {
    if ((invite.status === "PENDING" || invite.status === "REDEEMED") &&
      new Date(invite.expires_at).getTime() < Date.now()
    ) {
      return "EXPIRED"
    }
    return invite.status
  }

  private async expireInvite(inviteId: string): Promise<void> {
    await this.db.query(`UPDATE invites SET status = 'EXPIRED' WHERE id = $1`, [inviteId])
  }
}

function isUniqueViolation(error: unknown): boolean {
  return typeof error === "object" && error !== null && "code" in error && error.code === "23505"
}
