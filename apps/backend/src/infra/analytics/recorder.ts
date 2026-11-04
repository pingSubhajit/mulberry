import type { Database } from "../db/database.js"
import type { PairSessionRecord } from "../../contracts/dbRecords.js"
import { dateOnly } from "../../modules/_shared/dates.js"
import { anonymousDistinctId, currentClientContext } from "./context.js"
import type {
  AnalyticsEventName,
  AnalyticsEventProperties,
  ClientContext,
  EventSource,
  JsonObject,
  JsonValue,
  PairEndedReason,
} from "./events.js"
import { enqueueOutbox, newOutboxId, type OutboxOperation } from "./outbox.js"
import { isInternalEmail, pairMembers, pairName, pairSnapshot, personSnapshot, type InternalAccounts } from "./profiles.js"

export interface AnalyticsSettings {
  /** False records nothing: development, tests, or PostHog not configured. */
  enabled: boolean
  environment: string
  accounts: InternalAccounts
}

export interface TrackInput<E extends AnalyticsEventName> {
  event: E
  /** The user who acted. */
  distinctId: string
  properties: AnalyticsEventProperties[E]
  pairId?: string | null
  occurredAt?: Date
  /** Fixes the event's ID, to make sending it again harmless. */
  id?: string
  source?: EventSource
  /** Defaults to the app behind the current request. */
  client?: ClientContext
}

export type PersonlessTrackInput<E extends AnalyticsEventName> = Omit<TrackInput<E>, "distinctId">

/** Person properties emptied when an account is deleted, before the person itself is. */
const PERSONAL_PROPERTIES = ["email", "name", "partner_name", "partner_email", "pair_name"]

/** How long a deleted account's PostHog person waits, so its last events are in before it goes. */
const PERSON_DELETION_DELAY_MS = 60 * 60 * 1_000

type JsonObjectInput = { [key: string]: JsonValue | undefined }

/**
 * Mulberry's single way to record analytics. Every method writes to the outbox through the
 * caller's database handle, so passing a transaction ties the record to the change it describes.
 * When analytics is off, every method does nothing.
 */
export class Analytics {
  constructor(private readonly settings: AnalyticsSettings) {}

  get enabled(): boolean {
    return this.settings.enabled
  }

  isInternalEmail(email: string | null): boolean {
    return isInternalEmail(email, this.settings.accounts)
  }

  async track<E extends AnalyticsEventName>(db: Pick<Database, "query">, input: TrackInput<E>): Promise<void> {
    if (!this.enabled) return
    await this.capture(db, input, input.distinctId, false)
  }

  /** An event that belongs to no person, such as an account's deletion. */
  async trackPersonless<E extends AnalyticsEventName>(db: Pick<Database, "query">, input: PersonlessTrackInput<E>): Promise<void> {
    if (!this.enabled) return
    await this.capture(db, input, `mulberry-server:${input.event}`, true)
  }

  /** Brings the user's PostHog person up to date with the database. */
  async syncPerson(db: Pick<Database, "query">, userId: string): Promise<void> {
    if (!this.enabled) return
    const snapshot = await personSnapshot(db, userId, this.settings.accounts)
    if (!snapshot) return
    await this.enqueue(db, { kind: "set_person", distinctId: userId, ...snapshot })
  }

  /** Brings the pair's PostHog group up to date, and both partners with it. */
  async syncPair(db: Pick<Database, "query">, pair: PairSessionRecord): Promise<void> {
    if (!this.enabled) return
    for (const [position, userId] of [[1, pair.user_one_id], [2, pair.user_two_id]] as const) {
      await db.query(
        `
        INSERT INTO analytics_pair_members (pair_session_id, user_id, position)
        VALUES ($1, $2, $3)
        ON CONFLICT (pair_session_id, user_id) DO NOTHING
        `,
        [pair.id, userId, position],
      )
    }
    await this.enqueue(db, { kind: "set_pair", pairId: pair.id, set: await pairSnapshot(db, pair, this.settings.accounts) })
    await this.syncPerson(db, pair.user_one_id)
    await this.syncPerson(db, pair.user_two_id)
  }

  /**
   * Records a pair ending. Call it after the pair is gone from the database, inside the same
   * transaction, with the partner who acted (or who remains, when an account was deleted).
   */
  async endPair(
    db: Pick<Database, "query">,
    input: { pair: PairSessionRecord; reason: PairEndedReason; distinctId: string; activeDays: number },
  ): Promise<void> {
    if (!this.enabled) return
    const endedAt = new Date()
    const members = await pairMembers(db, input.pair)
    const set: JsonObject = {
      status: "ended",
      ended_at: endedAt.toISOString(),
      ended_reason: input.reason,
      name: pairName(members.one, members.two),
    }
    // A deleted partner's email and name leave PostHog with them.
    if (!members.one) Object.assign(set, { partner_one_name: "Deleted account", partner_one_email: null })
    if (!members.two) Object.assign(set, { partner_two_name: "Deleted account", partner_two_email: null })
    await this.enqueue(db, { kind: "set_pair", pairId: input.pair.id, set })

    await this.track(db, {
      event: "mulberry_pair_ended",
      distinctId: input.distinctId,
      pairId: input.pair.id,
      occurredAt: endedAt,
      properties: {
        reason: input.reason,
        pair_age_days: wholeDaysBetween(new Date(input.pair.created_at), endedAt),
        pair_active_days: input.activeDays,
      },
    })
    for (const userId of [input.pair.user_one_id, input.pair.user_two_id]) {
      await this.syncPerson(db, userId)
    }
  }

