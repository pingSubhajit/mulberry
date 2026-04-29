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

export interface DrawReminderPushPayload {
  type: "DRAW_REMINDER"
  pairSessionId: string
  partnerDisplayName: string
  reminderCount: string
}

export type MulberryPushPayload =
  | CanvasUpdatedPushPayload
  | CanvasNudgePushPayload
  | PairingConfirmedPushPayload
  | DrawReminderPushPayload

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
  canvasUpdateTtlMs?: number
  pairingConfirmationTtlMs?: number
  canvasNudgeDelayMs?: number
  canvasNudgePollIntervalMs?: number
  canvasNudgeTtlMs?: number
  drawReminderBaseDelayMs?: number
  drawReminderPollIntervalMs?: number
  drawReminderTtlMs?: number
  drawReminderMaxBackoffDays?: number
}

const DEFAULT_CANVAS_UPDATE_TTL_MS = 24 * 60 * 60 * 1_000
const DEFAULT_PAIRING_CONFIRMATION_TTL_MS = 60_000
const DEFAULT_CANVAS_NUDGE_DELAY_MS = 5 * 60 * 1_000
const DEFAULT_CANVAS_NUDGE_POLL_INTERVAL_MS = 30_000
const DEFAULT_CANVAS_NUDGE_TTL_MS = 24 * 60 * 60 * 1_000
const DEFAULT_DRAW_REMINDER_BASE_DELAY_MS = 24 * 60 * 60 * 1_000
const DEFAULT_DRAW_REMINDER_POLL_INTERVAL_MS = 30_000
const DEFAULT_DRAW_REMINDER_TTL_MS = 24 * 60 * 60 * 1_000
const DEFAULT_DRAW_REMINDER_MAX_BACKOFF_DAYS = 7

export class PushDispatchService {
  private readonly pendingByPairSession = new Map<string, PendingCanvasUpdate>()
  private readonly timers = new Map<string, ReturnType<typeof setTimeout>>()
  private readonly debounceMs: number
  private readonly canvasUpdateTtlMs: number
  private readonly pairingConfirmationTtlMs: number
  private readonly canvasNudgeDelayMs: number
  private readonly canvasNudgePollIntervalMs: number
  private readonly canvasNudgeTtlMs: number
  private readonly drawReminderBaseDelayMs: number
  private readonly drawReminderPollIntervalMs: number
  private readonly drawReminderTtlMs: number
  private readonly drawReminderMaxBackoffDays: number
  private nudgePoller: ReturnType<typeof setInterval> | null = null
  private nudgeFlushInProgress = false
  private drawReminderPoller: ReturnType<typeof setInterval> | null = null
  private drawReminderFlushInProgress = false

