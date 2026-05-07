import type { BootstrapResponse } from "./bootstrap.js"

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

export type PairingStatus = (typeof PairingStatuses)[number]
export type InviteStatus = (typeof InviteStatuses)[number]

export interface InviteSummary {
  inviteId: string
  code: string
  inviterDisplayName: string
  recipientDisplayName: string
  status: InviteStatus
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