  /** Joins what the install recorded before sign-in to the account. */
  async alias(db: Pick<Database, "query">, userId: string, anonymousId: string | null): Promise<void> {
    if (!this.enabled || !anonymousId) return
    await this.enqueue(db, { kind: "alias", distinctId: userId, alias: anonymousDistinctId(anonymousId) })
  }

  /**
   * Removes a deleted account's details from PostHog: emptied from the person and from every pair
   * they were ever in now, and the person deleted soon after. Call it after the users row is gone.
   */
  async forgetPerson(db: Pick<Database, "query">, userId: string): Promise<void> {
    if (!this.enabled) return
    await this.forgetPairMembership(db, userId)
    await this.enqueue(db, {
      kind: "set_person",
      distinctId: userId,
      set: { account_deleted: true, is_paired: false },
      setOnce: {},
      unset: PERSONAL_PROPERTIES,
    })
    const now = new Date()
    await enqueueOutbox(db, {
      id: newOutboxId(),
      operation: { kind: "delete_person", distinctId: userId },
      occurredAt: now,
      availableAt: new Date(now.getTime() + PERSON_DELETION_DELAY_MS),
    })
  }

  private async forgetPairMembership(db: Pick<Database, "query">, userId: string): Promise<void> {
    const memberships = await db.query<{ pair_session_id: string }>(
      `SELECT pair_session_id FROM analytics_pair_members WHERE user_id = $1`,
      [userId],
    )
    for (const { pair_session_id: pairId } of memberships.rows) {
      const rows = await db.query<{ user_id: string; position: number }>(
        `SELECT user_id, position FROM analytics_pair_members WHERE pair_session_id = $1`,
        [pairId],
      )
      const userOneId = rows.rows.find((row) => Number(row.position) === 1)?.user_id ?? ""
      const userTwoId = rows.rows.find((row) => Number(row.position) === 2)?.user_id ?? ""
      const members = await pairMembers(db, { user_one_id: userOneId, user_two_id: userTwoId })
      const set: JsonObject = { name: pairName(members.one, members.two) }
      if (userOneId === userId) Object.assign(set, { partner_one_name: "Deleted account", partner_one_email: null })
      if (userTwoId === userId) Object.assign(set, { partner_two_name: "Deleted account", partner_two_email: null })
      await this.enqueue(db, { kind: "set_pair", pairId, set })
    }
    await db.query(`DELETE FROM analytics_pair_members WHERE user_id = $1`, [userId])
  }

  /** `mulberry_app_opened`, at most once per user per UTC day. */
  async trackDailyAppOpen(db: Pick<Database, "query">, userId: string): Promise<void> {
    if (!this.enabled) return
    const day = dateOnly(new Date())
    const seen = await db.query(`SELECT 1 FROM user_app_open_days WHERE user_id = $1 AND open_day = $2::date`, [userId, day])
    if (seen.rows.length > 0) return
    await db.query(
      `INSERT INTO user_app_open_days (user_id, open_day) VALUES ($1, $2::date) ON CONFLICT (user_id, open_day) DO NOTHING`,
      [userId, day],
    )
    await this.track(db, { event: "mulberry_app_opened", distinctId: userId, properties: {} })
  }

  private async capture<E extends AnalyticsEventName>(
    db: Pick<Database, "query">,
    input: PersonlessTrackInput<E>,
    distinctId: string,
    personless: boolean,
  ): Promise<void> {
    const client = input.client ?? currentClientContext()
    const properties = compactJson({
      ...input.properties,
      environment: this.settings.environment,
      source: input.source ?? "server",
      pair_id: input.pairId ?? undefined,
      platform: client.platform ?? undefined,
      device_family: client.deviceFamily ?? undefined,
      app_build: client.appBuild ?? undefined,
    })
    await enqueueOutbox(db, {
      id: input.id ?? newOutboxId(),
      occurredAt: input.occurredAt ?? new Date(),
      operation: { kind: "capture", distinctId, event: input.event, properties, pairId: input.pairId ?? null, personless },
    })
  }

  private async enqueue(db: Pick<Database, "query">, operation: OutboxOperation): Promise<void> {
    await enqueueOutbox(db, { id: newOutboxId(), operation, occurredAt: new Date() })
  }
}

/** Analytics that records nothing, for tests and services built without it. */
export const disabledAnalytics = new Analytics({
  enabled: false,
  environment: "development",
  accounts: { production: false, emails: new Set() },
})

function compactJson(input: JsonObjectInput): JsonObject {
  const output: JsonObject = {}
  for (const [key, value] of Object.entries(input)) {
    if (value !== undefined) output[key] = value
  }
  return output
}

export function wholeDaysBetween(from: Date, to: Date): number {
  return Math.max(0, Math.floor((to.getTime() - from.getTime()) / (24 * 60 * 60 * 1_000)))
}