  constructor(
    private readonly db: Database,
    private readonly sender: PushSender,
    options: PushDispatchOptions = {},
  ) {
    this.debounceMs = options.debounceMs ?? 2_000
    this.canvasUpdateTtlMs = options.canvasUpdateTtlMs ?? DEFAULT_CANVAS_UPDATE_TTL_MS
    this.pairingConfirmationTtlMs =
      options.pairingConfirmationTtlMs ?? DEFAULT_PAIRING_CONFIRMATION_TTL_MS
    this.canvasNudgeDelayMs = options.canvasNudgeDelayMs ?? DEFAULT_CANVAS_NUDGE_DELAY_MS
    this.canvasNudgePollIntervalMs =
      options.canvasNudgePollIntervalMs ?? DEFAULT_CANVAS_NUDGE_POLL_INTERVAL_MS
    this.canvasNudgeTtlMs = options.canvasNudgeTtlMs ?? DEFAULT_CANVAS_NUDGE_TTL_MS
    this.drawReminderBaseDelayMs =
      options.drawReminderBaseDelayMs ?? DEFAULT_DRAW_REMINDER_BASE_DELAY_MS
    this.drawReminderPollIntervalMs =
      options.drawReminderPollIntervalMs ?? DEFAULT_DRAW_REMINDER_POLL_INTERVAL_MS
    this.drawReminderTtlMs = options.drawReminderTtlMs ?? DEFAULT_DRAW_REMINDER_TTL_MS
    this.drawReminderMaxBackoffDays = Math.max(
      1,
      Math.floor(options.drawReminderMaxBackoffDays ?? DEFAULT_DRAW_REMINDER_MAX_BACKOFF_DAYS),
    )

    this.nudgePoller = setInterval(() => {
      void this.flushDueNudges()
    }, this.canvasNudgePollIntervalMs)

    this.drawReminderPoller = setInterval(() => {
      void this.flushDueDrawReminders()
    }, this.drawReminderPollIntervalMs)
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

  enqueueCanvasNudge(
    pairSessionId: string,
    actorUserId: string,
    latestRevision: number,
  ): void {
    const dueAt = new Date(Date.now() + this.canvasNudgeDelayMs).toISOString()
    void this.db.query(
      `
      INSERT INTO canvas_nudges (pair_session_id, actor_user_id, latest_revision, due_at)
      VALUES ($1, $2, $3, $4)
      ON CONFLICT (pair_session_id)
      DO UPDATE SET
        actor_user_id = EXCLUDED.actor_user_id,
        latest_revision = EXCLUDED.latest_revision,
        due_at = EXCLUDED.due_at,
        updated_at = NOW()
      `,
      [pairSessionId, actorUserId, latestRevision, dueAt],
    )
  }

  initializePairDrawReminders(pairSessionId: string): void {
    void this.initializePairDrawRemindersInternal(pairSessionId)
  }

  recordUserDrew(pairSessionId: string, userId: string): void {
    const dueAt = new Date(Date.now() + this.drawReminderBaseDelayMs).toISOString()
    void this.db.query(
      `
      INSERT INTO draw_reminders (
        pair_session_id,
        user_id,
        last_draw_at,
        reminder_count,
        due_at
      )
      VALUES ($1, $2, NOW(), 0, $3)
      ON CONFLICT (pair_session_id, user_id)
      DO UPDATE SET
        last_draw_at = NOW(),
        reminder_count = 0,
        due_at = EXCLUDED.due_at,
        updated_at = NOW()
      `,
      [pairSessionId, userId, dueAt],
    )
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
          ttlMs: this.canvasUpdateTtlMs,
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
          ttlMs: this.pairingConfirmationTtlMs,
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
    if (this.nudgePoller) {
      clearInterval(this.nudgePoller)
      this.nudgePoller = null
    }
    if (this.drawReminderPoller) {
      clearInterval(this.drawReminderPoller)
      this.drawReminderPoller = null
    }
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

  private async activeUserTokens(userId: string): Promise<string[]> {
    const rows = await this.db.query<{ token: string }>(
      `
      SELECT token
      FROM device_tokens
      WHERE user_id = $1
        AND revoked_at IS NULL
      `,
      [userId],
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

  private async flushDueNudges(): Promise<void> {
    if (this.nudgeFlushInProgress) return
    this.nudgeFlushInProgress = true
    try {
      const due = await this.db.transaction(async (tx) => {
        let rows: { rows: Array<{
          pair_session_id: string
          actor_user_id: string
          latest_revision: string | number
        }> }
        try {
          rows = await tx.query(
            `
            SELECT pair_session_id, actor_user_id, latest_revision
            FROM canvas_nudges
            WHERE due_at <= NOW()
            ORDER BY due_at ASC
            LIMIT 20
            FOR UPDATE SKIP LOCKED
            `,
          )
        } catch {
          rows = await tx.query(
            `
            SELECT pair_session_id, actor_user_id, latest_revision
            FROM canvas_nudges
            WHERE due_at <= NOW()
            ORDER BY due_at ASC
            LIMIT 20
            FOR UPDATE
            `,
          )
        }
        if (rows.rows.length === 0) return []
        await tx.query(
          `
          DELETE FROM canvas_nudges
          WHERE pair_session_id = ANY($1)
          `,
          [rows.rows.map((row) => row.pair_session_id)],
        )
        return rows.rows
      })

      for (const item of due) {
        const pairSessionId = item.pair_session_id
        const actorUserId = item.actor_user_id
        const latestRevision = Number(item.latest_revision)
        if (!Number.isFinite(latestRevision) || latestRevision <= 0) continue

        const tokens = await this.activePeerTokens(pairSessionId, actorUserId)
        if (tokens.length === 0) {
          console.info("[push] no active peer tokens for canvas nudge", {
            pairSessionId,
            actorUserId,
            latestRevision,
          })
          continue
        }

        const actorDisplayName = await this.lookupActorDisplayName(actorUserId)
        console.info("[push] sending canvas nudge", {
          pairSessionId,
          actorUserId,
          latestRevision,
          tokenCount: tokens.length,
        })

        let result: PushSendResult
        try {
          result = await this.sender.send({
            tokens,
            data: {
              type: "CANVAS_NUDGE",
              pairSessionId,
              latestRevision: String(latestRevision),
              actorUserId,
              actorDisplayName,
            },
            android: {
              priority: "high",
              collapseKey: `canvas-nudge-${pairSessionId}`,
              ttlMs: this.canvasNudgeTtlMs,
            },
          })
        } catch (error) {
          console.error("[push] canvas nudge send failed", {
            pairSessionId,
            latestRevision,
            error: error instanceof Error ? error.message : String(error),
          })
          continue
        }

        console.info("[push] canvas nudge sent", {
          pairSessionId,
          latestRevision,
          invalidTokenCount: result.invalidTokens.length,
        })

        if (result.invalidTokens.length > 0) {
          await this.revokeTokens(result.invalidTokens)
        }
      }
    } finally {
      this.nudgeFlushInProgress = false
    }
  }

  private async lookupActorDisplayName(userId: string): Promise<string> {
    const rows = await this.db.query<{ display_name: string | null }>(
      `
      SELECT display_name
      FROM user_profiles
      WHERE user_id = $1
      LIMIT 1
      `,
      [userId],
    )
    return rows.rows[0]?.display_name?.trim() || "Your partner"
  }

  private async initializePairDrawRemindersInternal(pairSessionId: string): Promise<void> {
    const rows = await this.db.query<{
      user_one_id: string
      user_two_id: string
      created_at: string | Date
    }>(
      `
      SELECT user_one_id, user_two_id, created_at
      FROM pair_sessions
      WHERE id = $1
      LIMIT 1
      `,
      [pairSessionId],
    )
    const pair = rows.rows[0]
    if (!pair) return

    const createdAt = new Date(pair.created_at)
    const baseline = Number.isNaN(createdAt.getTime()) ? new Date() : createdAt
    const lastDrawAt = baseline.toISOString()
    const dueAt = new Date(baseline.getTime() + this.drawReminderBaseDelayMs).toISOString()

    await this.db.query(
      `
      INSERT INTO draw_reminders (
        pair_session_id,
        user_id,
        last_draw_at,
        reminder_count,
        due_at
      )
      VALUES ($1, $2, $3, 0, $4),
        ($1, $5, $3, 0, $4)
      ON CONFLICT (pair_session_id, user_id)
      DO UPDATE SET
        last_draw_at = EXCLUDED.last_draw_at,
        reminder_count = EXCLUDED.reminder_count,
        due_at = EXCLUDED.due_at,
        updated_at = NOW()
      `,
      [pairSessionId, pair.user_one_id, lastDrawAt, dueAt, pair.user_two_id],
    )
  }

  private async flushDueDrawReminders(): Promise<void> {
    if (this.drawReminderFlushInProgress) return
    this.drawReminderFlushInProgress = true
    try {
      const due = await this.db.transaction(async (tx) => {
        let rows: { rows: Array<{
          pair_session_id: string
          user_id: string
          reminder_count: number
        }> }
        try {
          rows = await tx.query(
            `
            SELECT pair_session_id, user_id, reminder_count
            FROM draw_reminders
            WHERE due_at <= NOW()
            ORDER BY due_at ASC
            LIMIT 20
            FOR UPDATE SKIP LOCKED
            `,
          )
        } catch {
          rows = await tx.query(
            `
            SELECT pair_session_id, user_id, reminder_count
            FROM draw_reminders
            WHERE due_at <= NOW()
            ORDER BY due_at ASC
            LIMIT 20
            FOR UPDATE
            `,
          )
        }
        if (rows.rows.length === 0) return []

        const leaseDueAt = new Date(Date.now() + 15 * 60 * 1_000).toISOString()
        for (const row of rows.rows) {
          await tx.query(
            `
            UPDATE draw_reminders
            SET due_at = $3, updated_at = NOW()
            WHERE pair_session_id = $1 AND user_id = $2
            `,
            [row.pair_session_id, row.user_id, leaseDueAt],
          )
        }
        return rows.rows
      })

      for (const item of due) {
        const pairSessionId = item.pair_session_id
        const userId = item.user_id
        const reminderCount = Number(item.reminder_count) || 0

        const tokens = await this.activeUserTokens(userId)
        if (tokens.length === 0) {
          console.info("[push] no active tokens for draw reminder", {
            pairSessionId,
            userId,
          })
          await this.rescheduleDrawReminder(pairSessionId, userId, reminderCount)
          continue
        }

        const partnerDisplayName = await this.lookupPartnerDisplayName(userId)
        console.info("[push] sending draw reminder", {
          pairSessionId,
          userId,
          reminderCount,
          tokenCount: tokens.length,
        })

        let result: PushSendResult
        try {
          result = await this.sender.send({
            tokens,
            data: {
              type: "DRAW_REMINDER",
              pairSessionId,
              partnerDisplayName,
              reminderCount: String(reminderCount),
            },
            android: {
              priority: "high",
              collapseKey: `draw-reminder-${pairSessionId}-${userId}`,
              ttlMs: this.drawReminderTtlMs,
            },
          })
        } catch (error) {
          console.error("[push] draw reminder send failed", {
            pairSessionId,
            userId,
            error: error instanceof Error ? error.message : String(error),
          })
          await this.rescheduleDrawReminder(pairSessionId, userId, reminderCount)
          continue
        }

        console.info("[push] draw reminder sent", {
          pairSessionId,
          userId,
          invalidTokenCount: result.invalidTokens.length,
        })

        if (result.invalidTokens.length > 0) {
          await this.revokeTokens(result.invalidTokens)
        }

        await this.rescheduleDrawReminder(pairSessionId, userId, reminderCount)
      }
    } finally {
      this.drawReminderFlushInProgress = false
    }
  }

  private async rescheduleDrawReminder(
    pairSessionId: string,
    userId: string,
    reminderCount: number,
  ): Promise<void> {
    const nextDays = Math.min(this.drawReminderMaxBackoffDays, 1 + Math.max(0, reminderCount + 1))
    const dueAt = new Date(Date.now() + nextDays * 24 * 60 * 60 * 1_000).toISOString()
    await this.db.query(
      `
      UPDATE draw_reminders
      SET reminder_count = reminder_count + 1,
        due_at = $3,
        updated_at = NOW()
      WHERE pair_session_id = $1 AND user_id = $2
      `,
      [pairSessionId, userId, dueAt],
    )
  }

  private async lookupPartnerDisplayName(userId: string): Promise<string> {
    const rows = await this.db.query<{ partner_display_name: string | null }>(
      `
      SELECT partner_display_name
      FROM user_profiles
      WHERE user_id = $1
      LIMIT 1
      `,
      [userId],
    )
    return rows.rows[0]?.partner_display_name?.trim() || "Your partner"
  }
}

function isPermanentTokenError(code: string | undefined): boolean {
  return code === "messaging/invalid-registration-token" ||
    code === "messaging/registration-token-not-registered"
}
