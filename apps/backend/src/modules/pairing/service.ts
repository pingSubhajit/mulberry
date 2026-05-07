import { randomInt, randomUUID } from "node:crypto"
import type { Database } from "../../infra/db/database.js"
import { HttpError } from "../../infra/http/HttpError.js"
import type { BootstrapResponse } from "../../contracts/bootstrap.js"
import type { AcceptInviteResponse, CreateInviteResponse, RedeemInviteResponse } from "../../contracts/pairing.js"
import type { InviteRecord, ProfileRecord } from "../../contracts/dbRecords.js"
import { requireSessionContext } from "../_shared/session.js"
import { getProfileFrom } from "../_shared/profiles.js"
import { getPairSession } from "../_shared/pairs.js"
import { isUniqueViolation } from "../_shared/dbErrors.js"
import type { PushDispatchService } from "../../infra/push/dispatchService.js"
import type { BootstrapService } from "../bootstrap/service.js"

const PARTNER_DETAILS_REQUIRED_MESSAGE = "Partner details are required before creating an invite"

export class PairingService {
  constructor(
    private readonly db: Database,
    private readonly bootstrapService: BootstrapService,
    private readonly pushDispatchService?: PushDispatchService,
  ) {}

  async createInvite(accessToken: string): Promise<CreateInviteResponse> {
    const context = await requireSessionContext(this.db, accessToken)
    const bootstrap = await this.bootstrapService.buildBootstrap(context.user.id)
    if (!bootstrap.onboardingCompleted) {
      throw new HttpError(400, "Complete onboarding before creating an invite")
    }
    if (bootstrap.pairingStatus === "PAIRED") {
      throw new HttpError(400, "Already paired")
    }
    const inviterProfile = await getProfileFrom(this.db, context.user.id)
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
    const context = await requireSessionContext(this.db, accessToken)
    const bootstrap = await this.bootstrapService.buildBootstrap(context.user.id)
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

    const inviterProfile = await getProfileFrom(this.db, invite.inviter_user_id)
    this.requireActivePartnerDetails(inviterProfile)
    const recipientProfileBeforeRedeem = await getProfileFrom(this.db, context.user.id)
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
    const context = await requireSessionContext(this.db, accessToken)
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

    const recipientProfile = await getProfileFrom(this.db, context.user.id)
    this.pushDispatchService?.enqueuePairingConfirmed(
      pairId,
      context.user.id,
      recipientProfile?.display_name ?? "Your partner",
    )

    return {
      pairSessionId: pairId,
      bootstrapState: await this.bootstrapService.buildBootstrap(context.user.id),
    }
  }

  async declineInvite(accessToken: string, inviteId: string): Promise<BootstrapResponse> {
    const context = await requireSessionContext(this.db, accessToken)
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
    return this.bootstrapService.buildBootstrap(context.user.id)
  }

  async disconnectPairing(accessToken: string): Promise<BootstrapResponse> {
    const context = await requireSessionContext(this.db, accessToken)
    const pairSession = await getPairSession(this.db, context.user.id)
    if (!pairSession) {
      throw new HttpError(400, "User is not paired")
    }

    const recipientUserId = pairSession.user_one_id === context.user.id
      ? pairSession.user_two_id
      : pairSession.user_one_id
    const profile = await getProfileFrom(this.db, context.user.id)
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
    return this.bootstrapService.buildBootstrap(context.user.id)
  }

  async sendDebugPairingConfirmed(accessToken: string): Promise<{ ok: true }> {
    const context = await requireSessionContext(this.db, accessToken)
    const pairSession = await getPairSession(this.db, context.user.id)
    if (!pairSession) {
      throw new HttpError(400, "User is not paired")
    }

    const profile = await getProfileFrom(this.db, context.user.id)
    this.pushDispatchService?.enqueuePairingConfirmed(
      pairSession.id,
      context.user.id,
      profile?.display_name ?? "Your partner",
    )
    return { ok: true }
  }

  async sendDebugPairingDisconnected(accessToken: string): Promise<{ ok: true }> {
    const context = await requireSessionContext(this.db, accessToken)
    const pairSession = await getPairSession(this.db, context.user.id)
    if (!pairSession) {
      throw new HttpError(400, "User is not paired")
    }

    const recipientUserId = pairSession.user_one_id === context.user.id
      ? pairSession.user_two_id
      : pairSession.user_one_id
    const profile = await getProfileFrom(this.db, context.user.id)
    this.pushDispatchService?.enqueuePairingDisconnected(
      pairSession.id,
      recipientUserId,
      context.user.id,
      profile?.display_name ?? "Your partner",
    )
    return { ok: true }
  }

  private requireActivePartnerDetails(profile: ProfileRecord | null): void {
    if (!profile?.partner_display_name?.trim() || !profile.anniversary_date?.trim()) {
      throw new HttpError(400, PARTNER_DETAILS_REQUIRED_MESSAGE)
    }
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
    const inviterProfile = await getProfileFrom(this.db, invite.inviter_user_id)
    const recipientProfile = await getProfileFrom(this.db, recipientUserId)
    const recipientUser = await this.db.query<{ email: string }>(
      `SELECT email FROM users WHERE id = $1`,
      [recipientUserId],
    ).then((r) => r.rows[0] ?? null)
    return {
      inviteId: invite.id,
      inviterDisplayName: inviterProfile?.display_name ?? "Your partner",
      recipientDisplayName: recipientProfile?.display_name ?? recipientUser?.email ?? "You",
      code: invite.code,
      status,
      bootstrapState: await this.bootstrapService.buildBootstrap(recipientUserId),
    }
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
    if (!invite) return null
    const status = this.normalizeInviteStatus(invite)
    if (status === "EXPIRED") {
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
      throw new HttpError(404, "Invite not found")
    }
    const status = this.normalizeInviteStatus(invite)
    if (status === "EXPIRED") {
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

  private normalizeInviteStatus(invite: InviteRecord): InviteRecord["status"] {
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

