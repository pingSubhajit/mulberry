import { randomUUID } from "node:crypto"
import sharp from "sharp"
import type { Database } from "../../infra/db/database.js"
import { HttpError } from "../../infra/http/HttpError.js"
import type { BootstrapResponse } from "../../contracts/bootstrap.js"
import type { ProfileRecord, UserWallpaperStatusRow } from "../../contracts/dbRecords.js"
import type { UpdateWallpaperStatusRequest } from "../../contracts/profile.js"
import type { PushDispatchService } from "../../infra/push/dispatchService.js"
import { requireSessionContext } from "../_shared/session.js"
import { getProfileFrom } from "../_shared/profiles.js"
import { getPairSession } from "../_shared/pairs.js"
import type { BootstrapService } from "../bootstrap/service.js"

export interface UploadedProfilePhoto {
  filename?: string
  contentType?: string
  data: Buffer
}

export type ProfilePhotoStorage = {
  upload(path: string, data: Buffer, contentType: string): Promise<void>
  remove(paths: string[]): Promise<void>
} | null | undefined

const PARTNER_PROFILE_UPDATE_COOLDOWN_MS = 72 * 60 * 60 * 1_000
const PROFILE_PHOTO_PREFIX = "profile-photos"
const PROFILE_PHOTO_MAX_BYTES = 10 * 1024 * 1024

export class ProfileService {
  constructor(
    private readonly db: Database,
    private readonly bootstrapService: BootstrapService,
    private readonly pushDispatchService?: PushDispatchService,
    private readonly profilePhotoStorage?: ProfilePhotoStorage,
  ) {}

  async updateCanvasStrokeRenderMode(
    accessToken: string,
    request: { canvasStrokeRenderMode: unknown },
  ): Promise<BootstrapResponse> {
    const context = await requireSessionContext(this.db, accessToken)
    const pairSession = await getPairSession(this.db, context.user.id)
    if (!pairSession) {
      throw new HttpError(400, "User is not paired")
    }

    const mode = parseCanvasStrokeRenderMode(request.canvasStrokeRenderMode)
    await this.db.query(
      `
      UPDATE pair_sessions
      SET canvas_stroke_render_mode = $1
      WHERE id = $2
      `,
      [mode, pairSession.id],
    )

    return this.bootstrapService.buildBootstrap(context.user.id)
  }

  async updateWallpaperStatus(accessToken: string, request: UpdateWallpaperStatusRequest): Promise<{ ok: true }> {
    const context = await requireSessionContext(this.db, accessToken)
    const pairSession = await getPairSession(this.db, context.user.id)
    if (!pairSession) {
      throw new HttpError(400, "User is not paired")
    }

    const wallpaperSyncEnabled = Boolean(request.wallpaperSyncEnabled)
    const wallpaperSelectedOnHome = Boolean(request.wallpaperSelectedOnHome)
    const wallpaperSelectedOnLock = Boolean(request.wallpaperSelectedOnLock)
    const canSeeLatestDrawings = wallpaperSyncEnabled && (wallpaperSelectedOnHome || wallpaperSelectedOnLock)

    const existing = await this.getUserWallpaperStatus(context.user.id)
    const shouldNotifyPeer = existing != null && existing.has_ever_been_able_to_see && (
      existing.can_see_latest_drawings !== canSeeLatestDrawings
    )

    const rows = await this.db.query<UserWallpaperStatusRow>(
      `
      INSERT INTO user_wallpaper_status (
        user_id,
        pair_session_id,
        wallpaper_sync_enabled,
        wallpaper_selected_on_home,
        wallpaper_selected_on_lock,
        can_see_latest_drawings,
        has_ever_been_able_to_see,
        updated_at
      ) VALUES ($1, $2, $3, $4, $5, $6, $7, NOW())
      ON CONFLICT (user_id) DO UPDATE SET
        pair_session_id = EXCLUDED.pair_session_id,
        wallpaper_sync_enabled = EXCLUDED.wallpaper_sync_enabled,
        wallpaper_selected_on_home = EXCLUDED.wallpaper_selected_on_home,
        wallpaper_selected_on_lock = EXCLUDED.wallpaper_selected_on_lock,
        can_see_latest_drawings = EXCLUDED.can_see_latest_drawings,
        has_ever_been_able_to_see = (user_wallpaper_status.has_ever_been_able_to_see OR EXCLUDED.has_ever_been_able_to_see),
        updated_at = NOW()
      RETURNING
        user_id,
        pair_session_id,
        wallpaper_sync_enabled,
        wallpaper_selected_on_home,
        wallpaper_selected_on_lock,
        can_see_latest_drawings,
        has_ever_been_able_to_see,
        updated_at
      `,
      [
        context.user.id,
        pairSession.id,
        wallpaperSyncEnabled,
        wallpaperSelectedOnHome,
        wallpaperSelectedOnLock,
        canSeeLatestDrawings,
        canSeeLatestDrawings,
      ],
    )
    const persisted = rows.rows[0]

    if (shouldNotifyPeer) {
      const profile = await getProfileFrom(this.db, context.user.id)
      this.pushDispatchService?.enqueuePartnerVisibilityChanged(
        pairSession.id,
        context.user.id,
        profile?.display_name ?? "Your partner",
        persisted.can_see_latest_drawings,
        {
          wallpaperSyncEnabled: persisted.wallpaper_sync_enabled,
          wallpaperSelectedOnHome: persisted.wallpaper_selected_on_home,
          wallpaperSelectedOnLock: persisted.wallpaper_selected_on_lock,
        },
      )
    }

    return { ok: true }
  }

