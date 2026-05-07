import type { AuthStatus } from "./auth.js"
import type { InviteSummary, PairingStatus } from "./pairing.js"
import type { StreakWeekDay } from "./streak.js"

export interface PartnerWallpaperStatus {
  updatedAt: string
  wallpaperSyncEnabled: boolean
  wallpaperSelectedOnHome: boolean
  wallpaperSelectedOnLock: boolean
  canSeeLatestDrawings: boolean
  hasEverBeenAbleToSee: boolean
}

export interface BootstrapResponse {
  authStatus: AuthStatus
  onboardingCompleted: boolean
  hasWallpaperConfigured: boolean
  canvasStrokeRenderMode: string
  userId: string | null
  userEmail: string | null
  userPhotoUrl: string | null
  userDisplayName: string | null
  partnerPhotoUrl: string | null
  partnerDisplayName: string | null
  partnerWallpaperStatus: PartnerWallpaperStatus | null
  anniversaryDate: string | null
  partnerProfileNextUpdateAt: string | null
  pairedAt: string | null
  currentStreakDays: number
  pairingStatus: PairingStatus
  pairSessionId: string | null
  invite: InviteSummary | null
}

export interface StreakResponseWithWeek {
  today: string
  currentStreakDays: number
  previousStreakDays: number
  hasActivityToday: boolean
  lastActivityDay: string | null
  week: StreakWeekDay[]
}
