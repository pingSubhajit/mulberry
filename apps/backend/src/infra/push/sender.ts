import { readFileSync } from "node:fs"
import { cert, getApps, initializeApp } from "firebase-admin/app"
import { getMessaging, type Messaging, type ApnsConfig } from "firebase-admin/messaging"
import type { MulberryPushMessage, PushSendResult, PushSender } from "./types.js"

export class NoopPushSender implements PushSender {
  readonly sentMessages: MulberryPushMessage[] = []

  async send(message: MulberryPushMessage): Promise<PushSendResult> {
    this.sentMessages.push(message)
    return { invalidTokens: [] }
  }
}

export interface FirebasePushSenderOptions {
  serviceAccountPath?: string
  serviceAccountJson?: string
}

export function createPushSender(options: FirebasePushSenderOptions): PushSender {
  if (!options.serviceAccountPath && !options.serviceAccountJson) {
    return new NoopPushSender()
  }
  const serviceAccount = options.serviceAccountJson
    ? JSON.parse(options.serviceAccountJson)
    : JSON.parse(readFileSync(options.serviceAccountPath ?? "", "utf8"))
  const app = getApps()[0] ?? initializeApp({
    credential: cert(serviceAccount),
  })
  return new FirebaseAdminPushSender(getMessaging(app))
}

export class FirebaseAdminPushSender implements PushSender {
  constructor(private readonly messaging: Messaging) {}

  async send(message: MulberryPushMessage): Promise<PushSendResult> {
    if (message.tokens.length === 0) return { invalidTokens: [] }
    const result = await this.messaging.sendEachForMulticast({
      tokens: message.tokens,
      data: { ...message.data },
      android: {
        priority: message.android.priority,
        collapseKey: message.android.collapseKey,
        ttl: message.android.ttlMs,
      },
      apns: applePushConfiguration(message),
    })
    return {
      invalidTokens: result.responses
        .map((response, index) => {
          if (response.success) return null
          return isPermanentTokenError(response.error?.code) ? message.tokens[index] : null
        })
        .filter((token): token is string => token !== null),
    }
  }
}

/** Keep Android's data-only delivery intact. Apple receives a quiet visible
 * canvas alert because the user's Shortcuts trigger cannot use silent pushes. */
export function applePushConfiguration(message: MulberryPushMessage): ApnsConfig {
  const data = message.data
  const headers = {
    "apns-collapse-id": message.android.collapseKey.slice(0, 64),
    "apns-expiration": String(Math.floor((Date.now() + message.android.ttlMs) / 1_000)),
  }
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
    case "CANVAS_NUDGE":
      alert = { title: "A drawing is waiting", body: "Open Mulberry to check your wallpaper setup." }
      break
    case "DRAW_REMINDER":
      alert = { title: "Draw them something cute", body: "Your shared canvas is waiting." }
      break
    default:
      // Reactions are outside iOS v1. Visibility is fetched when the app opens.
      return { headers: { ...headers, "apns-push-type": "background", "apns-priority": "5" }, payload: { aps: { contentAvailable: true } } }
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

function isPermanentTokenError(code: string | undefined): boolean {
  return code === "messaging/invalid-registration-token" ||
    code === "messaging/registration-token-not-registered"
}