  async updateProfile(
    accessToken: string,
    profile: {
      displayName: string
      partnerDisplayName: string
      anniversaryDate: string
    },
  ): Promise<BootstrapResponse> {
    const context = await requireSessionContext(this.db, accessToken)
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

    return this.bootstrapService.buildBootstrap(context.user.id)
  }

  async updateDisplayName(accessToken: string, displayNameInput: string): Promise<BootstrapResponse> {
    const context = await requireSessionContext(this.db, accessToken)
    const displayName = displayNameInput.trim()
    if (!displayName) {
      throw new HttpError(400, "Profile name is required")
    }

    const existing = await getProfileFrom(this.db, context.user.id)
    const pairSession = await getPairSession(this.db, context.user.id)
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

    return this.bootstrapService.buildBootstrap(context.user.id)
  }

  async updateProfilePhoto(accessToken: string, upload: UploadedProfilePhoto): Promise<BootstrapResponse> {
    const context = await requireSessionContext(this.db, accessToken)
    const processed = await this.storeProfilePhoto(context.user.id, upload)
    const existing = await getProfileFrom(this.db, context.user.id)
    const pairSession = await getPairSession(this.db, context.user.id)
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
    return this.bootstrapService.buildBootstrap(context.user.id)
  }

  async updatePartnerProfile(
    accessToken: string,
    profile: {
      partnerDisplayName: string
      anniversaryDate: string
    },
  ): Promise<BootstrapResponse> {
    const context = await requireSessionContext(this.db, accessToken)
    const partnerDisplayName = profile.partnerDisplayName.trim()
    const anniversaryDate = profile.anniversaryDate.trim()
    if (!partnerDisplayName || !anniversaryDate) {
      throw new HttpError(400, "Partner name and relationship anniversary are required")
    }

    const existing = await getProfileFrom(this.db, context.user.id)
    const pairSession = await getPairSession(this.db, context.user.id)
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

    return this.bootstrapService.buildBootstrap(context.user.id)
  }

