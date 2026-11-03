import type { AuthStatus, SignInMethod } from "./auth.js"
import type { InviteSummary, PairingStatus } from "./pairing.js"
import type { PresenceSummary } from "./presence.js"
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
  /** How this account can sign in. */
  signInMethods: SignInMethod[]
  userPhotoUrl: string | null
  userDisplayName: string | null
  partnerPhotoUrl: string | null
  partnerDisplayName: string | null
  partnerWallpaperStatus: PartnerWallpaperStatus | null
  anniversaryDate: string | null
  partnerProfileNextUpdateAt: string | null
  pairedAt: string | null
  currentStreakDays: number
  /** Whether the account has ever drawn, on any device or pair: the first drawing is celebrated once. */
  hasDrawn: boolean
  pairingStatus: PairingStatus
  pairSessionId: string | null
  invite: InviteSummary | null
  ownPresence: PresenceSummary
  partnerPresence: PresenceSummary
  /** iCloud links to the iOS wallpaper Shortcut, by app bundle ID. */
  wallpaperShortcutLinks: Record<string, string>
}

export interface StreakResponseWithWeek {
  today: string
  currentStreakDays: number
  previousStreakDays: number
  hasActivityToday: boolean
  lastActivityDay: string | null
  week: StreakWeekDay[]
}
