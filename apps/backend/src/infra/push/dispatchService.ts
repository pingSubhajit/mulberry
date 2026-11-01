import type { Database } from "../db/database.js"
import type { DeviceTokenRow } from "../../contracts/dbRecords.js"
import type { ReactionType } from "../../contracts/reactions.js"
import type { PushRecipients, PushSendResult, PushSender, ReactionActivityContentState } from "./types.js"

type DeviceTokenTarget = Pick<DeviceTokenRow, "token" | "platform">

type PairDeviceTarget = Pick<DeviceTokenRow, "user_id" | "token" | "platform" | "sign_in_id">

/** Who changed the canvas, and from which signed-in device. */
export interface CanvasEditor {
  userId: string
  signInId: string
}

type LiveActivityDevice = Pick<DeviceTokenRow, "user_id" | "token" | "live_activity_start_token" | "live_activity_token">

export interface ReactionCounts {
  heartCount: number
  hugCount: number
  kissCount: number
  smileCount: number
  laughCount: number
  sparkleCount: number
}

const REACTION_COUNT_KEYS: Record<ReactionType, keyof ReactionCounts> = {
  HEART: "heartCount",
  HUG: "hugCount",
  KISS: "kissCount",
  SMILE: "smileCount",
  LAUGH: "laughCount",
  SPARKLE: "sparkleCount",
}

/** As the iPhone words it when it shows the activity itself: "Sent you love ❤️". */
const REACTION_WORDS: Record<ReactionType, string> = {
  HEART: "love ❤️",
  HUG: "hugs 🤗",
  KISS: "kisses 💋",
  SMILE: "smiles 😊",
  LAUGH: "laughs 😂",
  SPARKLE: "sparkles ✨",
}

/** Only the kinds that arrived, as the iPhone keys them. */
function reactionActivityContent(latest: ReactionType, counts: ReactionCounts): ReactionActivityContentState {
  const arrived: ReactionActivityContentState["counts"] = {}
  for (const [type, key] of Object.entries(REACTION_COUNT_KEYS) as [ReactionType, keyof ReactionCounts][]) {
    if (counts[key] > 0) arrived[type] = counts[key]
  }
  return { counts: arrived, latest }
}

function pushRecipients(rows: DeviceTokenTarget[]): PushRecipients {
  return {
    tokens: rows.map((row) => row.token),
    macTokens: rows.filter((row) => row.platform === "MACOS").map((row) => row.token),
  }
}

interface PendingCanvasUpdate {
  pairSessionId: string
  actorUserId: string
  /** Every device that changed the canvas since the last push, by user: they already have it. */
  editors: Map<string, Set<string>>
  latestRevision: number
  snapshotRevision: number
}

/** A canvas change goes to every device in the pair except those that made it. An editor's own
 * other devices hear of it only if they are Apple devices whose sign-in is known: Android treats any
 * edit by its own account as one it made itself, and a token not yet registered under a sign-in
 * may belong to the very device that drew. */
