import { getMessaging, type Messaging, type ApnsConfig, type ApsAlert } from "firebase-admin/messaging"
import type { DevicePlatform } from "../../contracts/devices.js"
import { firebaseApp, type FirebaseCredentials } from "../firebase/app.js"
import type { MulberryPushMessage, PushSendResult, PushSender, ReactionLiveActivityMessage } from "./types.js"

export class NoopPushSender implements PushSender {
  readonly sentMessages: MulberryPushMessage[] = []
  readonly sentLiveActivities: ReactionLiveActivityMessage[] = []

  async send(message: MulberryPushMessage): Promise<PushSendResult> {
    this.sentMessages.push(message)
    return { invalidTokens: [] }
  }

  async sendLiveActivity(message: ReactionLiveActivityMessage): Promise<boolean> {
    this.sentLiveActivities.push(message)
    return true
  }
}

type ApplePlatform = Exclude<DevicePlatform, "ANDROID">

export function createPushSender(credentials: FirebaseCredentials): PushSender {
  const app = firebaseApp(credentials)
  return app ? new FirebaseAdminPushSender(getMessaging(app)) : new NoopPushSender()
}

export class FirebaseAdminPushSender implements PushSender {
  constructor(private readonly messaging: Messaging) {}

  async send(message: MulberryPushMessage): Promise<PushSendResult> {
    // Macs take their own Apple payload; Android ignores the Apple one, so it goes with the iPhones'.
    const macs = new Set(message.macTokens ?? [])
    const groups: { platform: ApplePlatform; tokens: string[] }[] = [
      { platform: "IOS", tokens: message.tokens.filter((token) => !macs.has(token)) },
      { platform: "MACOS", tokens: message.tokens.filter((token) => macs.has(token)) },
    ]
    const invalidTokens: string[] = []
    for (const group of groups) {
      if (group.tokens.length === 0) continue
      const result = await this.messaging.sendEachForMulticast({
        tokens: group.tokens,
        data: { ...message.data },
        android: {
          priority: message.android.priority,
          collapseKey: message.android.collapseKey,
          ttl: message.android.ttlMs,
        },
        apns: applePushConfiguration(message, group.platform),
      })
      result.responses.forEach((response, index) => {
        if (!response.success && isPermanentTokenError(response.error?.code)) invalidTokens.push(group.tokens[index])
      })
    }
    return { invalidTokens }
  }

  async sendLiveActivity(message: ReactionLiveActivityMessage): Promise<boolean> {
    try {
      await this.messaging.send({ token: message.token, apns: reactionLiveActivityConfiguration(message) })
      return true
    } catch (error) {
      console.warn("[push] reaction live activity refused", {
        event: message.event,
        error: error instanceof Error ? error.message : String(error),
      })
      return false
    }
  }
}

/** Keep Android's data-only delivery intact. iPhones receive a quiet visible canvas alert because
 * the user's Shortcuts trigger cannot use silent pushes. A Mac draws the canvas on the desktop
 * itself, so canvas changes reach it silently, only to catch up. */
export function applePushConfiguration(message: MulberryPushMessage, platform: ApplePlatform = "IOS"): ApnsConfig {
  const data = message.data
  const headers = {
    "apns-collapse-id": message.android.collapseKey.slice(0, 64),
    "apns-expiration": String(Math.floor((Date.now() + message.android.ttlMs) / 1_000)),
  }
  const background: ApnsConfig = {
    headers: { ...headers, "apns-push-type": "background", "apns-priority": "5" },
    payload: { aps: { contentAvailable: true } },
  }
  if (platform === "MACOS" && (data.type === "CANVAS_UPDATED" || data.type === "CANVAS_NUDGE")) return background
  let alert: { title: string; body: string } | undefined
  switch (data.type) {
    case "CANVAS_UPDATED":
      alert = { title: "Mulberry canvas updated", body: "Your wallpaper update is ready." }
      break
    case "PAIRING_CONFIRMED":
      alert = { title: "You’re connected", body: "Open Mulberry to start drawing together." }
      break
    case "PAIRING_DISCONNECTED":
      alert = { title: "Pairing ended", body: "Open Mulberry to see your pairing status." }
      break
    case "DRAW_REMINDER":
      alert = { title: "Draw them something cute", body: "Your shared canvas is waiting." }
      break
    default:
      // Reactions and visibility changes are fetched by the app; an iPhone's reactions also show in
      // their Live Activity, which is pushed separately. The canvas nudge is Android's partner-doodle
      // alert: an iPhone's wallpaper already follows the canvas update push.
      return background
  }
  // Joining and ending a pairing show as banners; the rest arrive quietly in Notification Center.
  const banner = data.type === "PAIRING_CONFIRMED" || data.type === "PAIRING_DISCONNECTED"
  return {
    headers: { ...headers, "apns-push-type": "alert", "apns-priority": "10" },
    payload: {
      aps: {
        alert,
        threadId: data.type === "CANVAS_UPDATED" ? "mulberry-wallpaper" : "mulberry-account",
        "interruption-level": banner ? "active" : "passive",
      },
    },
  }
}

/** How long a reaction stays fresh in the Dynamic Island, as when the app shows it. */
const REACTION_ACTIVITY_FRESH_SECONDS = 5 * 60

/** The Live Activity push FCM forwards to APNs; FCM sets its push type and topic. Starting it
 * names the Swift type the iPhone decodes; an update carries the content alone. No end event is
 * ever sent: the activity stays until the reactions are seen in the app. */
export function reactionLiveActivityConfiguration(message: ReactionLiveActivityMessage, now = Date.now()): ApnsConfig {
  const timestamp = Math.floor(now / 1_000)
  const alert: ApsAlert & { sound: string } = { ...message.alert, sound: "default" }
  const start = message.event === "start"
    ? { "attributes-type": "ReactionActivityAttributes", attributes: { partnerName: message.partnerName } }
    : {}
  return {
    liveActivityToken: message.liveActivityToken,
    headers: { "apns-priority": "10" },
    payload: {
      aps: {
        timestamp,
        event: message.event,
        "content-state": message.contentState,
        "stale-date": timestamp + REACTION_ACTIVITY_FRESH_SECONDS,
        alert,
        ...start,
      },
    },
  }
}

function isPermanentTokenError(code: string | undefined): boolean {
  return code === "messaging/invalid-registration-token" ||
    code === "messaging/registration-token-not-registered"
}

