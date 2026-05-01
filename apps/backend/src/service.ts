import { randomBytes, randomInt, randomUUID } from "node:crypto"
import sharp from "sharp"
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
import type { WallpaperStorage } from "./wallpapers.js"

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

interface MaterializedTextElement {
  id: string
  text: string
  createdAt: number
  center: { x: number; y: number }
  rotationRad: number
  scale: number
  boxWidth: number
  colorArgb: number
  backgroundPillEnabled: boolean
  font: string
  alignment: string
}

interface UploadedProfilePhoto {
  filename?: string
  contentType?: string
  data: Buffer
}

const PARTNER_PROFILE_UPDATE_COOLDOWN_MS = 72 * 60 * 60 * 1_000
const PARTNER_DETAILS_REQUIRED_MESSAGE = "Partner details are required before creating an invite"
const PROFILE_PHOTO_PREFIX = "profile-photos"
const PROFILE_PHOTO_MAX_BYTES = 10 * 1024 * 1024

export class MulberryService {
  constructor(
    private readonly db: Database,
    private readonly googleVerifier: GoogleTokenVerifier,
    private readonly pushDispatchService?: PushDispatchService,
    private readonly profilePhotoStorage?: WallpaperStorage | null,
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
        onboarding_completed_at,
        updated_at
      ) VALUES ($1, $2, $3, $4, NOW(), NOW())
      ON CONFLICT (user_id) DO UPDATE SET
        display_name = EXCLUDED.display_name,
        partner_display_name = EXCLUDED.partner_display_name,
        anniversary_date = EXCLUDED.anniversary_date,
        onboarding_completed_at = COALESCE(user_profiles.onboarding_completed_at, NOW()),
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

  async updateDisplayName(accessToken: string, displayNameInput: string): Promise<BootstrapResponse> {
    const context = await this.requireSessionContext(accessToken)
    const displayName = displayNameInput.trim()
    if (!displayName) {
      throw new HttpError(400, "Profile name is required")
    }

    const existing = await this.getProfile(context.user.id)
    const pairSession = await this.getPairSession(context.user.id)
    await this.db.transaction(async (tx) => {
      await tx.query(
        `
        INSERT INTO user_profiles (
          user_id,
          display_name,
          onboarding_completed_at,
          updated_at
        ) VALUES ($1, $2, $3, NOW())
        ON CONFLICT (user_id) DO UPDATE SET
          display_name = EXCLUDED.display_name,
          onboarding_completed_at = COALESCE(user_profiles.onboarding_completed_at, EXCLUDED.onboarding_completed_at),
          updated_at = NOW()
        `,
        [
          context.user.id,
          displayName,
          existing?.onboarding_completed_at ?? null,
        ],
      )

      if (pairSession) {
        const peerUserId = pairSession.user_one_id === context.user.id
          ? pairSession.user_two_id
          : pairSession.user_one_id
        await tx.query(
          `
          INSERT INTO user_profiles (
            user_id,
            partner_display_name,
            updated_at
          ) VALUES ($1, $2, NOW())
          ON CONFLICT (user_id) DO UPDATE SET
            partner_display_name = EXCLUDED.partner_display_name,
            updated_at = NOW()
          `,
          [peerUserId, displayName],
        )
      }
    })

    return this.buildBootstrap(context.user.id)
  }

  async updateProfilePhoto(
    accessToken: string,
    upload: UploadedProfilePhoto,
  ): Promise<BootstrapResponse> {
    const context = await this.requireSessionContext(accessToken)
    const processed = await this.storeProfilePhoto(context.user.id, upload)
    const existing = await this.getProfile(context.user.id)
    const pairSession = await this.getPairSession(context.user.id)
    const oldPaths = [existing?.profile_photo_path].filter((path): path is string => Boolean(path))

    await this.db.transaction(async (tx) => {
      await tx.query(
        `
        INSERT INTO user_profiles (
          user_id,
          display_name,
          profile_photo_path,
          updated_at
        ) VALUES ($1, $2, $3, NOW())
        ON CONFLICT (user_id) DO UPDATE SET
          profile_photo_path = EXCLUDED.profile_photo_path,
          updated_at = NOW()
        `,
        [context.user.id, existing?.display_name ?? context.user.email, processed.path],
      )

      if (pairSession) {
        const peerUserId = pairSession.user_one_id === context.user.id
          ? pairSession.user_two_id
          : pairSession.user_one_id
        await tx.query(
          `
          INSERT INTO user_profiles (
            user_id,
            partner_profile_photo_path,
            updated_at
          ) VALUES ($1, $2, NOW())
          ON CONFLICT (user_id) DO UPDATE SET
            partner_profile_photo_path = EXCLUDED.partner_profile_photo_path,
            updated_at = NOW()
          `,
          [peerUserId, processed.path],
        )
      }
    })

    await this.removeStoredProfilePhotos(oldPaths)
    return this.buildBootstrap(context.user.id)
  }