function canvasUpdateRecipients(rows: PairDeviceTarget[], editors: Map<string, Set<string>>): PushRecipients {
  return pushRecipients(
    rows.filter((row) => {
      const editingDevices = editors.get(row.user_id)
      if (!editingDevices) return true
      return row.platform !== "ANDROID" && row.sign_in_id !== null && !editingDevices.has(row.sign_in_id)
    }),
  )
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
const DEFAULT_PARTNER_VISIBILITY_TTL_MS = 24 * 60 * 60 * 1_000
const DEFAULT_REACTION_TTL_MS = 24 * 60 * 60 * 1_000

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
  private readonly partnerVisibilityTtlMs: number
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
    this.partnerVisibilityTtlMs = DEFAULT_PARTNER_VISIBILITY_TTL_MS

    this.nudgePoller = setInterval(() => {
      void this.flushDueNudges()
    }, this.canvasNudgePollIntervalMs)

    this.drawReminderPoller = setInterval(() => {
      void this.flushDueDrawReminders()
    }, this.drawReminderPollIntervalMs)
  }

  enqueueCanvasUpdated(
    pairSessionId: string,
    editor: CanvasEditor,
    latestRevision: number,
    snapshotRevision = latestRevision,
  ): void {
    const editors = this.pendingByPairSession.get(pairSessionId)?.editors ?? new Map<string, Set<string>>()
    editors.set(editor.userId, (editors.get(editor.userId) ?? new Set<string>()).add(editor.signInId))
    this.pendingByPairSession.set(pairSessionId, {
      pairSessionId,
      actorUserId: editor.userId,
      editors,
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

  enqueuePairingDisconnected(
    pairSessionId: string,
    recipientUserId: string,
    actorUserId: string,
    actorDisplayName: string,
  ): void {
    void this.sendPairingDisconnected(pairSessionId, recipientUserId, actorUserId, actorDisplayName)
  }

  enqueueReaction(
    pairSessionId: string,
    actorUserId: string,
    generation: number,
    latest: ReactionType,
    counts: ReactionCounts,
  ): void {
    void this.sendReaction(pairSessionId, actorUserId, generation, counts).then(() =>
      this.showReactionLiveActivities(pairSessionId, actorUserId, latest, counts),
    )
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

  enqueuePartnerVisibilityChanged(
    pairSessionId: string,
    actorUserId: string,
    actorDisplayName: string,
    canSeeLatestDrawings: boolean,
    details: {
      wallpaperSyncEnabled: boolean
      wallpaperSelectedOnHome: boolean
      wallpaperSelectedOnLock: boolean
    },
  ): void {
    void this.sendPartnerVisibilityChanged(
      pairSessionId,
      actorUserId,
      actorDisplayName,
      canSeeLatestDrawings,
      details,
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

    const { tokens, macTokens } = await this.canvasUpdateTokens(pending.pairSessionId, pending.editors)
    if (tokens.length === 0) {
      console.info("[push] no devices to tell of a canvas update", {
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
        macTokens,
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
    const { tokens, macTokens } = await this.activePeerTokens(pairSessionId, actorUserId)
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
          macTokens,
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

  private async sendPairingDisconnected(
    pairSessionId: string,
    recipientUserId: string,
    actorUserId: string,
    actorDisplayName: string,
  ): Promise<void> {
    const { tokens, macTokens } = await this.activeUserTokens(recipientUserId)
    if (tokens.length === 0) {
      console.info("[push] no active tokens for pairing disconnected", {
        pairSessionId,
        recipientUserId,
        actorUserId,
      })
      return
    }

    console.info("[push] sending pairing disconnected", {
      pairSessionId,
      recipientUserId,
      actorUserId,
      tokenCount: tokens.length,
    })

    let result: PushSendResult
    try {
      result = await this.sender.send({
        tokens,
          macTokens,
        data: {
          type: "PAIRING_DISCONNECTED",
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
      console.error("[push] pairing disconnected send failed", {
        pairSessionId,
        recipientUserId,
        actorUserId,
        error: error instanceof Error ? error.message : String(error),
      })
      return
    }

    console.info("[push] pairing disconnected sent", {
      pairSessionId,
      invalidTokenCount: result.invalidTokens.length,
    })

    if (result.invalidTokens.length > 0) {
      await this.revokeTokens(result.invalidTokens)
    }
  }

  private async sendPartnerVisibilityChanged(
    pairSessionId: string,
    actorUserId: string,
    actorDisplayName: string,
    canSeeLatestDrawings: boolean,
    details: {
      wallpaperSyncEnabled: boolean
      wallpaperSelectedOnHome: boolean
      wallpaperSelectedOnLock: boolean
    },
  ): Promise<void> {
    const { tokens, macTokens } = await this.activePeerTokens(pairSessionId, actorUserId)
    if (tokens.length === 0) {
      console.info("[push] no active peer tokens for partner visibility", {
        pairSessionId,
        actorUserId,
      })
      return
    }

    console.info("[push] sending partner visibility", {
      pairSessionId,
      actorUserId,
      canSeeLatestDrawings,
      tokenCount: tokens.length,
    })

    let result: PushSendResult
    try {
      result = await this.sender.send({
        tokens,
          macTokens,
        data: {
          type: "PARTNER_VISIBILITY_CHANGED",
          pairSessionId,
          actorUserId,
          actorDisplayName,
          canSeeLatestDrawings: String(canSeeLatestDrawings),
          wallpaperSyncEnabled: String(details.wallpaperSyncEnabled),
          wallpaperSelectedOnHome: String(details.wallpaperSelectedOnHome),
          wallpaperSelectedOnLock: String(details.wallpaperSelectedOnLock),
        },
        android: {
          priority: "high",
          collapseKey: `partner-visibility-${pairSessionId}`,
          ttlMs: this.partnerVisibilityTtlMs,
        },
      })
    } catch (error) {
      console.error("[push] partner visibility send failed", {
        pairSessionId,
        actorUserId,
        error: error instanceof Error ? error.message : String(error),
      })
      return
    }

    console.info("[push] partner visibility sent", {
      pairSessionId,
      invalidTokenCount: result.invalidTokens.length,
    })

    if (result.invalidTokens.length > 0) {
      await this.revokeTokens(result.invalidTokens)
    }
  }

  private async sendReaction(
    pairSessionId: string,
    actorUserId: string,
    generation: number,
    counts: ReactionCounts,
  ): Promise<void> {
    const { tokens, macTokens } = await this.activePeerTokens(pairSessionId, actorUserId)
    const totalCount =
      counts.heartCount +
      counts.hugCount +
      counts.kissCount +
      counts.smileCount +
      counts.laughCount +
      counts.sparkleCount
    if (tokens.length === 0) {
      console.info("[push] no active peer tokens for reaction", {
        pairSessionId,
        actorUserId,
      })
      return
    }

    console.info("[push] sending reaction", {
      pairSessionId,
      actorUserId,
      generation,
      tokenCount: tokens.length,
    })

    let result: PushSendResult
    try {
      result = await this.sender.send({
        tokens,
          macTokens,
        data: {
          type: "REACTION",
          pairSessionId,
          generation: String(generation),
          heartCount: String(counts.heartCount),
          hugCount: String(counts.hugCount),
          kissCount: String(counts.kissCount),
          smileCount: String(counts.smileCount),
          laughCount: String(counts.laughCount),
          sparkleCount: String(counts.sparkleCount),
        },
        android: {
          priority: "high",
          collapseKey: `reaction-${pairSessionId}-${generation}-${totalCount}`,
          ttlMs: DEFAULT_REACTION_TTL_MS,
        },
      })
    } catch (error) {
      console.error("[push] reaction send failed", {
        pairSessionId,
        actorUserId,
        error: error instanceof Error ? error.message : String(error),
      })
      return
    }

    console.info("[push] reaction sent", {
      pairSessionId,
      invalidTokenCount: result.invalidTokens.length,
    })

    if (result.invalidTokens.length > 0) {
      await this.revokeTokens(result.invalidTokens)
    }
  }

  /** The batch in each of the recipient's iPhones' Dynamic Island and Lock Screen: the activity on
   * screen is updated, or one is started. An update that is refused means that activity has
   * ended, so a new one starts. */
  private async showReactionLiveActivities(
    pairSessionId: string,
    actorUserId: string,
    latest: ReactionType,
    counts: ReactionCounts,
  ): Promise<void> {
    try {
      const devices = await this.db.query<LiveActivityDevice>(
        `
        SELECT dt.user_id, dt.token, dt.live_activity_start_token, dt.live_activity_token
        FROM pair_sessions ps
        JOIN device_tokens dt
          ON dt.user_id = CASE
            WHEN ps.user_one_id = $2 THEN ps.user_two_id
            ELSE ps.user_one_id
          END
        WHERE ps.id = $1
          AND ($2 = ps.user_one_id OR $2 = ps.user_two_id)
          AND dt.platform = 'IOS'
          AND dt.revoked_at IS NULL
          AND (dt.live_activity_start_token IS NOT NULL OR dt.live_activity_token IS NOT NULL)
        `,
        [pairSessionId, actorUserId],
      )
      if (devices.rows.length === 0) return
      const partnerName = await this.lookupPartnerDisplayName(devices.rows[0].user_id)
      const content = {
        partnerName,
        contentState: reactionActivityContent(latest, counts),
        alert: { title: partnerName, body: `Sent you ${REACTION_WORDS[latest]}` },
      }
      for (const device of devices.rows) {
        if (device.live_activity_token) {
          const updated = await this.sender.sendLiveActivity({
            ...content,
            token: device.token,
            liveActivityToken: device.live_activity_token,
            event: "update",
          })
          if (updated) continue
          await this.db.query(
            `UPDATE device_tokens SET live_activity_token = NULL WHERE token = $1 AND live_activity_token = $2`,
            [device.token, device.live_activity_token],
          )
        }
        if (device.live_activity_start_token) {
          await this.sender.sendLiveActivity({
            ...content,
            token: device.token,
            liveActivityToken: device.live_activity_start_token,
            event: "start",
          })
        }
      }
    } catch (error) {
      console.error("[push] reaction live activity failed", {
        pairSessionId,
        actorUserId,
        error: error instanceof Error ? error.message : String(error),
      })
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

  private async activePeerTokens(pairSessionId: string, actorUserId: string): Promise<PushRecipients> {
    const rows = await this.db.query<DeviceTokenTarget>(
      `
      SELECT dt.token, dt.platform
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
    return pushRecipients(rows.rows)
  }

  private async canvasUpdateTokens(
    pairSessionId: string,
    editors: Map<string, Set<string>>,
  ): Promise<PushRecipients> {
    const rows = await this.db.query<PairDeviceTarget>(
      `
      SELECT dt.user_id, dt.token, dt.platform, dt.sign_in_id
      FROM pair_sessions ps
      JOIN device_tokens dt
        ON dt.user_id = ps.user_one_id OR dt.user_id = ps.user_two_id
      WHERE ps.id = $1
        AND dt.revoked_at IS NULL
      `,
      [pairSessionId],
    )
    return canvasUpdateRecipients(rows.rows, editors)
  }

  private async activeUserTokens(userId: string): Promise<PushRecipients> {
    const rows = await this.db.query<DeviceTokenTarget>(
      `
      SELECT token, platform
      FROM device_tokens
      WHERE user_id = $1
        AND revoked_at IS NULL
      `,
      [userId],
    )
    return pushRecipients(rows.rows)
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

        const { tokens, macTokens } = await this.activePeerTokens(pairSessionId, actorUserId)
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
            macTokens,
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

        const { tokens, macTokens } = await this.activeUserTokens(userId)
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
            macTokens,
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
