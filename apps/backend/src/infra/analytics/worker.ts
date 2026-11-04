import type { Database } from "../db/database.js"
import { PAIR_GROUP_TYPE, type OutboxOperation } from "./outbox.js"
import type { Analytics } from "./recorder.js"
import type { PostHogMessage, PostHogTransport } from "./transport.js"

export interface OutboxWorkerOptions {
  /** 0 leaves the worker idle until `flush` is called, as tests do. */
  pollIntervalMs?: number
  batchSize?: number
  inviteSweepIntervalMs?: number
}

interface OutboxRow {
  id: string
  payload_json: OutboxOperation | string
  occurred_at: string | Date
  attempts: number
}

const DEFAULT_POLL_INTERVAL_MS = 2_000
const DEFAULT_BATCH_SIZE = 100
const DEFAULT_INVITE_SWEEP_INTERVAL_MS = 10 * 60 * 1_000
/** How long a claimed row is left to its worker before another may take it. */
const CLAIM_SECONDS = 120
const MAX_ATTEMPTS = 40
const MAX_BACKOFF_MS = 60 * 60 * 1_000

/**
 * Sends the analytics outbox to PostHog. Rows leave the table only once PostHog has taken them,
 * and failures wait and retry, so a restart or an outage loses nothing. Each row's ID is its
 * PostHog `uuid`, so a row sent twice still counts once.
 */
export class AnalyticsOutboxWorker {
  private pollTimer: ReturnType<typeof setInterval> | null = null
  private sweepTimer: ReturnType<typeof setInterval> | null = null
  private running: Promise<number> | null = null

  constructor(
    private readonly db: Database,
    private readonly transport: PostHogTransport,
    private readonly analytics: Analytics,
    private readonly options: OutboxWorkerOptions = {},
  ) {}

  start(): void {
    const pollIntervalMs = this.options.pollIntervalMs ?? DEFAULT_POLL_INTERVAL_MS
    if (pollIntervalMs <= 0 || this.pollTimer) return
    this.pollTimer = setInterval(() => void this.flush().catch(logFailure("send")), pollIntervalMs)
    this.pollTimer.unref()
    const sweepMs = this.options.inviteSweepIntervalMs ?? DEFAULT_INVITE_SWEEP_INTERVAL_MS
    this.sweepTimer = setInterval(() => void this.sweepExpiredInvites().catch(logFailure("invite sweep")), sweepMs)
    this.sweepTimer.unref()
    void this.sweepExpiredInvites().catch(logFailure("invite sweep"))
  }

  stop(): void {
    if (this.pollTimer) clearInterval(this.pollTimer)
    if (this.sweepTimer) clearInterval(this.sweepTimer)
    this.pollTimer = null
    this.sweepTimer = null
  }

  /** Sends everything due, batch by batch, until the outbox is empty or a send fails. Returns rows sent. */
  async flush(): Promise<number> {
    if (this.running) return this.running
    this.running = this.drain().finally(() => {
      this.running = null
    })
    return this.running
  }

  private async drain(): Promise<number> {
    let sent = 0
    while (true) {
      const rows = await this.claim()
      if (rows.length === 0) return sent
      const delivered = await this.deliver(rows)
      sent += delivered
      if (delivered < rows.length) return sent
    }
  }

  private async claim(): Promise<OutboxRow[]> {
    const claimedUntil = new Date(Date.now() + CLAIM_SECONDS * 1_000).toISOString()
    const now = new Date().toISOString()
    const result = await this.db.query<OutboxRow & { seq: string | number }>(
      `
      UPDATE analytics_outbox
      SET claimed_until = $1, attempts = attempts + 1
      WHERE id IN (
        SELECT id FROM analytics_outbox
        WHERE dead_at IS NULL AND available_at <= $2 AND (claimed_until IS NULL OR claimed_until < $2)
        ORDER BY seq
        LIMIT $3
      )
        AND dead_at IS NULL
        AND (claimed_until IS NULL OR claimed_until < $2)
      RETURNING id, seq, payload_json, occurred_at, attempts
      `,
      [claimedUntil, now, this.options.batchSize ?? DEFAULT_BATCH_SIZE],
    )
    return [...result.rows].sort((a, b) => Number(a.seq) - Number(b.seq))
  }

  /** Sends the rows; returns how many left the outbox. */
  private async deliver(rows: OutboxRow[]): Promise<number> {
    const deletions = rows.filter((row) => operationOf(row).kind === "delete_person")
    const messages = rows.filter((row) => operationOf(row).kind !== "delete_person")
    let delivered = 0

    if (messages.length > 0) {
      try {
        await this.transport.sendBatch(messages.map(toPostHogMessage))
        await this.remove(messages.map((row) => row.id))
        delivered += messages.length
      } catch (error) {
        await this.retryLater(messages, error)
      }
    }

    for (const row of deletions) {
      const operation = operationOf(row)
      if (operation.kind !== "delete_person") continue
      if (!this.transport.canDeletePersons) {
        console.warn("[analytics] can't delete a PostHog person: POSTHOG_PERSONAL_API_KEY or POSTHOG_PROJECT_ID is not set")
        await this.markDead(row.id, "PostHog personal API key not configured")
        delivered += 1
        continue
      }
      try {
        await this.transport.deletePerson(operation.distinctId)
        await this.remove([row.id])
        delivered += 1
      } catch (error) {
        await this.retryLater([row], error)
      }
    }
    return delivered
  }

