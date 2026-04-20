export const AuthStatuses = ["SIGNED_OUT", "SIGNED_IN", "REFRESHING"] as const
export const PairingStatuses = [
  "UNPAIRED",
  "INVITE_PENDING_ACCEPTANCE",
  "PAIRED",
] as const
export const InviteStatuses = [
  "PENDING",
  "REDEEMED",
  "ACCEPTED",
  "DECLINED",
  "EXPIRED",
] as const

export type AuthStatus = (typeof AuthStatuses)[number]
export type PairingStatus = (typeof PairingStatuses)[number]
export type InviteStatus = (typeof InviteStatuses)[number]

export interface InviteSummary {
  inviteId: string
  code: string
  inviterDisplayName: string
  recipientDisplayName: string
  status: InviteStatus
}

export interface BootstrapResponse {
  authStatus: AuthStatus
  onboardingCompleted: boolean
  hasWallpaperConfigured: boolean
  userId: string | null
  userDisplayName: string | null
  partnerDisplayName: string | null
  anniversaryDate: string | null
  pairingStatus: PairingStatus
  pairSessionId: string | null
  invite: InviteSummary | null
}

export interface AuthResponse {
  accessToken: string
  refreshToken: string
  userId: string
  bootstrapState: BootstrapResponse
}

export interface CreateInviteResponse {
  inviteId: string
  code: string
  expiresAt: string
}

export interface RedeemInviteResponse {
  inviteId: string
  inviterDisplayName: string
  recipientDisplayName: string
  code: string
  status: InviteStatus
  bootstrapState: BootstrapResponse
}

export interface AcceptInviteResponse {
  pairSessionId: string
  bootstrapState: BootstrapResponse
}

export interface UserRecord {
  id: string
  google_subject: string
  email: string
}

export interface ProfileRecord {
  user_id: string
  display_name: string | null
  partner_display_name: string | null
  anniversary_date: string | null
}

export interface SessionRecord {
  id: string
  user_id: string
  access_token: string
  refresh_token: string
}

export interface InviteRecord {
  id: string
  inviter_user_id: string
  recipient_user_id: string | null
  code: string
  status: InviteStatus
  expires_at: Date | string
}

export interface PairSessionRecord {
  id: string
  user_one_id: string
  user_two_id: string
}

export interface GoogleIdentity {
  subject: string
  email: string
  name: string | null
}