  async updatePartnerProfile(
    accessToken: string,
    profile: {
      partnerDisplayName: string
      anniversaryDate: string
    },
  ): Promise<BootstrapResponse> {
    const context = await this.requireSessionContext(accessToken)
    const partnerDisplayName = profile.partnerDisplayName.trim()
    const anniversaryDate = profile.anniversaryDate.trim()
    if (!partnerDisplayName || !anniversaryDate) {
      throw new HttpError(400, "Partner name and relationship anniversary are required")
    }

    const existing = await this.getProfile(context.user.id)
    const pairSession = await this.getPairSession(context.user.id)
    const enforceCooldown = pairSession !== null
    const nextUpdateAt = enforceCooldown ? this.partnerProfileNextUpdateAt(existing) : null
    if (nextUpdateAt) {
      throw new HttpError(409, `Partner details can be updated again at ${nextUpdateAt}`)
    }

    const updatedAt = enforceCooldown ? new Date().toISOString() : null
    await this.db.transaction(async (tx) => {
      await tx.query(
        `
        INSERT INTO user_profiles (
          user_id,
          display_name,
          partner_display_name,
          anniversary_date,
          partner_profile_updated_at,
          updated_at
        ) VALUES ($1, $2, $3, $4, $5, NOW())
        ON CONFLICT (user_id) DO UPDATE SET
          partner_display_name = EXCLUDED.partner_display_name,
          anniversary_date = EXCLUDED.anniversary_date,
          partner_profile_updated_at = EXCLUDED.partner_profile_updated_at,
          updated_at = NOW()
        `,
        [
          context.user.id,
          existing?.display_name ?? context.user.email,
          partnerDisplayName,
          anniversaryDate,
          updatedAt,
        ],
      )

      if (pairSession) {
        const peerUserId = pairSession.user_one_id === context.user.id
          ? pairSession.user_two_id
          : pairSession.user_one_id
        await tx.query(
          `
          INSERT INTO user_profiles (
            user_id,
            display_name,
            anniversary_date,
            updated_at
          ) VALUES ($1, $2, $3, NOW())
          ON CONFLICT (user_id) DO UPDATE SET
            display_name = EXCLUDED.display_name,
            anniversary_date = EXCLUDED.anniversary_date,
            updated_at = NOW()
          `,
          [peerUserId, partnerDisplayName, anniversaryDate],
        )
      }
    })

    return this.buildBootstrap(context.user.id)
  }

