export interface CanvasUpdatedPushPayload {
  type: "CANVAS_UPDATED"
  pairSessionId: string
  latestRevision: string
  snapshotRevision: string
  actorUserId: string
}

export interface CanvasNudgePushPayload {
  type: "CANVAS_NUDGE"
  pairSessionId: string
  latestRevision: string
  actorUserId: string
  actorDisplayName: string
}

export interface PairingConfirmedPushPayload {
  type: "PAIRING_CONFIRMED"
  pairSessionId: string
  actorUserId: string
  actorDisplayName: string
}

export interface PairingDisconnectedPushPayload {
  type: "PAIRING_DISCONNECTED"
  pairSessionId: string
  actorUserId: string
  actorDisplayName: string
}

export interface DrawReminderPushPayload {
  type: "DRAW_REMINDER"
  pairSessionId: string
  partnerDisplayName: string
  reminderCount: string
}

export interface ReactionPushPayload {
  type: "REACTION"
  pairSessionId: string
  generation: string
  heartCount: string
  hugCount: string
  kissCount: string
  smileCount: string
  laughCount: string
  sparkleCount: string
}

export interface PartnerVisibilityChangedPushPayload {
  type: "PARTNER_VISIBILITY_CHANGED"
  pairSessionId: string
  actorUserId: string
  actorDisplayName: string
  canSeeLatestDrawings: string
  wallpaperSyncEnabled: string
  wallpaperSelectedOnHome: string
  wallpaperSelectedOnLock: string
}

export type MulberryPushPayload =
  | CanvasUpdatedPushPayload
  | CanvasNudgePushPayload
  | PairingConfirmedPushPayload
  | PairingDisconnectedPushPayload
  | DrawReminderPushPayload
  | ReactionPushPayload
  | PartnerVisibilityChangedPushPayload

export interface MulberryPushMessage {
  tokens: string[]
  data: MulberryPushPayload
  android: {
    priority: "high"
    collapseKey: string
    ttlMs: number
  }
}

export interface PushSendResult {
  invalidTokens: string[]
}

export interface PushSender {
  send(message: MulberryPushMessage): Promise<PushSendResult>
}
