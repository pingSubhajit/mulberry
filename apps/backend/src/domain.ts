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
export const CanvasOperationTypes = [
  "ADD_STROKE",
  "APPEND_POINTS",
  "FINISH_STROKE",
  "DELETE_STROKE",
  "CLEAR_CANVAS",
] as const
export const DevicePlatforms = ["ANDROID"] as const

export type AuthStatus = (typeof AuthStatuses)[number]
export type PairingStatus = (typeof PairingStatuses)[number]
export type InviteStatus = (typeof InviteStatuses)[number]
export type CanvasOperationType = (typeof CanvasOperationTypes)[number]
export type DevicePlatform = (typeof DevicePlatforms)[number]

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
  userEmail: string | null
  userPhotoUrl: string | null
  userDisplayName: string | null
  partnerPhotoUrl: string | null
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

export interface CanvasOperationEnvelope {
  clientOperationId: string
  actorUserId: string
  pairSessionId: string
  type: CanvasOperationType
  strokeId: string | null
  payload: unknown
  clientCreatedAt: string
  serverRevision: number
  createdAt: string
}

export interface ClientCanvasOperation {
  clientOperationId: string
  type: CanvasOperationType
  strokeId?: string | null
  payload: unknown
  clientCreatedAt: string
}

export interface ClientCanvasOperationBatch {
  batchId: string
  operations: ClientCanvasOperation[]
  clientCreatedAt: string
}

export interface CanvasOpsResponse {
  operations: CanvasOperationEnvelope[]
}

export interface CanvasSnapshotResponse {
  pairSessionId: string
  revision: number
  snapshot: unknown
  updatedAt: string | null
}

export interface RegisterFcmTokenRequest {
  token: string
  platform: DevicePlatform
  appEnvironment: string
}

export interface DeviceTokenRecord {
  id: string
  userId: string
  token: string
  platform: DevicePlatform
  appEnvironment: string
  lastSeenAt: string
  revokedAt: string | null
}

export interface CanvasSyncBootstrap {
  pairSessionId: string
  userId: string
  latestRevision: number
  missedOperations: CanvasOperationEnvelope[]
}

export interface UserRecord {
  id: string
  google_subject: string
  email: string
  google_picture_url: string | null
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

export interface CanvasOperationRecord {
  id: string
  pair_session_id: string
  server_revision: number
  client_operation_id: string
  actor_user_id: string
  type: CanvasOperationType
  stroke_id: string | null
  payload_json: unknown
  client_created_at: Date | string
  created_at: Date | string
}

export interface DeviceTokenRow {
  id: string
  user_id: string
  token: string
  platform: DevicePlatform
  app_environment: string
  last_seen_at: Date | string
  revoked_at: Date | string | null
}

export interface GoogleIdentity {
  subject: string
  email: string
  name: string | null
  pictureUrl: string | null
}
