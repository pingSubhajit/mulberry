import { randomUUID } from "node:crypto"
import type { Database } from "../../infra/db/database.js"
import { HttpError } from "../../infra/http/HttpError.js"
import type { PushDispatchService } from "../../infra/push/dispatchService.js"
import { disabledAnalytics, type Analytics } from "../../infra/analytics/recorder.js"
import { recordPairActivity } from "../_shared/pairActivity.js"
import { requireSessionWithPair } from "../_shared/session.js"
import { ReactionTypes, type ReactionType } from "../../contracts/reactions.js"

const REACTION_SEND_RATE_LIMIT_CAPACITY = 6
const REACTION_SEND_RATE_LIMIT_REFILL_TOKENS_PER_SEC = 1

type NormalizedReactionType = ReactionType

interface PendingReactionBatch {
  generation: number
  heartCount: number
  hugCount: number
  kissCount: number
  smileCount: number
  laughCount: number
  sparkleCount: number
}

export class ReactionsService {
  constructor(
    private readonly db: Database,
    private readonly pushDispatchService?: PushDispatchService,
    private readonly analytics: Analytics = disabledAnalytics,
  ) {}

  async sendReaction(
    accessToken: string,
    reactionType: string,
  ): Promise<{
    ok: true
    generation: number
    heartCount: number
    hugCount: number
    kissCount: number
    smileCount: number
    laughCount: number
    sparkleCount: number
  }> {
    const { pairSession, ...context } = await requireSessionWithPair(this.db, accessToken)
    if (!pairSession) {
      throw new HttpError(400, "User is not paired", { code: "NOT_PAIRED" })
    }

    const recipientUserId = pairSession.user_one_id === context.user.id
      ? pairSession.user_two_id
      : pairSession.user_one_id
    const normalizedType = normalizeReactionType(reactionType)
    if (!normalizedType) {
      throw new HttpError(400, "reactionType must be one of HEART, HUG, KISS, SMILE, LAUGH, SPARKLE")
    }

    const updated = await this.db.transaction(async (tx) => {
      await this.enforceReactionSendRateLimit(tx, {
        actorUserId: context.user.id,
        pairSessionId: pairSession.id,
      })
      await tx.query(
        `
        INSERT INTO reaction_events (id, pair_session_id, sender_user_id, reaction_type)
        VALUES ($1, $2, $3, $4)
        `,
        [randomUUID(), pairSession.id, context.user.id, normalizedType],
      )
      const occurredAt = new Date()
      await this.analytics.track(tx, {
        event: "mulberry_reaction_sent",
        distinctId: context.user.id,
        pairId: pairSession.id,
        occurredAt,
        properties: { reaction_type: normalizedType, canvas_stroke_render_mode: pairSession.canvas_stroke_render_mode },
      })
      await recordPairActivity(tx, this.analytics, {
        pairSession,
        actorUserId: context.user.id,
        occurredAt,
        activitySource: "reaction",
      })
      return this.appendReactionForRecipient(tx, {
        pairSessionId: pairSession.id,
        recipientUserId,
        reactionType: normalizedType,
      })
    })

    this.pushDispatchService?.enqueueReaction(pairSession.id, context.user.id, updated.generation, normalizedType, {
      heartCount: updated.heartCount,
      hugCount: updated.hugCount,
      kissCount: updated.kissCount,
      smileCount: updated.smileCount,
      laughCount: updated.laughCount,
      sparkleCount: updated.sparkleCount,
    })

    return { ok: true, ...updated }
  }

  /**
   * The recipient's unplayed batch, for clients that cannot rely on the reaction push arriving
   * (iOS delivers it only while Mulberry is open). Playback still goes through lease and confirm.
   */
  async pendingReactions(accessToken: string): Promise<{ pending: PendingReactionBatch | null }> {
    const { pairSession, ...context } = await requireSessionWithPair(this.db, accessToken)
    if (!pairSession) {
      throw new HttpError(400, "User is not paired", { code: "NOT_PAIRED" })
    }

    const rows = await this.db.query<{
      pair_session_id: string | null
      generation: number
      heart_count: number
      hug_count: number
      kiss_count: number
      smile_count: number
      laugh_count: number
      sparkle_count: number
    }>(
      `
      SELECT
        pair_session_id,
        generation,
        heart_count,
        hug_count,
        kiss_count,
        smile_count,
        laugh_count,
        sparkle_count
      FROM reaction_inboxes
      WHERE recipient_user_id = $1
      `,
      [context.user.id],
    )
    const row = rows.rows[0]
    if (!row || (row.pair_session_id ?? "") !== pairSession.id) {
      return { pending: null }
    }

    const batch: PendingReactionBatch = {
      generation: Number(row.generation) || 0,
      heartCount: Number(row.heart_count) || 0,
      hugCount: Number(row.hug_count) || 0,
      kissCount: Number(row.kiss_count) || 0,
      smileCount: Number(row.smile_count) || 0,
      laughCount: Number(row.laugh_count) || 0,
      sparkleCount: Number(row.sparkle_count) || 0,
    }
    const total = batch.heartCount + batch.hugCount + batch.kissCount + batch.smileCount + batch.laughCount + batch.sparkleCount
    return { pending: total > 0 && batch.generation > 0 ? batch : null }
  }

