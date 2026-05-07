import type { Database } from "../../infra/db/database.js"
import type { BootstrapResponse } from "../../contracts/bootstrap.js"
import type { InviteRecord, ProfileRecord, UserWallpaperStatusRow } from "../../contracts/dbRecords.js"
import { requireSessionContext, getUserById } from "../_shared/session.js"
import { getProfileFrom } from "../_shared/profiles.js"
import { getPairSession } from "../_shared/pairs.js"
import type { StreakService } from "../streak/service.js"

const PARTNER_PROFILE_UPDATE_COOLDOWN_MS = 72 * 60 * 60 * 1_000

export type ProfilePhotoStorage = {
  publicUrl(path: string): string
} | null | undefined

export class BootstrapService {
  constructor(
    private readonly db: Database,
    private readonly streakService: StreakService,
    private readonly profilePhotoStorage?: ProfilePhotoStorage,
  ) {}

  async getBootstrap(accessToken: string): Promise<BootstrapResponse> {
    const context = await requireSessionContext(this.db, accessToken)
    return this.buildBootstrap(context.user.id)
  }

  async buildBootstrap(userId: string): Promise<BootstrapResponse> {
    const user = await getUserById(this.db, userId)
    let profile = await getProfileFrom(this.db, userId)
    const pairSession = await getPairSession(this.db, userId)
    const partnerUser = pairSession
      ? await getUserById(
          this.db,
          pairSession.user_one_id === userId ? pairSession.user_two_id : pairSession.user_one_id,
        )
      : null
    const partnerWallpaperStatus = partnerUser ? await this.getUserWallpaperStatus(partnerUser.id) : null
    const pendingInvite = await this.getPendingInvite(userId)
    let onboardingCompleted = Boolean(profile?.onboarding_completed_at)
    if (pendingInvite && !onboardingCompleted) {
      await this.hydrateRecipientProfileFromInvite(
        userId,
        profile,
        await getProfileFrom(this.db, pendingInvite.inviter_user_id),
        false,
      )
      profile = await getProfileFrom(this.db, userId)
      onboardingCompleted = Boolean(profile?.onboarding_completed_at)
    }

    const inviterPhotoUrl = pendingInvite
      ? this.profilePhotoUrl((await getProfileFrom(this.db, pendingInvite.inviter_user_id))?.profile_photo_path) ??
        (await getUserById(this.db, pendingInvite.inviter_user_id))?.google_picture_url ??
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
        ? this.profilePhotoUrl((partnerUser ? await getProfileFrom(this.db, partnerUser.id) : null)?.profile_photo_path) ??
          partnerUser?.google_picture_url ??
          null
        : this.profilePhotoUrl(profile?.partner_profile_photo_path) ?? inviterPhotoUrl,
      partnerDisplayName: profile?.partner_display_name ?? null,
      partnerWallpaperStatus: partnerWallpaperStatus ? {
        updatedAt: new Date(partnerWallpaperStatus.updated_at).toISOString(),
        wallpaperSyncEnabled: partnerWallpaperStatus.wallpaper_sync_enabled,
        wallpaperSelectedOnHome: partnerWallpaperStatus.wallpaper_selected_on_home,
        wallpaperSelectedOnLock: partnerWallpaperStatus.wallpaper_selected_on_lock,
        canSeeLatestDrawings: partnerWallpaperStatus.can_see_latest_drawings,
        hasEverBeenAbleToSee: partnerWallpaperStatus.has_ever_been_able_to_see,
      } : null,
      anniversaryDate: profile?.anniversary_date ?? null,
      partnerProfileNextUpdateAt: pairSession ? this.partnerProfileNextUpdateAt(profile) : null,
      pairedAt: pairSession ? new Date(pairSession.created_at).toISOString() : null,
      currentStreakDays: pairSession ? await this.streakService.currentStreakDays(pairSession.id) : 0,
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
              (await getProfileFrom(this.db, pendingInvite.inviter_user_id))?.display_name ?? "Your partner",
            recipientDisplayName: profile?.display_name ?? "You",
            status: "REDEEMED",
          }
        : null,
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

  private profilePhotoUrl(path: string | null | undefined): string | null {
    if (!path || !this.profilePhotoStorage) return null
    return this.profilePhotoStorage.publicUrl(path)
  }

  private async getUserWallpaperStatus(userId: string): Promise<UserWallpaperStatusRow | null> {
    const rows = await this.db.query<UserWallpaperStatusRow>(
      `
      SELECT
        user_id,
        pair_session_id,
        wallpaper_sync_enabled,
        wallpaper_selected_on_home,
        wallpaper_selected_on_lock,
        can_see_latest_drawings,
        has_ever_been_able_to_see,
        updated_at
      FROM user_wallpaper_status
      WHERE user_id = $1
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
    return rows.rows[0] ?? null
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
}

