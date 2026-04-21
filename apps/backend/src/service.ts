import { randomBytes, randomInt, randomUUID } from "node:crypto"
import type { Database } from "./db.js"
import type {
  AcceptInviteResponse,
  AuthResponse,
  BootstrapResponse,
  CanvasOperationEnvelope,
  CanvasOperationRecord,
  CanvasOpsResponse,
  CanvasSnapshotResponse,
  CanvasSyncBootstrap,
  ClientCanvasOperationBatch,
  ClientCanvasOperation,
  CreateInviteResponse,
  DeviceTokenRecord,
  DeviceTokenRow,
  GoogleIdentity,
  InviteRecord,
  InviteStatus,
  PairSessionRecord,
  ProfileRecord,
  RegisterFcmTokenRequest,
  RedeemInviteResponse,
  SessionRecord,
  UserRecord,
} from "./domain.js"
import type { GoogleTokenVerifier } from "./googleAuth.js"
import type { PushDispatchService } from "./push.js"

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

interface CanvasSessionContext extends SessionContext {
  pairSession: PairSessionRecord
}

interface MaterializedStroke {
  id: string
  colorArgb: number
  width: number
  createdAt: number
  points: Array<{ x: number; y: number }>
  finished: boolean
}

export class MulberryService {
  constructor(
    private readonly db: Database,
    private readonly googleVerifier: GoogleTokenVerifier,
    private readonly pushDispatchService?: PushDispatchService,
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

  async registerFcmToken(
    accessToken: string,
    request: RegisterFcmTokenRequest,
  ): Promise<DeviceTokenRecord> {
    const context = await this.requireSessionContext(accessToken)
    if (!request.token?.trim()) {
      throw new HttpError(400, "token is required")
    }
    if (request.platform !== "ANDROID") {
      throw new HttpError(400, "Unsupported device platform")
    }
    if (!request.appEnvironment?.trim()) {
      throw new HttpError(400, "appEnvironment is required")
    }

    const tokenId = randomUUID()
    const rows = await this.db.query<DeviceTokenRow>(
      `
      INSERT INTO device_tokens (
        id,
        user_id,
        token,
        platform,
        app_environment,
        last_seen_at,
        revoked_at
      ) VALUES ($1, $2, $3, $4, $5, NOW(), NULL)
      ON CONFLICT (token) DO UPDATE SET
        user_id = EXCLUDED.user_id,
        platform = EXCLUDED.platform,
        app_environment = EXCLUDED.app_environment,
        last_seen_at = NOW(),
        revoked_at = NULL
      RETURNING id, user_id, token, platform, app_environment, last_seen_at, revoked_at
      `,
      [
        tokenId,
        context.user.id,
        request.token.trim(),
        request.platform,
        request.appEnvironment.trim(),
      ],
    )
    return this.deviceTokenToRecord(rows.rows[0])
  }

  async unregisterFcmToken(accessToken: string, token: string): Promise<void> {
    const context = await this.requireSessionContext(accessToken)
    if (!token.trim()) {
      throw new HttpError(400, "token is required")
    }

    await this.db.query(
      `
      UPDATE device_tokens
      SET revoked_at = NOW()
      WHERE user_id = $1 AND token = $2 AND revoked_at IS NULL
      `,
      [context.user.id, token.trim()],
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
    if (inviteStatus === "REDEEMED" && invite.recipient_user_id === context.user.id) {
      return this.redeemInviteResponseFor(context.user.id, invite, "REDEEMED")
    }
    if (inviteStatus !== "PENDING") {
      throw new HttpError(400, "Invite code is no longer valid")
    }
    if (invite.inviter_user_id === context.user.id) {
      throw new HttpError(400, "You cannot redeem your own invite")
    }

    const inviterProfile = await this.getProfile(invite.inviter_user_id)
    const recipientProfileBeforeRedeem = await this.getProfile(context.user.id)
    const recipientOnboardingCompleted = Boolean(
      recipientProfileBeforeRedeem?.display_name &&
        recipientProfileBeforeRedeem.partner_display_name &&
        recipientProfileBeforeRedeem.anniversary_date,
    )
    await this.hydrateRecipientProfileFromInvite(
      context.user.id,
      recipientProfileBeforeRedeem,
      inviterProfile,
      recipientOnboardingCompleted,
    )

    await this.db.query(
      `
      UPDATE invites
      SET recipient_user_id = $1, status = 'REDEEMED'
      WHERE id = $2
      `,
      [context.user.id, invite.id],
    )

    return this.redeemInviteResponseFor(context.user.id, invite, "REDEEMED")
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
    await this.clearProfileAfterInviteDecline(context.user.id)
    return this.buildBootstrap(context.user.id)
  }

  async bootstrapCanvasSync(
    accessToken: string,
    pairSessionId: string,
    lastAppliedRevision: number,
  ): Promise<CanvasSyncBootstrap> {
    const context = await this.requireCanvasSessionContext(accessToken, pairSessionId)
    const missedOperations = await this.listCanvasOperationsForPair(
      context.pairSession.id,
      lastAppliedRevision,
    )
    return {
      pairSessionId: context.pairSession.id,
      userId: context.user.id,
      latestRevision: missedOperations.at(-1)?.serverRevision ??
        (await this.getLatestCanvasRevision(context.pairSession.id)),
      missedOperations,
    }
  }

  async acceptCanvasOperationForSession(
    accessToken: string,
    pairSessionId: string,
    operation: ClientCanvasOperation,
  ): Promise<CanvasOperationEnvelope> {
    const context = await this.requireCanvasSessionContext(accessToken, pairSessionId)
    return this.acceptCanvasOperation(context, operation)
  }

  async acceptCanvasOperationBatchForSession(
    accessToken: string,
    pairSessionId: string,
    batch: ClientCanvasOperationBatch,
  ): Promise<CanvasOperationEnvelope[]> {
    const context = await this.requireCanvasSessionContext(accessToken, pairSessionId)
    if (!batch.batchId.trim()) {
      throw new HttpError(400, "batchId is required")
    }
    if (!Array.isArray(batch.operations) || batch.operations.length === 0) {
      throw new HttpError(400, "CLIENT_OP_BATCH requires operations")
    }
    if (batch.operations.length > 128) {
      throw new HttpError(413, "CLIENT_OP_BATCH is too large")
    }

    const accepted: CanvasOperationEnvelope[] = []
    for (const operation of batch.operations) {
      accepted.push(await this.acceptCanvasOperation(context, operation))
    }
    return accepted
  }

  async listCanvasOperations(
    accessToken: string,
    afterRevision: number,
  ): Promise<CanvasOpsResponse> {
    const context = await this.requireDefaultCanvasSessionContext(accessToken)
    return {
      operations: await this.listCanvasOperationsForPair(
        context.pairSession.id,
        afterRevision,
      ),
    }
  }

  async getCanvasSnapshot(accessToken: string): Promise<CanvasSnapshotResponse> {
    const context = await this.requireDefaultCanvasSessionContext(accessToken)
    const row = await this.getOrCreateCanvasSnapshot(context.pairSession.id)
    return {
      pairSessionId: context.pairSession.id,
      revision: Number(row.revision),
      snapshot: row.snapshot_json,
      updatedAt: row.updated_at ? new Date(row.updated_at).toISOString() : null,
    }
  }

  async acceptCanvasOperation(
    context: CanvasSessionContext,
    operation: ClientCanvasOperation,
  ): Promise<CanvasOperationEnvelope> {
    if (!operation.clientOperationId.trim()) {
      throw new HttpError(400, "clientOperationId is required")
    }
    if (!operation.type) {
      throw new HttpError(400, "operation type is required")
    }

    const duplicate = await this.db.query<CanvasOperationRecord>(
      `
      SELECT id, pair_session_id, server_revision, client_operation_id, actor_user_id,
        type, stroke_id, payload_json, client_created_at, created_at
      FROM canvas_operations
      WHERE pair_session_id = $1 AND actor_user_id = $2 AND client_operation_id = $3
      LIMIT 1
      `,
      [context.pairSession.id, context.user.id, operation.clientOperationId],
    )
    if (duplicate.rows[0]) {
      return this.canvasOperationToEnvelope(duplicate.rows[0])
    }

    const latestRevision = await this.getLatestCanvasRevision(context.pairSession.id)
    const nextRevision = latestRevision + 1
    const id = randomUUID()
    const clientCreatedAt = validDateOrNow(operation.clientCreatedAt)
    await this.db.query(
      `
      INSERT INTO canvas_operations (
        id,
        pair_session_id,
        server_revision,
        client_operation_id,
        actor_user_id,
        type,
        stroke_id,
        payload_json,
        client_created_at
      ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8::jsonb, $9)
      `,
      [
        id,
        context.pairSession.id,
        nextRevision,
        operation.clientOperationId,
        context.user.id,
        operation.type,
        operation.strokeId ?? null,
        JSON.stringify(operation.payload ?? {}),
        clientCreatedAt.toISOString(),
      ],
    )

    await this.applyOperationToSnapshot(context.pairSession.id, {
      type: operation.type,
      strokeId: operation.strokeId ?? null,
      payload: operation.payload,
      revision: nextRevision,
    })

    const rows = await this.db.query<CanvasOperationRecord>(
      `
      SELECT id, pair_session_id, server_revision, client_operation_id, actor_user_id,
        type, stroke_id, payload_json, client_created_at, created_at
      FROM canvas_operations
      WHERE id = $1
      `,
      [id],
    )
    const accepted = this.canvasOperationToEnvelope(rows.rows[0])
    if (shouldSendCanvasUpdatePush(accepted.type)) {
      this.pushDispatchService?.enqueueCanvasUpdated(
        context.pairSession.id,
        context.user.id,
        accepted.serverRevision,
      )
    }
    return accepted
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

  private async hydrateRecipientProfileFromInvite(
    recipientUserId: string,
    recipientProfile: ProfileRecord | null,
    inviterProfile: ProfileRecord | null,
    recipientOnboardingCompleted: boolean,
  ): Promise<void> {
    const recipientDisplayName = recipientOnboardingCompleted
      ? recipientProfile?.display_name
      : inviterProfile?.partner_display_name ?? recipientProfile?.display_name
    const partnerDisplayName = inviterProfile?.display_name ?? recipientProfile?.partner_display_name
    const anniversaryDate = inviterProfile?.anniversary_date ?? recipientProfile?.anniversary_date

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
        recipientUserId,
        recipientDisplayName ?? null,
        partnerDisplayName ?? null,
        anniversaryDate ?? null,
      ],
    )
  }

  private async clearProfileAfterInviteDecline(userId: string): Promise<void> {
    await this.db.query(
      `
      UPDATE user_profiles
      SET
        display_name = NULL,
        partner_display_name = NULL,
        anniversary_date = NULL,
        updated_at = NOW()
      WHERE user_id = $1
      `,
      [userId],
    )
  }

  private async redeemInviteResponseFor(
    recipientUserId: string,
    invite: InviteRecord,
    status: "REDEEMED",
  ): Promise<RedeemInviteResponse> {
    const inviterProfile = await this.getProfile(invite.inviter_user_id)
    const recipientProfile = await this.getProfile(recipientUserId)
    const recipientUser = await this.getUserById(recipientUserId)
    return {
      inviteId: invite.id,
      inviterDisplayName: inviterProfile?.display_name ?? "Your partner",
      recipientDisplayName: recipientProfile?.display_name ?? recipientUser?.email ?? "You",
      code: invite.code,
      status,
      bootstrapState: await this.buildBootstrap(recipientUserId),
    }
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

  private async requireDefaultCanvasSessionContext(accessToken: string): Promise<CanvasSessionContext> {
    const context = await this.requireSessionContext(accessToken)
    const pairSession = await this.getPairSession(context.user.id)
    if (!pairSession) {
      throw new HttpError(403, "User is not paired")
    }
    return { ...context, pairSession }
  }

  private async requireCanvasSessionContext(
    accessToken: string,
    pairSessionId: string,
  ): Promise<CanvasSessionContext> {
    const context = await this.requireSessionContext(accessToken)
    const pairSession = await this.getPairSession(context.user.id)
    if (!pairSession || pairSession.id !== pairSessionId) {
      throw new HttpError(403, "User is not in this pair session")
    }
    return { ...context, pairSession }
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

  private async getUserById(userId: string): Promise<UserRecord | null> {
    const rows = await this.db.query<UserRecord>(
      `SELECT id, google_subject, email FROM users WHERE id = $1`,
      [userId],
    )
    return rows.rows[0] ?? null
  }

  private async buildBootstrap(userId: string): Promise<BootstrapResponse> {
    let profile = await this.getProfile(userId)
    const pairSession = await this.getPairSession(userId)
    const pendingInvite = await this.getPendingInvite(userId)
    let onboardingCompleted = Boolean(
      profile?.display_name &&
        profile.partner_display_name &&
        profile.anniversary_date,
    )
    if (pendingInvite && !onboardingCompleted) {
      await this.hydrateRecipientProfileFromInvite(
        userId,
        profile,
        await this.getProfile(pendingInvite.inviter_user_id),
        false,
      )
      profile = await this.getProfile(userId)
      onboardingCompleted = Boolean(
        profile?.display_name &&
          profile.partner_display_name &&
          profile.anniversary_date,
      )
    }

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
      anniversary_date: normalizeDateString(row.anniversary_date),
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

  private async getLatestCanvasRevision(pairSessionId: string): Promise<number> {
    const rows = await this.db.query<{ revision: string | number }>(
      `
      SELECT COALESCE(MAX(server_revision), 0) AS revision
      FROM canvas_operations
      WHERE pair_session_id = $1
      `,
      [pairSessionId],
    )
    return Number(rows.rows[0]?.revision ?? 0)
  }

  private async listCanvasOperationsForPair(
    pairSessionId: string,
    afterRevision: number,
  ): Promise<CanvasOperationEnvelope[]> {
    const rows = await this.db.query<CanvasOperationRecord>(
      `
      SELECT id, pair_session_id, server_revision, client_operation_id, actor_user_id,
        type, stroke_id, payload_json, client_created_at, created_at
      FROM canvas_operations
      WHERE pair_session_id = $1 AND server_revision > $2
      ORDER BY server_revision ASC
      `,
      [pairSessionId, Math.max(0, Math.floor(afterRevision))],
    )
    return rows.rows.map((row) => this.canvasOperationToEnvelope(row))
  }

  private async getOrCreateCanvasSnapshot(pairSessionId: string): Promise<{
    revision: string | number
    snapshot_json: unknown
    updated_at: Date | string
  }> {
    await this.db.query(
      `
      INSERT INTO canvas_snapshots (pair_session_id)
      VALUES ($1)
      ON CONFLICT (pair_session_id) DO NOTHING
      `,
      [pairSessionId],
    )
    const rows = await this.db.query<{
      revision: string | number
      snapshot_json: unknown
      updated_at: Date | string
    }>(
      `
      SELECT revision, snapshot_json, updated_at
      FROM canvas_snapshots
      WHERE pair_session_id = $1
      `,
      [pairSessionId],
    )
    return rows.rows[0]
  }

  private async applyOperationToSnapshot(
    pairSessionId: string,
    operation: {
      type: string
      strokeId: string | null
      payload: unknown
      revision: number
    },
  ): Promise<void> {
    const snapshotRow = await this.getOrCreateCanvasSnapshot(pairSessionId)
    const snapshot = normalizeCanvasSnapshot(snapshotRow.snapshot_json)
    switch (operation.type) {
      case "ADD_STROKE": {
        const payload = operation.payload as Partial<MaterializedStroke> & {
          firstPoint?: { x: number; y: number }
        }
        const strokeId = operation.strokeId ?? payload.id
        if (!strokeId || !payload.firstPoint) break
        snapshot.strokes = snapshot.strokes.filter((stroke) => stroke.id !== strokeId)
        snapshot.strokes.push({
          id: strokeId,
          colorArgb: Number(payload.colorArgb ?? 0xff111111),
          width: Number(payload.width ?? 8),
          createdAt: Number(payload.createdAt ?? Date.now()),
          points: [payload.firstPoint],
          finished: false,
        })
        break
      }
      case "APPEND_POINTS": {
        const payload = operation.payload as { points?: Array<{ x: number; y: number }> }
        const stroke = snapshot.strokes.find((item) => item.id === operation.strokeId)
        if (stroke && Array.isArray(payload.points)) {
          stroke.points.push(...payload.points)
        }
        break
      }
      case "FINISH_STROKE": {
        const stroke = snapshot.strokes.find((item) => item.id === operation.strokeId)
        if (stroke) stroke.finished = true
        break
      }
      case "DELETE_STROKE":
        snapshot.strokes = snapshot.strokes.filter((stroke) => stroke.id !== operation.strokeId)
        break
      case "CLEAR_CANVAS":
        snapshot.strokes = []
        break
    }

    await this.db.query(
      `
      UPDATE canvas_snapshots
      SET revision = $2, snapshot_json = $3::jsonb, updated_at = NOW()
      WHERE pair_session_id = $1
      `,
      [pairSessionId, operation.revision, JSON.stringify(snapshot)],
    )
  }

  private canvasOperationToEnvelope(row: CanvasOperationRecord): CanvasOperationEnvelope {
    return {
      clientOperationId: row.client_operation_id,
      actorUserId: row.actor_user_id,
      pairSessionId: row.pair_session_id,
      type: row.type,
      strokeId: row.stroke_id,
      payload: row.payload_json,
      clientCreatedAt: new Date(row.client_created_at).toISOString(),
      serverRevision: Number(row.server_revision),
      createdAt: new Date(row.created_at).toISOString(),
    }
  }

  private deviceTokenToRecord(row: DeviceTokenRow): DeviceTokenRecord {
    return {
      id: row.id,
      userId: row.user_id,
      token: row.token,
      platform: row.platform,
      appEnvironment: row.app_environment,
      lastSeenAt: new Date(row.last_seen_at).toISOString(),
      revokedAt: row.revoked_at ? new Date(row.revoked_at).toISOString() : null,
    }
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

function validDateOrNow(raw: string): Date {
  const parsed = new Date(raw)
  return Number.isNaN(parsed.getTime()) ? new Date() : parsed
}

function normalizeDateString(raw: string | Date | null): string | null {
  if (!raw) return null
  if (raw instanceof Date) {
    return raw.toISOString().slice(0, 10)
  }
  if (/^\d{4}-\d{2}-\d{2}/.test(raw)) {
    return raw.slice(0, 10)
  }
  const parsed = new Date(raw)
  if (!Number.isNaN(parsed.getTime())) {
    return parsed.toISOString().slice(0, 10)
  }
  return raw.slice(0, 10)
}

function normalizeCanvasSnapshot(raw: unknown): { strokes: MaterializedStroke[] } {
  if (
    typeof raw === "object" &&
    raw !== null &&
    "strokes" in raw &&
    Array.isArray((raw as { strokes: unknown }).strokes)
  ) {
    return {
      strokes: (raw as { strokes: unknown[] }).strokes
        .map(normalizeStroke)
        .filter((stroke): stroke is MaterializedStroke => stroke !== null),
    }
  }
  return { strokes: [] }
}

function shouldSendCanvasUpdatePush(type: string): boolean {
  return type === "FINISH_STROKE" || type === "DELETE_STROKE" || type === "CLEAR_CANVAS"
}

function normalizeStroke(raw: unknown): MaterializedStroke | null {
  if (typeof raw !== "object" || raw === null || !("id" in raw)) return null
  const candidate = raw as {
    id?: unknown
    colorArgb?: unknown
    width?: unknown
    createdAt?: unknown
    points?: unknown
    finished?: unknown
  }
  if (typeof candidate.id !== "string" || !Array.isArray(candidate.points)) return null
  return {
    id: candidate.id,
    colorArgb: Number(candidate.colorArgb ?? 0xff111111),
    width: Number(candidate.width ?? 8),
    createdAt: Number(candidate.createdAt ?? Date.now()),
    points: candidate.points
      .map((point) => {
        if (typeof point !== "object" || point === null) return null
        const item = point as { x?: unknown; y?: unknown }
        return { x: Number(item.x ?? 0), y: Number(item.y ?? 0) }
      })
      .filter((point): point is { x: number; y: number } => point !== null),
    finished: Boolean(candidate.finished),
  }
}
