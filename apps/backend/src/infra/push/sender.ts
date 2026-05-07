import { readFileSync } from "node:fs"
import { cert, getApps, initializeApp } from "firebase-admin/app"
import { getMessaging, type Messaging } from "firebase-admin/messaging"
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

function isPermanentTokenError(code: string | undefined): boolean {
  return code === "messaging/invalid-registration-token" ||
    code === "messaging/registration-token-not-registered"
}