  async leaseReactionPlayback(
    accessToken: string,
    request: { generation?: unknown; deviceId?: unknown },
  ): Promise<{
    status: "CLAIMED" | "NO_PENDING" | "LEASED_BY_OTHER" | "STALE_GENERATION"
    leaseExpiresAt?: string
  }> {
    const { pairSession, ...context } = await requireSessionWithPair(this.db, accessToken)
    if (!pairSession) {
      throw new HttpError(400, "User is not paired", { code: "NOT_PAIRED" })
    }
    const generation = Number(request.generation)
    if (!Number.isFinite(generation) || generation <= 0) {
      throw new HttpError(400, "generation must be a positive number")
    }
    const deviceId = typeof request.deviceId === "string" ? request.deviceId.trim() : ""
    if (!deviceId) {
      throw new HttpError(400, "deviceId is required")
    }

    const result = await this.db.transaction(async (tx) => {
      return this.leaseReactionForRecipient(tx, {
        recipientUserId: context.user.id,
        pairSessionId: pairSession.id,
        generation,
        deviceId,
        leaseMs: 30_000,
      })
    })

    return result.status === "CLAIMED"
      ? { status: "CLAIMED", leaseExpiresAt: result.leaseExpiresAt }
      : { status: result.status }
  }

  async confirmReactionPlayback(
    accessToken: string,
    request: { generation?: unknown; deviceId?: unknown },
  ): Promise<{ ok: true }> {
    const { pairSession, ...context } = await requireSessionWithPair(this.db, accessToken)
    if (!pairSession) {
      throw new HttpError(400, "User is not paired", { code: "NOT_PAIRED" })
    }
    const generation = Number(request.generation)
    if (!Number.isFinite(generation) || generation <= 0) {
      throw new HttpError(400, "generation must be a positive number")
    }
    const deviceId = typeof request.deviceId === "string" ? request.deviceId.trim() : ""
    if (!deviceId) {
      throw new HttpError(400, "deviceId is required")
    }

    await this.db.transaction(async (tx) => {
      await this.confirmReactionForRecipient(tx, {
        recipientUserId: context.user.id,
        pairSessionId: pairSession.id,
        generation,
        deviceId,
      })
    })
    return { ok: true }
  }

  private async enforceReactionSendRateLimit(
    tx: Pick<Database, "query">,
    input: { actorUserId: string; pairSessionId: string },
  ): Promise<void> {
    const nowMs = Date.now()
    const rows = await tx.query<{
      available_tokens: number
      last_refill_at_ms: number
    }>(
      `
      SELECT available_tokens, last_refill_at_ms
      FROM reaction_send_rate_limits
      WHERE actor_user_id = $1 AND pair_session_id = $2
      FOR UPDATE
      `,
      [input.actorUserId, input.pairSessionId],
    )

    const existing = rows.rows[0]
    if (!existing) {
      await tx.query(
        `
        INSERT INTO reaction_send_rate_limits (
          actor_user_id,
          pair_session_id,
          available_tokens,
          last_refill_at_ms,
          updated_at
        )
        VALUES ($1, $2, $3, $4, NOW())
        `,
        [
          input.actorUserId,
          input.pairSessionId,
          REACTION_SEND_RATE_LIMIT_CAPACITY - 1,
          nowMs,
        ],
      )
      return
    }

    const lastRefillAtMs = Number(existing.last_refill_at_ms) || nowMs
    const elapsedSec = Math.max(0, (nowMs - lastRefillAtMs) / 1000)
    let tokens = Number(existing.available_tokens) || 0
    tokens = Math.min(
      REACTION_SEND_RATE_LIMIT_CAPACITY,
      tokens + elapsedSec * REACTION_SEND_RATE_LIMIT_REFILL_TOKENS_PER_SEC,
    )

    if (tokens < 1) {
      await tx.query(
        `
        UPDATE reaction_send_rate_limits
        SET available_tokens = $3,
          last_refill_at_ms = $4,
          updated_at = NOW()
        WHERE actor_user_id = $1 AND pair_session_id = $2
        `,
        [input.actorUserId, input.pairSessionId, tokens, nowMs],
      )
      throw new HttpError(429, "Too many reactions sent; try again in a moment", { code: "TOO_MANY_REACTIONS" })
    }

    tokens -= 1
    await tx.query(
      `
      UPDATE reaction_send_rate_limits
      SET available_tokens = $3,
        last_refill_at_ms = $4,
        updated_at = NOW()
      WHERE actor_user_id = $1 AND pair_session_id = $2
      `,
      [input.actorUserId, input.pairSessionId, tokens, nowMs],
    )
  }

