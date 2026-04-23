import { readFileSync } from "node:fs"
import { cert, getApps, initializeApp } from "firebase-admin/app"
import { getMessaging, type Messaging } from "firebase-admin/messaging"
import type { Database } from "./db.js"

export interface CanvasUpdatedPushPayload {
  type: "CANVAS_UPDATED"
  pairSessionId: string
  latestRevision: string
  snapshotRevision: string
  actorUserId: string
}

export interface PairingConfirmedPushPayload {
  type: "PAIRING_CONFIRMED"
  pairSessionId: string
  actorUserId: string
  actorDisplayName: string
}

export type MulberryPushPayload = CanvasUpdatedPushPayload | PairingConfirmedPushPayload

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

interface PendingCanvasUpdate {
  pairSessionId: string
  actorUserId: string
  latestRevision: number
  snapshotRevision: number
}

export interface PushDispatchOptions {
  debounceMs?: number
  ttlMs?: number
}

export class PushDispatchService {
  private readonly pendingByPairSession = new Map<string, PendingCanvasUpdate>()
  private readonly timers = new Map<string, ReturnType<typeof setTimeout>>()
  private readonly debounceMs: number
  private readonly ttlMs: number

  constructor(
    private readonly db: Database,
    private readonly sender: PushSender,
    options: PushDispatchOptions = {},
  ) {
    this.debounceMs = options.debounceMs ?? 2_000
    this.ttlMs = options.ttlMs ?? 60_000
  }

  enqueueCanvasUpdated(
    pairSessionId: string,
    actorUserId: string,
    latestRevision: number,
    snapshotRevision = latestRevision,
  ): void {
    this.pendingByPairSession.set(pairSessionId, {
      pairSessionId,
      actorUserId,
      latestRevision,
      snapshotRevision,
    })

    const existing = this.timers.get(pairSessionId)
    if (existing) {
      clearTimeout(existing)
    }

    if (this.debounceMs <= 0) {
      void this.flushPairSession(pairSessionId)
      return
    }

    this.timers.set(
      pairSessionId,
      setTimeout(() => {
        void this.flushPairSession(pairSessionId)
      }, this.debounceMs),
    )
  }

  enqueuePairingConfirmed(
    pairSessionId: string,
    actorUserId: string,
    actorDisplayName: string,
  ): void {
    void this.sendPairingConfirmed(pairSessionId, actorUserId, actorDisplayName)
  }

  async flushPairSession(pairSessionId: string): Promise<void> {
    const pending = this.pendingByPairSession.get(pairSessionId)
    if (!pending) return

    const timer = this.timers.get(pairSessionId)
    if (timer) {
      clearTimeout(timer)
      this.timers.delete(pairSessionId)
    }
    this.pendingByPairSession.delete(pairSessionId)

    const tokens = await this.activePeerTokens(pending.pairSessionId, pending.actorUserId)
    if (tokens.length === 0) {
      console.info("[push] no active peer tokens", {
        pairSessionId: pending.pairSessionId,
        actorUserId: pending.actorUserId,
        latestRevision: pending.latestRevision,
      })
      return
    }

    console.info("[push] sending canvas update", {
      pairSessionId: pending.pairSessionId,
      actorUserId: pending.actorUserId,
      latestRevision: pending.latestRevision,
      snapshotRevision: pending.snapshotRevision,
      tokenCount: tokens.length,
    })

    let result: PushSendResult
    try {
      result = await this.sender.send({
        tokens,
        data: {
          type: "CANVAS_UPDATED",
          pairSessionId: pending.pairSessionId,
          latestRevision: String(pending.latestRevision),
          snapshotRevision: String(pending.snapshotRevision),
          actorUserId: pending.actorUserId,
        },
        android: {
          priority: "high",
          collapseKey: `canvas-${pending.pairSessionId}`,
          ttlMs: this.ttlMs,
        },
      })
    } catch (error) {
      console.error("[push] canvas update send failed", {
        pairSessionId: pending.pairSessionId,
        latestRevision: pending.latestRevision,
        error: error instanceof Error ? error.message : String(error),
      })
      return
    }

    console.info("[push] canvas update sent", {
      pairSessionId: pending.pairSessionId,
      latestRevision: pending.latestRevision,
      invalidTokenCount: result.invalidTokens.length,
    })

    if (result.invalidTokens.length > 0) {
      await this.revokeTokens(result.invalidTokens)
    }
  }

  private async sendPairingConfirmed(
    pairSessionId: string,
    actorUserId: string,
    actorDisplayName: string,
  ): Promise<void> {
    const tokens = await this.activePeerTokens(pairSessionId, actorUserId)
    if (tokens.length === 0) {
      console.info("[push] no active peer tokens for pairing confirmation", {
        pairSessionId,
        actorUserId,
      })
      return
    }

    console.info("[push] sending pairing confirmation", {
      pairSessionId,
      actorUserId,
      tokenCount: tokens.length,
    })

    let result: PushSendResult
    try {
      result = await this.sender.send({
        tokens,
        data: {
          type: "PAIRING_CONFIRMED",
          pairSessionId,
          actorUserId,
          actorDisplayName,
        },
        android: {
          priority: "high",
          collapseKey: `pairing-${pairSessionId}`,
          ttlMs: this.ttlMs,
        },
      })
    } catch (error) {
      console.error("[push] pairing confirmation send failed", {
        pairSessionId,
        actorUserId,
        error: error instanceof Error ? error.message : String(error),
      })
      return
    }

    console.info("[push] pairing confirmation sent", {
      pairSessionId,
      invalidTokenCount: result.invalidTokens.length,
    })

    if (result.invalidTokens.length > 0) {
      await this.revokeTokens(result.invalidTokens)
    }
  }

  dispose(): void {
    this.timers.forEach((timer) => clearTimeout(timer))
    this.timers.clear()
    this.pendingByPairSession.clear()
  }

  private async activePeerTokens(pairSessionId: string, actorUserId: string): Promise<string[]> {
    const rows = await this.db.query<{ token: string }>(
      `
      SELECT dt.token
      FROM pair_sessions ps
      JOIN device_tokens dt
        ON dt.user_id = CASE
          WHEN ps.user_one_id = $2 THEN ps.user_two_id
          ELSE ps.user_one_id
        END
      WHERE ps.id = $1
        AND ($2 = ps.user_one_id OR $2 = ps.user_two_id)
        AND dt.revoked_at IS NULL
      `,
      [pairSessionId, actorUserId],
    )
    return rows.rows.map((row) => row.token)
  }

  private async revokeTokens(tokens: string[]): Promise<void> {
    for (const token of tokens) {
      await this.db.query(
        `
        UPDATE device_tokens
        SET revoked_at = NOW()
        WHERE token = $1
        `,
        [token],
      )
    }
  }
}

function isPermanentTokenError(code: string | undefined): boolean {
  return code === "messaging/invalid-registration-token" ||
    code === "messaging/registration-token-not-registered"
}