  async updatePartnerProfilePhoto(
    accessToken: string,
    upload: UploadedProfilePhoto,
  ): Promise<BootstrapResponse> {
    const context = await this.requireSessionContext(accessToken)
    const existing = await this.getProfile(context.user.id)
    const pairSession = await this.getPairSession(context.user.id)
    const enforceCooldown = pairSession !== null
    const nextUpdateAt = enforceCooldown ? this.partnerProfileNextUpdateAt(existing) : null
    if (nextUpdateAt) {
      throw new HttpError(409, `Partner details can be updated again at ${nextUpdateAt}`)
    }

    const processed = await this.storeProfilePhoto(context.user.id, upload)
    const updatedAt = enforceCooldown ? new Date().toISOString() : null
    const oldPaths: string[] = []
    await this.db.transaction(async (tx) => {
      if (pairSession) {
        const peerUserId = pairSession.user_one_id === context.user.id
          ? pairSession.user_two_id
          : pairSession.user_one_id
        const peerProfile = await this.getProfileFrom(tx, peerUserId)
        if (peerProfile?.profile_photo_path) {
          oldPaths.push(peerProfile.profile_photo_path)
        }
        await tx.query(
          `
          INSERT INTO user_profiles (
            user_id,
            profile_photo_path,
            updated_at
          ) VALUES ($1, $2, NOW())
          ON CONFLICT (user_id) DO UPDATE SET
            profile_photo_path = EXCLUDED.profile_photo_path,
            updated_at = NOW()
          `,
          [peerUserId, processed.path],
        )
        await tx.query(
          `
          UPDATE user_profiles
          SET
            partner_profile_updated_at = $2,
            partner_profile_photo_path = $3,
            updated_at = NOW()
          WHERE user_id = $1
          `,
          [context.user.id, updatedAt, processed.path],
        )
      } else {
        if (existing?.partner_profile_photo_path) {
          oldPaths.push(existing.partner_profile_photo_path)
        }
        await tx.query(
          `
          INSERT INTO user_profiles (
            user_id,
            display_name,
            partner_profile_photo_path,
            partner_profile_updated_at,
            updated_at
          ) VALUES ($1, $2, $3, $4, NOW())
          ON CONFLICT (user_id) DO UPDATE SET
            partner_profile_photo_path = EXCLUDED.partner_profile_photo_path,
            partner_profile_updated_at = EXCLUDED.partner_profile_updated_at,
            updated_at = NOW()
          `,
          [
            context.user.id,
            existing?.display_name ?? context.user.email,
            processed.path,
            updatedAt,
          ],
        )
      }
    })

    await this.removeStoredProfilePhotos(oldPaths)
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
    const inviterProfile = await this.getProfile(context.user.id)
    this.requireActivePartnerDetails(inviterProfile)

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
    this.requireActivePartnerDetails(inviterProfile)
    const recipientProfileBeforeRedeem = await this.getProfile(context.user.id)
    const recipientOnboardingCompleted = Boolean(recipientProfileBeforeRedeem?.onboarding_completed_at)
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
    this.pushDispatchService?.initializePairDrawReminders(pairId)
    await this.db.query(
      `
      UPDATE invites
      SET status = 'ACCEPTED', consumed_at = NOW()
      WHERE id = $1
      `,
      [invite.id],
    )

    const recipientProfile = await this.getProfile(context.user.id)
    this.pushDispatchService?.enqueuePairingConfirmed(
      pairId,
      context.user.id,
      recipientProfile?.display_name ?? "Your partner",
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

  async disconnectPairing(accessToken: string): Promise<BootstrapResponse> {
    const context = await this.requireSessionContext(accessToken)
    const pairSession = await this.getPairSession(context.user.id)
    if (!pairSession) {
      throw new HttpError(400, "User is not paired")
    }

    const recipientUserId = pairSession.user_one_id === context.user.id
      ? pairSession.user_two_id
      : pairSession.user_one_id
    const profile = await this.getProfile(context.user.id)
    const actorDisplayName = profile?.display_name ?? "Your partner"

    await this.db.transaction(async (tx) => {
      await tx.query(`DELETE FROM pair_sessions WHERE id = $1`, [pairSession.id])
      await this.clearPartnerMetadataForUsers(tx, [pairSession.user_one_id, pairSession.user_two_id])
    })

    this.pushDispatchService?.enqueuePairingDisconnected(
      pairSession.id,
      recipientUserId,
      context.user.id,
      actorDisplayName,
    )
    return this.buildBootstrap(context.user.id)
  }

  async sendDebugPairingConfirmed(accessToken: string): Promise<{ ok: true }> {
    const context = await this.requireSessionContext(accessToken)
    const pairSession = await this.getPairSession(context.user.id)
    if (!pairSession) {
      throw new HttpError(400, "User is not paired")
    }

    const profile = await this.getProfile(context.user.id)
    this.pushDispatchService?.enqueuePairingConfirmed(
      pairSession.id,
      context.user.id,
      profile?.display_name ?? "Your partner",
    )
    return { ok: true }
  }

  async sendDebugPairingDisconnected(accessToken: string): Promise<{ ok: true }> {
    const context = await this.requireSessionContext(accessToken)
    const pairSession = await this.getPairSession(context.user.id)
    if (!pairSession) {
      throw new HttpError(400, "User is not paired")
    }

    const recipientUserId = pairSession.user_one_id === context.user.id
      ? pairSession.user_two_id
      : pairSession.user_one_id
    const profile = await this.getProfile(context.user.id)
    this.pushDispatchService?.enqueuePairingDisconnected(
      pairSession.id,
      recipientUserId,
      context.user.id,
      profile?.display_name ?? "Your partner",
    )
    return { ok: true }
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
    const accepted = await this.acceptCanvasOperationBatch(context, [operation])
    return accepted[0]
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

    return this.acceptCanvasOperationBatch(context, batch.operations)
  }

  async acceptCanvasOperationBatchForAuthenticatedPair(
    accessToken: string,
    batch: ClientCanvasOperationBatch,
  ): Promise<CanvasOperationEnvelope[]> {
    const context = await this.requireDefaultCanvasSessionContext(accessToken)
    if (!batch.batchId.trim()) {
      throw new HttpError(400, "batchId is required")
    }
    if (!Array.isArray(batch.operations) || batch.operations.length === 0) {
      throw new HttpError(400, "CLIENT_OP_BATCH requires operations")
    }
    if (batch.operations.length > 128) {
      throw new HttpError(413, "CLIENT_OP_BATCH is too large")
    }

    return this.acceptCanvasOperationBatch(context, batch.operations)
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
    const snapshotRevision = Number(row.revision)
    return {
      pairSessionId: context.pairSession.id,
      snapshotRevision,
      latestRevision: Number(row.latest_revision),
      revision: snapshotRevision,
      snapshot: row.snapshot_json,
      updatedAt: row.updated_at ? new Date(row.updated_at).toISOString() : null,
    }
  }

  async acceptCanvasOperation(
    context: CanvasSessionContext,
    operation: ClientCanvasOperation,
  ): Promise<CanvasOperationEnvelope> {
    const accepted = await this.acceptCanvasOperationBatch(context, [operation])
    return accepted[0]
  }

  private async acceptCanvasOperationBatch(
    context: CanvasSessionContext,
    operations: ClientCanvasOperation[],
  ): Promise<CanvasOperationEnvelope[]> {
    operations.forEach((operation) => {
      if (!operation.clientOperationId.trim()) {
        throw new HttpError(400, "clientOperationId is required")
      }
      if (!operation.type) {
        throw new HttpError(400, "operation type is required")
      }
    })

    const { accepted, latestRevision, snapshotRevision, shouldPushCanvasUpdate, shouldEnqueueCanvasNudge, shouldRecordUserDrew } = await this.db.transaction(
      async (tx) => {
        const snapshotRow = await this.getOrCreateCanvasSnapshot(context.pairSession.id, tx, true)
        let latestRevision = Number(snapshotRow.latest_revision)
        let snapshotRevision = Number(snapshotRow.revision)
        const snapshot = normalizeCanvasSnapshot(snapshotRow.snapshot_json)
        const acceptedRecords: CanvasOperationRecord[] = []
        let snapshotChanged = false
        let shouldPushCanvasUpdate = false
        let shouldEnqueueCanvasNudge = false
        let shouldRecordUserDrew = false

        for (const operation of operations) {
          const duplicate = await tx.query<CanvasOperationRecord>(
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
            acceptedRecords.push(duplicate.rows[0])
            latestRevision = Math.max(latestRevision, Number(duplicate.rows[0].server_revision))
            continue
          }

          latestRevision += 1
          const id = randomUUID()
          const clientCreatedAt = validDateOrNow(operation.clientCreatedAt)
          const rows = await tx.query<CanvasOperationRecord>(
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
            RETURNING id, pair_session_id, server_revision, client_operation_id, actor_user_id,
              type, stroke_id, payload_json, client_created_at, created_at
            `,
            [
              id,
              context.pairSession.id,
              latestRevision,
              operation.clientOperationId,
              context.user.id,
              operation.type,
              operation.strokeId ?? null,
              JSON.stringify(operation.payload ?? {}),
              clientCreatedAt.toISOString(),
            ],
          )
          const acceptedRecord = rows.rows[0]
          acceptedRecords.push(acceptedRecord)
          if (acceptedRecord.type === "FINISH_STROKE") {
            await this.recordPairActivityDay(tx, context.pairSession.id, operation.clientLocalDate)
            shouldEnqueueCanvasNudge = true
            shouldRecordUserDrew = true
          }
          const materialized = await this.materializeDurableOperation(
            tx,
            context.pairSession.id,
            acceptedRecord,
            snapshot,
          )
          if (materialized) {
            snapshotRevision = Number(acceptedRecord.server_revision)
            snapshotChanged = true
            shouldPushCanvasUpdate = true
          }
        }

        await tx.query(
          `
          UPDATE canvas_snapshots
          SET latest_revision = $2,
            revision = $3,
            snapshot_json = CASE WHEN $4 THEN $5::jsonb ELSE snapshot_json END,
            updated_at = CASE WHEN $4 THEN NOW() ELSE updated_at END
          WHERE pair_session_id = $1
          `,
          [
            context.pairSession.id,
            latestRevision,
            snapshotRevision,
            snapshotChanged,
            JSON.stringify(snapshot),
          ],
        )

        return {
          accepted: acceptedRecords.map((row) => this.canvasOperationToEnvelope(row)),
          latestRevision,
          snapshotRevision,
          shouldPushCanvasUpdate,
          shouldEnqueueCanvasNudge,
          shouldRecordUserDrew,
        }
      },
    )

    if (shouldPushCanvasUpdate) {
      this.pushDispatchService?.enqueueCanvasUpdated(
        context.pairSession.id,
        context.user.id,
        latestRevision,
        snapshotRevision,
      )
    }

    if (shouldEnqueueCanvasNudge) {
      this.pushDispatchService?.enqueueCanvasNudge(
        context.pairSession.id,
        context.user.id,
        latestRevision,
      )
    }

    if (shouldRecordUserDrew) {
      this.pushDispatchService?.recordUserDrew(context.pairSession.id, context.user.id)
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
      `SELECT id, google_subject, email, google_picture_url FROM users WHERE google_subject = $1`,
      [identity.subject],
    )
    if (existing.rows[0]) {
      await this.db.query(`UPDATE users SET email = $2, google_picture_url = $3 WHERE id = $1`, [
        existing.rows[0].id,
        identity.email,
        identity.pictureUrl,
      ])
      await this.ensureProfileRow(existing.rows[0].id, identity.name)
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

  private requireActivePartnerDetails(profile: ProfileRecord | null): void {
    if (!profile?.partner_display_name?.trim() || !profile.anniversary_date?.trim()) {
      throw new HttpError(400, PARTNER_DETAILS_REQUIRED_MESSAGE)
    }
  }

  private partnerProfileNextUpdateAt(profile: ProfileRecord | null): string | null {
    if (!profile?.partner_display_name?.trim() || !profile.anniversary_date?.trim()) {
      return null
    }
    if (!profile.partner_profile_updated_at) {
      return null
    }
    const updatedAt = new Date(profile.partner_profile_updated_at).getTime()
    if (!Number.isFinite(updatedAt)) {
      return null
    }
    const nextUpdateAt = updatedAt + PARTNER_PROFILE_UPDATE_COOLDOWN_MS
    if (Date.now() >= nextUpdateAt) {
      return null
    }
    return new Date(nextUpdateAt).toISOString()
  }

  private async clearPartnerMetadataForUsers(
    db: Pick<Database, "query">,
    userIds: string[],
  ): Promise<void> {
    if (userIds.length === 0) return
    const placeholders = userIds.map((_, index) => `$${index + 1}`).join(", ")
    await db.query(
      `
      UPDATE user_profiles
      SET
        partner_display_name = NULL,
        anniversary_date = NULL,
        partner_profile_photo_path = NULL,
        partner_profile_updated_at = NULL,
        updated_at = NOW()
      WHERE user_id IN (${placeholders})
      `,
      userIds,
    )
  }

  private async hydrateRecipientProfileFromInvite(
    recipientUserId: string,
    recipientProfile: ProfileRecord | null,
    inviterProfile: ProfileRecord | null,
    recipientOnboardingCompleted: boolean,
  ): Promise<void> {
    const recipientDisplayName = inviterProfile?.partner_display_name ?? recipientProfile?.display_name
    const partnerDisplayName = inviterProfile?.display_name ?? recipientProfile?.partner_display_name
    const anniversaryDate = inviterProfile?.anniversary_date ?? recipientProfile?.anniversary_date
    const profilePhotoPath = inviterProfile?.partner_profile_photo_path ?? recipientProfile?.profile_photo_path

    await this.db.query(
      `
      INSERT INTO user_profiles (
        user_id,
        display_name,
        partner_display_name,
        anniversary_date,
        profile_photo_path,
        onboarding_completed_at,
        updated_at
      ) VALUES ($1, $2, $3, $4, $5, CASE WHEN $6 THEN COALESCE($7::timestamptz, NOW()) ELSE NOW() END, NOW())
      ON CONFLICT (user_id) DO UPDATE SET
        display_name = EXCLUDED.display_name,
        partner_display_name = EXCLUDED.partner_display_name,
        anniversary_date = EXCLUDED.anniversary_date,
        profile_photo_path = EXCLUDED.profile_photo_path,
        onboarding_completed_at = EXCLUDED.onboarding_completed_at,
        updated_at = NOW()
      `,
      [
        recipientUserId,
        recipientDisplayName ?? null,
        partnerDisplayName ?? null,
        anniversaryDate ?? null,
        profilePhotoPath ?? null,
        recipientOnboardingCompleted,
        recipientProfile?.onboarding_completed_at ?? null,
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
        profile_photo_path = NULL,
        partner_profile_photo_path = NULL,
        onboarding_completed_at = NULL,
        partner_profile_updated_at = NULL,
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
      `SELECT id, google_subject, email, google_picture_url FROM users WHERE id = $1`,
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
      `SELECT id, google_subject, email, google_picture_url FROM users WHERE id = $1`,
      [userId],
    )
    return rows.rows[0] ?? null
  }

  private async buildBootstrap(userId: string): Promise<BootstrapResponse> {
    const user = await this.getUserById(userId)
    let profile = await this.getProfile(userId)
    const pairSession = await this.getPairSession(userId)
    const partnerUser = pairSession
      ? await this.getUserById(
          pairSession.user_one_id === userId ? pairSession.user_two_id : pairSession.user_one_id,
        )
      : null
    const pendingInvite = await this.getPendingInvite(userId)
    let onboardingCompleted = Boolean(profile?.onboarding_completed_at)
    if (pendingInvite && !onboardingCompleted) {
      await this.hydrateRecipientProfileFromInvite(
        userId,
        profile,
        await this.getProfile(pendingInvite.inviter_user_id),
        false,
      )
      profile = await this.getProfile(userId)
      onboardingCompleted = Boolean(profile?.onboarding_completed_at)
    }

    const inviterPhotoUrl = pendingInvite
      ? this.profilePhotoUrl((await this.getProfile(pendingInvite.inviter_user_id))?.profile_photo_path) ??
        (await this.getUserById(pendingInvite.inviter_user_id))?.google_picture_url ??
        null
      : null

    return {
      authStatus: "SIGNED_IN",
      onboardingCompleted,
      hasWallpaperConfigured: false,
      userId,
      userEmail: user?.email ?? null,
      userPhotoUrl: this.profilePhotoUrl(profile?.profile_photo_path) ?? user?.google_picture_url ?? null,
      userDisplayName: profile?.display_name ?? null,
      partnerPhotoUrl: pairSession
        ? this.profilePhotoUrl((partnerUser ? await this.getProfile(partnerUser.id) : null)?.profile_photo_path) ??
          partnerUser?.google_picture_url ??
          null
        : this.profilePhotoUrl(profile?.partner_profile_photo_path) ?? inviterPhotoUrl,
      partnerDisplayName: profile?.partner_display_name ?? null,
      anniversaryDate: profile?.anniversary_date ?? null,
      partnerProfileNextUpdateAt: pairSession ? this.partnerProfileNextUpdateAt(profile) : null,
      pairedAt: pairSession ? new Date(pairSession.created_at).toISOString() : null,
      currentStreakDays: pairSession ? await this.currentStreakDays(pairSession.id) : 0,
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
    return this.getProfileFrom(this.db, userId)
  }

  private async getProfileFrom(
    db: Pick<Database, "query">,
    userId: string,
  ): Promise<ProfileRecord | null> {
    const rows = await db.query<{
      user_id: string
      display_name: string | null
      partner_display_name: string | null
      anniversary_date: string | Date | null
      onboarding_completed_at: string | Date | null
      partner_profile_updated_at: string | Date | null
      profile_photo_path: string | null
      partner_profile_photo_path: string | null
    }>(
      `
      SELECT user_id, display_name, partner_display_name, anniversary_date,
        onboarding_completed_at, partner_profile_updated_at,
        profile_photo_path, partner_profile_photo_path
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
      onboarding_completed_at: normalizeTimestampString(row.onboarding_completed_at),
      partner_profile_updated_at: normalizeTimestampString(row.partner_profile_updated_at),
      profile_photo_path: row.profile_photo_path,
      partner_profile_photo_path: row.partner_profile_photo_path,
    }
  }

  private async getPairSession(userId: string): Promise<PairSessionRecord | null> {
    const rows = await this.db.query<PairSessionRecord>(
      `
      SELECT id, user_one_id, user_two_id, created_at
      FROM pair_sessions
      WHERE user_one_id = $1 OR user_two_id = $1
      LIMIT 1
      `,
      [userId],
    )
    return rows.rows[0] ?? null
  }

  private async storeProfilePhoto(
    userId: string,
    upload: UploadedProfilePhoto,
  ): Promise<{ path: string }> {
    if (!this.profilePhotoStorage) {
      throw new HttpError(503, "Profile photo storage is not configured")
    }
    if (!upload.data || upload.data.length === 0) {
      throw new HttpError(400, "image is required")
    }
    if (upload.data.length > PROFILE_PHOTO_MAX_BYTES) {
      throw new HttpError(400, "Profile photo must be 10 MB or smaller")
    }
    if (!isSupportedProfilePhotoType(upload.contentType)) {
      throw new HttpError(400, "Profile photo must be JPEG, PNG, or WebP")
    }

    let processed: Buffer
    try {
      processed = await sharp(upload.data, { failOn: "none" })
        .rotate()
        .resize(512, 512, { fit: "cover" })
        .webp({ quality: 84 })
        .toBuffer()
    } catch {
      throw new HttpError(400, "Profile photo could not be processed")
    }

    const path = `${PROFILE_PHOTO_PREFIX}/${userId}/${randomUUID()}.webp`
    await this.profilePhotoStorage.upload(path, processed, "image/webp")
    return { path }
  }

  private async removeStoredProfilePhotos(paths: string[]): Promise<void> {
    if (!this.profilePhotoStorage || paths.length === 0) return
    await this.profilePhotoStorage.remove([...new Set(paths)])
  }

  private profilePhotoUrl(path: string | null | undefined): string | null {
    if (!path || !this.profilePhotoStorage) return null
    return this.profilePhotoStorage.publicUrl(path)
  }

  private async recordPairActivityDay(
    db: Pick<Database, "query">,
    pairSessionId: string,
    clientLocalDate: string | null | undefined,
  ): Promise<void> {
    const activityDay = isLocalDateString(clientLocalDate)
      ? clientLocalDate
      : new Date().toISOString().slice(0, 10)
    await db.query(
      `
      INSERT INTO pair_activity_days (pair_session_id, activity_day)
      VALUES ($1, $2::date)
      ON CONFLICT (pair_session_id, activity_day) DO NOTHING
      `,
      [pairSessionId, activityDay],
    )
  }

  private async currentStreakDays(pairSessionId: string): Promise<number> {
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

  private async getLatestCanvasRevision(pairSessionId: string): Promise<number> {
    const rows = await this.db.query<{ latest_revision: string | number }>(
      `
      SELECT latest_revision
      FROM canvas_snapshots
      WHERE pair_session_id = $1
      `,
      [pairSessionId],
    )
    if (rows.rows[0]) {
      return Number(rows.rows[0].latest_revision)
    }
    const fallback = await this.db.query<{ revision: string | number }>(
      `
      SELECT COALESCE(MAX(server_revision), 0) AS revision
      FROM canvas_operations
      WHERE pair_session_id = $1
      `,
      [pairSessionId],
    )
    return Number(fallback.rows[0]?.revision ?? 0)
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
    latest_revision: string | number
    snapshot_json: unknown
    updated_at: Date | string
  }>
  private async getOrCreateCanvasSnapshot(
    pairSessionId: string,
    db: Pick<Database, "query">,
    lockForUpdate: true,
  ): Promise<{
    revision: string | number
    latest_revision: string | number
    snapshot_json: unknown
    updated_at: Date | string
  }>
  private async getOrCreateCanvasSnapshot(
    pairSessionId: string,
    db: Pick<Database, "query"> = this.db,
    lockForUpdate = false,
  ): Promise<{
    revision: string | number
    latest_revision: string | number
    snapshot_json: unknown
    updated_at: Date | string
  }> {
    await db.query(
      `
      INSERT INTO canvas_snapshots (pair_session_id)
      VALUES ($1)
      ON CONFLICT (pair_session_id) DO NOTHING
      `,
      [pairSessionId],
    )
    const rows = await db.query<{
      revision: string | number
      latest_revision: string | number
      snapshot_json: unknown
      updated_at: Date | string
    }>(
      `
      SELECT revision, latest_revision, snapshot_json, updated_at
      FROM canvas_snapshots
      WHERE pair_session_id = $1
      ${lockForUpdate ? "FOR UPDATE" : ""}
      `,
      [pairSessionId],
    )
    return rows.rows[0]
  }

  private async materializeDurableOperation(
    db: Pick<Database, "query">,
    pairSessionId: string,
    operation: CanvasOperationRecord,
    snapshot: { strokes: MaterializedStroke[]; textElements: MaterializedTextElement[] },
  ): Promise<boolean> {
    switch (operation.type) {
      case "FINISH_STROKE": {
        const strokeId = operation.stroke_id
        if (!strokeId) return false
        const stroke = await this.reconstructFinishedStroke(db, pairSessionId, strokeId, Number(operation.server_revision))
        if (!stroke) return false
        snapshot.strokes = snapshot.strokes.filter((stroke) => stroke.id !== strokeId)
        snapshot.strokes.push(stroke)
        return true
      }
      case "DELETE_STROKE":
        snapshot.strokes = snapshot.strokes.filter((stroke) => stroke.id !== operation.stroke_id)
        return true
      case "CLEAR_CANVAS": {
        snapshot.strokes = []
        snapshot.textElements = []
        return true
      }
      case "ADD_TEXT_ELEMENT":
      case "UPDATE_TEXT_ELEMENT": {
        const payload = normalizeTextElement(operation.payload_json)
        if (!payload) return false
        snapshot.textElements = snapshot.textElements.filter((element) => element.id !== payload.id)
        snapshot.textElements.push(payload)
        return true
      }
      case "DELETE_TEXT_ELEMENT": {
        if (!operation.stroke_id) return false
        snapshot.textElements = snapshot.textElements.filter((element) => element.id !== operation.stroke_id)
        return true
      }
      default:
        return false
    }
  }

  private async reconstructFinishedStroke(
    db: Pick<Database, "query">,
    pairSessionId: string,
    strokeId: string,
    throughRevision: number,
  ): Promise<MaterializedStroke | null> {
    const rows = await db.query<CanvasOperationRecord>(
      `
      SELECT id, pair_session_id, server_revision, client_operation_id, actor_user_id,
        type, stroke_id, payload_json, client_created_at, created_at
      FROM canvas_operations
      WHERE pair_session_id = $1
        AND stroke_id = $2
        AND server_revision <= $3
        AND type IN ('ADD_STROKE', 'APPEND_POINTS')
      ORDER BY server_revision ASC
      `,
      [pairSessionId, strokeId, throughRevision],
    )
    let stroke: MaterializedStroke | null = null
    for (const operation of rows.rows) {
      if (operation.type === "ADD_STROKE") {
        const payload = operation.payload_json as Partial<MaterializedStroke> & {
          firstPoint?: { x: number; y: number }
        }
        const firstPoint = normalizeCanvasPoint(payload.firstPoint)
        if (!firstPoint) continue
        stroke = {
          id: strokeId,
          colorArgb: Number(payload.colorArgb ?? 0xff111111),
          width: Number(payload.width ?? 8),
          createdAt: Number(payload.createdAt ?? Date.now()),
          points: [firstPoint],
          finished: true,
        }
      } else if (operation.type === "APPEND_POINTS" && stroke) {
        const payload = operation.payload_json as { points?: Array<{ x: number; y: number }> }
        if (Array.isArray(payload.points)) {
          stroke.points.push(...payload.points.map(normalizeCanvasPoint).filter((point): point is { x: number; y: number } => point !== null))
        }
      }
    }
    return stroke
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

function isSupportedProfilePhotoType(contentType: string | undefined): boolean {
  return contentType === "image/jpeg" ||
    contentType === "image/png" ||
    contentType === "image/webp"
}

function isLocalDateString(value: string | null | undefined): value is string {
  return typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value)
}

function dateOnly(date: Date): string {
  return date.toISOString().slice(0, 10)
}

function addDays(day: string, delta: number): string {
  const date = new Date(`${day}T00:00:00.000Z`)
  date.setUTCDate(date.getUTCDate() + delta)
  return dateOnly(date)
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

function normalizeTimestampString(raw: string | Date | null): string | null {
  if (!raw) return null
  if (raw instanceof Date) return raw.toISOString()
  const parsed = new Date(raw)
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString()
}

function normalizeCanvasSnapshot(raw: unknown): {
  strokes: MaterializedStroke[]
  textElements: MaterializedTextElement[]
} {
  if (
    typeof raw === "object" &&
    raw !== null &&
    "strokes" in raw &&
    Array.isArray((raw as { strokes: unknown }).strokes)
  ) {
    const candidate = raw as { strokes: unknown[]; textElements?: unknown }
    return {
      strokes: candidate.strokes
        .map(normalizeStroke)
        .filter((stroke): stroke is MaterializedStroke => stroke !== null),
      textElements: Array.isArray(candidate.textElements)
        ? candidate.textElements
          .map(normalizeTextElement)
          .filter((element): element is MaterializedTextElement => element !== null)
        : [],
    }
  }
  return { strokes: [], textElements: [] }
}

function normalizeCanvasPoint(raw: unknown): { x: number; y: number } | null {
  if (typeof raw !== "object" || raw === null) return null
  const item = raw as { x?: unknown; y?: unknown }
  return { x: Number(item.x ?? 0), y: Number(item.y ?? 0) }
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

function normalizeTextElement(raw: unknown): MaterializedTextElement | null {
  if (typeof raw !== "object" || raw === null || !("id" in raw)) return null
  const candidate = raw as {
    id?: unknown
    text?: unknown
    createdAt?: unknown
    center?: unknown
    rotationRad?: unknown
    scale?: unknown
    boxWidth?: unknown
    colorArgb?: unknown
    backgroundPillEnabled?: unknown
    font?: unknown
    alignment?: unknown
  }
  if (typeof candidate.id !== "string") return null
  const center = normalizeCanvasPoint(candidate.center)
  if (!center) return null
  return {
    id: candidate.id,
    text: typeof candidate.text === "string" ? candidate.text : "",
    createdAt: Number(candidate.createdAt ?? Date.now()),
    center,
    rotationRad: Number(candidate.rotationRad ?? 0),
    scale: Number(candidate.scale ?? 1),
    boxWidth: Number(candidate.boxWidth ?? 0.7),
    colorArgb: Number(candidate.colorArgb ?? 0xff111111),
    backgroundPillEnabled: Boolean(candidate.backgroundPillEnabled),
    font: typeof candidate.font === "string" ? candidate.font : "POPPINS",
    alignment: typeof candidate.alignment === "string" ? candidate.alignment : "CENTER",
  }
}