  /**
   * Adds the reaction to the recipient's inbox in one statement. A new batch starts, with a new
   * generation and no lease, when the inbox is empty, belongs to another pair, or is being played.
   */
  private async appendReactionForRecipient(
    tx: Pick<Database, "query">,
    input: {
      pairSessionId: string
      recipientUserId: string
      reactionType: NormalizedReactionType
    },
  ): Promise<{
    generation: number
    heartCount: number
    hugCount: number
    kissCount: number
    smileCount: number
    laughCount: number
    sparkleCount: number
  }> {
    const startsNewBatch = `(
      COALESCE(reaction_inboxes.pair_session_id, '') <> $2
      OR reaction_inboxes.heart_count + reaction_inboxes.hug_count + reaction_inboxes.kiss_count
        + reaction_inboxes.smile_count + reaction_inboxes.laugh_count + reaction_inboxes.sparkle_count = 0
      OR (reaction_inboxes.lease_expires_at IS NOT NULL AND reaction_inboxes.lease_expires_at > $9::timestamptz)
    )`
    const count = (column: string, parameter: string) =>
      `${column} = (CASE WHEN ${startsNewBatch} THEN 0 ELSE reaction_inboxes.${column} END) + ${parameter}`
    const rows = await tx.query<{
      generation: string | number
      heart_count: number
      hug_count: number
      kiss_count: number
      smile_count: number
      laugh_count: number
      sparkle_count: number
    }>(
      `
      INSERT INTO reaction_inboxes (
        recipient_user_id,
        pair_session_id,
        generation,
        heart_count,
        hug_count,
        kiss_count,
        smile_count,
        laugh_count,
        sparkle_count,
        leased_by_device_id,
        lease_expires_at,
        lease_generation,
        consumed_generation,
        updated_at
      )
      VALUES ($1, $2, 1, $3, $4, $5, $6, $7, $8, NULL, NULL, NULL, 0, NOW())
      ON CONFLICT (recipient_user_id) DO UPDATE SET
        pair_session_id = $2,
        generation = (CASE WHEN reaction_inboxes.generation > 0 THEN reaction_inboxes.generation ELSE 1 END)
          + (CASE WHEN ${startsNewBatch} THEN 1 ELSE 0 END),
        ${count("heart_count", "$3")},
        ${count("hug_count", "$4")},
        ${count("kiss_count", "$5")},
        ${count("smile_count", "$6")},
        ${count("laugh_count", "$7")},
        ${count("sparkle_count", "$8")},
        leased_by_device_id = CASE WHEN ${startsNewBatch} THEN NULL ELSE reaction_inboxes.leased_by_device_id END,
        lease_expires_at = CASE WHEN ${startsNewBatch} THEN NULL ELSE reaction_inboxes.lease_expires_at END,
        lease_generation = CASE WHEN ${startsNewBatch} THEN NULL ELSE reaction_inboxes.lease_generation END,
        updated_at = NOW()
      RETURNING generation, heart_count, hug_count, kiss_count, smile_count, laugh_count, sparkle_count
      `,
      [
        input.recipientUserId,
        input.pairSessionId,
        input.reactionType === "HEART" ? 1 : 0,
        input.reactionType === "HUG" ? 1 : 0,
        input.reactionType === "KISS" ? 1 : 0,
        input.reactionType === "SMILE" ? 1 : 0,
        input.reactionType === "LAUGH" ? 1 : 0,
        input.reactionType === "SPARKLE" ? 1 : 0,
        new Date().toISOString(),
      ],
    )
    const row = rows.rows[0]
    return {
      generation: Number(row.generation) || 1,
      heartCount: Number(row.heart_count) || 0,
      hugCount: Number(row.hug_count) || 0,
      kissCount: Number(row.kiss_count) || 0,
      smileCount: Number(row.smile_count) || 0,
      laughCount: Number(row.laugh_count) || 0,
      sparkleCount: Number(row.sparkle_count) || 0,
    }
  }

