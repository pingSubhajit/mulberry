import { createHash, randomUUID } from "node:crypto"
import type { Database } from "../db/database.js"
import type { JsonObject } from "./events.js"

/** The PostHog group type that represents a pair. */
export const PAIR_GROUP_TYPE = "pair"

/**
 * One thing to send to PostHog. Rows are written in the same transaction as the change they
 * describe, so an event exists exactly when its change does.
 */
export type OutboxOperation =
  | {
      kind: "capture"
      distinctId: string
      event: string
      properties: JsonObject
      /** The pair the event belongs to, as a PostHog group. */
      pairId: string | null
      /** Records the event without creating or updating a person, as for a deleted account. */
      personless: boolean
    }
  | { kind: "set_person"; distinctId: string; set: JsonObject; setOnce: JsonObject; unset: string[] }
  | { kind: "set_pair"; pairId: string; set: JsonObject }
  /** Joins events sent before sign-in, under the install's anonymous ID, to the account. */
  | { kind: "alias"; distinctId: string; alias: string }
  /** Deletes the PostHog person, and with it their email and name. Events stay, without them. */
  | { kind: "delete_person"; distinctId: string }

export type OutboxOperationKind = OutboxOperation["kind"]

export interface OutboxEntry {
  /** Doubles as the PostHog event `uuid`, so sending a row twice records it once. */
  id: string
  operation: OutboxOperation
  occurredAt: Date
  /** Not sent before this time. */
  availableAt?: Date
}

export async function enqueueOutbox(db: Pick<Database, "query">, entry: OutboxEntry): Promise<void> {
  await db.query(
    `
    INSERT INTO analytics_outbox (id, kind, payload_json, occurred_at, available_at)
    VALUES ($1, $2, $3::jsonb, $4, $5)
    ON CONFLICT (id) DO NOTHING
    `,
    [
      entry.id,
      entry.operation.kind,
      JSON.stringify(entry.operation),
      entry.occurredAt.toISOString(),
      (entry.availableAt ?? new Date()).toISOString(),
    ],
  )
}

export function newOutboxId(): string {
  return randomUUID()
}

/**
 * A UUID that is always the same for the same `name`, so an event made twice, as by a backfill
 * run again, is recorded once.
 */
export function stableUuid(name: string): string {
  const hex = createHash("sha256").update(`mulberry-analytics:${name}`).digest("hex")
  const variant = ((parseInt(hex[16], 16) & 0x3) | 0x8).toString(16)
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-5${hex.slice(13, 16)}-${variant}${hex.slice(17, 20)}-${hex.slice(20, 32)}`
}