  async updatePartnerProfileWithPhoto(
    accessToken: string,
    input: {
      partnerDisplayName: string
      anniversaryDate: string
      upload: UploadedProfilePhoto
    },
  ): Promise<BootstrapResponse> {
    const context = await requireSessionContext(this.db, accessToken)
    const partnerDisplayName = input.partnerDisplayName.trim()
    const anniversaryDate = input.anniversaryDate.trim()
    if (!partnerDisplayName || !anniversaryDate) {
      throw new HttpError(400, "Partner name and relationship anniversary are required")
    }

    const existing = await getProfileFrom(this.db, context.user.id)
    const pairSession = await getPairSession(this.db, context.user.id)
    const enforceCooldown = pairSession !== null
    const nextUpdateAt = enforceCooldown ? this.partnerProfileNextUpdateAt(existing) : null
    if (nextUpdateAt) {
      throw new HttpError(409, `Partner details can be updated again at ${nextUpdateAt}`)
    }

    const processed = await this.storeProfilePhoto(context.user.id, input.upload)
    const updatedAt = enforceCooldown ? new Date().toISOString() : null
    const oldPaths: string[] = []

    await this.db.transaction(async (tx) => {
      await tx.query(
        `
        INSERT INTO user_profiles (
          user_id,
          display_name,
          partner_display_name,
          anniversary_date,
          partner_profile_photo_path,
          partner_profile_updated_at,
          updated_at
        ) VALUES ($1, $2, $3, $4, $5, $6, NOW())
        ON CONFLICT (user_id) DO UPDATE SET
          partner_display_name = EXCLUDED.partner_display_name,
          anniversary_date = EXCLUDED.anniversary_date,
          partner_profile_photo_path = EXCLUDED.partner_profile_photo_path,
          partner_profile_updated_at = EXCLUDED.partner_profile_updated_at,
          updated_at = NOW()
        `,
        [
          context.user.id,
          existing?.display_name ?? context.user.email,
          partnerDisplayName,
          anniversaryDate,
          processed.path,
          updatedAt,
        ],
      )

      if (pairSession) {
        const peerUserId = pairSession.user_one_id === context.user.id
          ? pairSession.user_two_id
          : pairSession.user_one_id
        const peerProfile = await getProfileFrom(tx, peerUserId)
        if (peerProfile?.profile_photo_path) {
          oldPaths.push(peerProfile.profile_photo_path)
        }
        if (existing?.partner_profile_photo_path) {
          oldPaths.push(existing.partner_profile_photo_path)
        }

        await tx.query(
          `
          INSERT INTO user_profiles (
            user_id,
            display_name,
            anniversary_date,
            profile_photo_path,
            updated_at
          ) VALUES ($1, $2, $3, $4, NOW())
          ON CONFLICT (user_id) DO UPDATE SET
            display_name = EXCLUDED.display_name,
            anniversary_date = EXCLUDED.anniversary_date,
            profile_photo_path = EXCLUDED.profile_photo_path,
            updated_at = NOW()
          `,
          [peerUserId, partnerDisplayName, anniversaryDate, processed.path],
        )
      } else {
        if (existing?.partner_profile_photo_path) {
          oldPaths.push(existing.partner_profile_photo_path)
        }
      }
    })

    await this.removeStoredProfilePhotos(oldPaths)
    return this.bootstrapService.buildBootstrap(context.user.id)
  }

  async updatePartnerProfilePhoto(accessToken: string, upload: UploadedProfilePhoto): Promise<BootstrapResponse> {
    const context = await requireSessionContext(this.db, accessToken)
    const existing = await getProfileFrom(this.db, context.user.id)
    const pairSession = await getPairSession(this.db, context.user.id)
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
        const peerProfile = await getProfileFrom(tx, peerUserId)
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
    return this.bootstrapService.buildBootstrap(context.user.id)
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

  private async storeProfilePhoto(userId: string, upload: UploadedProfilePhoto): Promise<{ path: string }> {
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
}

function parseCanvasStrokeRenderMode(raw: unknown): "dry" | "round" {
  if (typeof raw !== "string") {
    throw new HttpError(400, "canvasStrokeRenderMode must be a string")
  }
  const value = raw.trim().toLowerCase()
  if (!value) {
    throw new HttpError(400, "canvasStrokeRenderMode must be non-empty")
  }
  switch (value) {
    case "dry":
    case "dry_brush":
    case "dry-brush":
    case "dry_brush_only":
    case "dry-brush-only":
    case "dry_brush_only_strokes":
      return "dry"
    case "round":
    case "round_stroke":
    case "round-stroke":
    case "round_stroke_only":
    case "round-stroke-only":
    case "round_stroke_only_strokes":
      return "round"
    default:
      throw new HttpError(400, `Unsupported canvasStrokeRenderMode: ${raw}`)
  }
}

function isSupportedProfilePhotoType(contentType: string | undefined): boolean {
  return contentType === "image/jpeg" ||
    contentType === "image/png" ||
    contentType === "image/webp"
}