  private async leaseReactionForRecipient(
    tx: Pick<Database, "query">,
    input: {
      recipientUserId: string
      pairSessionId: string
      generation: number
      deviceId: string
      leaseMs: number
    },
  ): Promise<
    | { status: "CLAIMED"; leaseExpiresAt: string }
    | { status: "NO_PENDING" | "LEASED_BY_OTHER" | "STALE_GENERATION" }
  > {
    const rows = await tx.query<{
      pair_session_id: string | null
      generation: number
      heart_count: number
      hug_count: number
      kiss_count: number
      smile_count: number
      laugh_count: number
      sparkle_count: number
      leased_by_device_id: string | null
      lease_expires_at: string | null
      lease_generation: number | null
      consumed_generation: number
    }>(
      `
      SELECT
        pair_session_id,
        generation,
        heart_count,
        hug_count,
        kiss_count,
        smile_count,
        laugh_count,
        sparkle_count,
        leased_by_device_id,
        lease_expires_at,
        lease_generation,
        consumed_generation
      FROM reaction_inboxes
      WHERE recipient_user_id = $1
      FOR UPDATE
      `,
      [input.recipientUserId],
    )
    const row = rows.rows[0]
    if (!row || (row.pair_session_id ?? "") !== input.pairSessionId) {
      return { status: "NO_PENDING" }
    }

    const heart = Number(row.heart_count) || 0
    const hug = Number(row.hug_count) || 0
    const kiss = Number(row.kiss_count) || 0
    const smile = Number(row.smile_count) || 0
    const laugh = Number(row.laugh_count) || 0
    const sparkle = Number(row.sparkle_count) || 0
    const total = heart + hug + kiss + smile + laugh + sparkle
    if (total <= 0) {
      return { status: "NO_PENDING" }
    }

    const currentGeneration = Number(row.generation) || 0
    if (currentGeneration !== input.generation) {
      return { status: "STALE_GENERATION" }
    }

    const now = new Date()
    const leaseExpiresAt = row.lease_expires_at ? new Date(row.lease_expires_at) : null
    const leaseActive = leaseExpiresAt !== null && leaseExpiresAt.getTime() > now.getTime()
    if (leaseActive) {
      if (
        row.leased_by_device_id === input.deviceId &&
        Number(row.lease_generation ?? 0) === input.generation
      ) {
        return { status: "CLAIMED", leaseExpiresAt: leaseExpiresAt.toISOString() }
      }
      return { status: "LEASED_BY_OTHER" }
    }

    const nextLeaseExpiry = new Date(now.getTime() + Math.max(1_000, input.leaseMs))
    await tx.query(
      `
      UPDATE reaction_inboxes
      SET leased_by_device_id = $2,
        lease_generation = $3,
        lease_expires_at = $4,
        updated_at = NOW()
      WHERE recipient_user_id = $1
      `,
      [input.recipientUserId, input.deviceId, input.generation, nextLeaseExpiry.toISOString()],
    )
    return { status: "CLAIMED", leaseExpiresAt: nextLeaseExpiry.toISOString() }
  }

  private async confirmReactionForRecipient(
    tx: Pick<Database, "query">,
    input: {
      recipientUserId: string
      pairSessionId: string
      generation: number
      deviceId: string
    },
  ): Promise<void> {
    const rows = await tx.query<{
      pair_session_id: string | null
      generation: number
      leased_by_device_id: string | null
      lease_expires_at: string | null
      lease_generation: number | null
      consumed_generation: number
    }>(
      `
      SELECT
        pair_session_id,
        generation,
        leased_by_device_id,
        lease_expires_at,
        lease_generation,
        consumed_generation
      FROM reaction_inboxes
      WHERE recipient_user_id = $1
      FOR UPDATE
      `,
      [input.recipientUserId],
    )
    const row = rows.rows[0]
    if (!row || (row.pair_session_id ?? "") !== input.pairSessionId) return

    const currentGeneration = Number(row.generation) || 0
    if (currentGeneration !== input.generation) return

    const now = new Date()
    const leaseExpiresAt = row.lease_expires_at ? new Date(row.lease_expires_at) : null
    const leaseActive = leaseExpiresAt !== null && leaseExpiresAt.getTime() > now.getTime()
    if (!leaseActive) return
    if (row.leased_by_device_id !== input.deviceId) return
    if (Number(row.lease_generation ?? 0) !== input.generation) return

    await tx.query(
      `
      UPDATE reaction_inboxes
      SET heart_count = 0,
        hug_count = 0,
        kiss_count = 0,
        smile_count = 0,
        laugh_count = 0,
        sparkle_count = 0,
        leased_by_device_id = NULL,
        lease_expires_at = NULL,
        lease_generation = NULL,
        consumed_generation = $2,
        updated_at = NOW()
      WHERE recipient_user_id = $1
      `,
      [input.recipientUserId, input.generation],
    )
  }
}

function normalizeReactionType(raw: string): NormalizedReactionType | null {
  const value = raw.trim().toUpperCase()
  return ReactionTypes.find((type) => type === value) ?? null
}
