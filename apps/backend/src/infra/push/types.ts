import type { ReactionType } from "../../contracts/reactions.js"

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

export interface PushRecipients {
  tokens: string[]
  /** The tokens in `tokens` that belong to Macs, which take canvas changes silently. */
  macTokens: string[]
}

export interface MulberryPushMessage {
  tokens: string[]
  macTokens?: string[]
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

/** The reactions Live Activity's content, as the iPhone's `ReactionActivityAttributes.ContentState`
 * decodes it: each kind that arrived with its count, and the kind that arrived last. */
export interface ReactionActivityContentState {
  counts: Partial<Record<ReactionType, number>>
  latest: ReactionType
}

/** Starts the reactions Live Activity on an iPhone, or updates the one it shows. */
export interface ReactionLiveActivityMessage {
  /** The iPhone's FCM registration token. */
  token: string
  /** The app's push-to-start token to start an activity; the activity's own push token to update it. */
  liveActivityToken: string
  event: "start" | "update"
  /** The name the recipient gave their partner, which the activity shows. */
  partnerName: string
  contentState: ReactionActivityContentState
  alert: { title: string; body: string }
}

export interface PushSender {
  send(message: MulberryPushMessage): Promise<PushSendResult>
  /** Whether the Live Activity push was accepted. A refused one says nothing about the FCM token. */
  sendLiveActivity(message: ReactionLiveActivityMessage): Promise<boolean>
}