  /**
   * Records `mulberry_invite_expired` for each invite that passed its expiry unused, timestamped
   * at the expiry. Invites expire by time alone, so no request marks the moment.
   */
  async sweepExpiredInvites(): Promise<void> {
    if (!this.analytics.enabled) return
    const now = new Date().toISOString()
    const due = await this.db.query<{ id: string; inviter_user_id: string; expires_at: string | Date }>(
      `
      SELECT id, inviter_user_id, expires_at
      FROM invites
      WHERE analytics_expired_at IS NULL
        AND expires_at < $1
        AND status IN ('PENDING', 'REDEEMED', 'EXPIRED')
      LIMIT 200
      `,
      [now],
    )
    for (const invite of due.rows) {
      await this.db.transaction(async (tx) => {
        const marked = await tx.query<{ id: string }>(
          `UPDATE invites SET analytics_expired_at = $2 WHERE id = $1 AND analytics_expired_at IS NULL RETURNING id`,
          [invite.id, now],
        )
        if (marked.rows.length === 0) return
        await this.analytics.track(tx, {
          event: "mulberry_invite_expired",
          distinctId: invite.inviter_user_id,
          occurredAt: new Date(invite.expires_at),
          properties: { invite_id: invite.id },
          client: { platform: null, deviceFamily: null, appBuild: null, anonymousId: null },
        })
      })
    }
  }

  private async remove(ids: string[]): Promise<void> {
    if (ids.length === 0) return
    const placeholders = ids.map((_, index) => `$${index + 1}`).join(", ")
    await this.db.query(`DELETE FROM analytics_outbox WHERE id IN (${placeholders})`, ids)
  }

  private async retryLater(rows: OutboxRow[], error: unknown): Promise<void> {
    const message = error instanceof Error ? error.message : String(error)
    console.error("[analytics] PostHog send failed; will retry", { rows: rows.length, error: message })
    for (const row of rows) {
      if (row.attempts >= MAX_ATTEMPTS) {
        await this.markDead(row.id, message)
        continue
      }
      const backoffMs = Math.min(MAX_BACKOFF_MS, 5_000 * 2 ** Math.min(row.attempts, 20))
      await this.db.query(
        `UPDATE analytics_outbox SET claimed_until = NULL, available_at = $2, last_error = $3 WHERE id = $1`,
        [row.id, new Date(Date.now() + backoffMs).toISOString(), message.slice(0, 1_000)],
      )
    }
  }

  private async markDead(id: string, reason: string): Promise<void> {
    await this.db.query(
      `UPDATE analytics_outbox SET dead_at = $2, claimed_until = NULL, last_error = $3 WHERE id = $1`,
      [id, new Date().toISOString(), reason.slice(0, 1_000)],
    )
  }
}

function operationOf(row: OutboxRow): OutboxOperation {
  return typeof row.payload_json === "string" ? (JSON.parse(row.payload_json) as OutboxOperation) : row.payload_json
}

export function toPostHogMessage(row: OutboxRow): PostHogMessage {
  const operation = operationOf(row)
  const timestamp = new Date(row.occurred_at).toISOString()
  const base = { uuid: row.id, timestamp }
  // Every message comes from the server, so PostHog's IP lookup would place everyone at the server.
  const lib = { $lib: "mulberry-backend", $geoip_disable: true }
  switch (operation.kind) {
    case "capture":
      return {
        ...base,
        event: operation.event,
        distinct_id: operation.distinctId,
        properties: {
          ...operation.properties,
          ...lib,
          ...(operation.pairId ? { $groups: { [PAIR_GROUP_TYPE]: operation.pairId } } : {}),
          ...(operation.personless ? { $process_person_profile: false } : {}),
        },
      }
    case "set_person":
      return {
        ...base,
        event: "$set",
        distinct_id: operation.distinctId,
        properties: { ...lib, $set: operation.set, $set_once: operation.setOnce, $unset: operation.unset },
      }
    case "set_pair":
      return {
        ...base,
        event: "$groupidentify",
        distinct_id: `$${PAIR_GROUP_TYPE}_${operation.pairId}`,
        properties: { ...lib, $group_type: PAIR_GROUP_TYPE, $group_key: operation.pairId, $group_set: operation.set },
      }
    case "alias":
      return {
        ...base,
        event: "$create_alias",
        distinct_id: operation.distinctId,
        properties: { ...lib, distinct_id: operation.distinctId, alias: operation.alias },
      }
    case "delete_person":
      throw new Error("Person deletions go through the PostHog API, not capture")
  }
}

function logFailure(task: string): (error: unknown) => void {
  return (error) => {
    console.error(`[analytics] ${task} failed`, { error: error instanceof Error ? error.message : String(error) })
  }
}
