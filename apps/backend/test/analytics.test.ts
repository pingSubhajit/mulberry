import { newDb } from "pg-mem"
import type { FastifyInstance } from "fastify"
import { afterEach, describe, expect, it } from "vitest"
import { createApp } from "../src/app/createApp.js"
import { runMigrations, type Database } from "../src/infra/db/database.js"
import type { AppConfig } from "../src/infra/config/config.js"
import type { PostHogMessage, PostHogTransport } from "../src/infra/analytics/transport.js"
import type { JsonObject } from "../src/infra/analytics/events.js"
import { ANALYTICS_BACKFILL_NAME, runAnalyticsBackfill } from "../src/infra/analytics/backfill.js"
import { createAnalyticsRuntime } from "../src/infra/analytics/setup.js"
import type { PushSender, PushSendResult } from "../src/infra/push/types.js"
import type { AnalyticsRuntime } from "../src/infra/analytics/setup.js"

class RecordingTransport implements PostHogTransport {
  readonly messages: PostHogMessage[] = []
  readonly deletedPersons: string[] = []
  failNext = 0
  canDeletePersons = true

  async sendBatch(messages: PostHogMessage[]): Promise<void> {
    if (this.failNext > 0) {
      this.failNext -= 1
      throw new Error("PostHog is down")
    }
    this.messages.push(...messages)
  }

  async deletePerson(distinctId: string): Promise<void> {
    this.deletedPersons.push(distinctId)
  }

  events(name: string): PostHogMessage[] {
    return this.messages.filter((message) => message.event === name)
  }

  /** The person properties last set for `distinctId`, merged in order. */
  person(distinctId: string): JsonObject {
    const person: JsonObject = {}
    for (const message of this.events("$set").filter((m) => m.distinct_id === distinctId)) {
      Object.assign(person, message.properties.$set as JsonObject)
      for (const key of (message.properties.$unset as string[]) ?? []) delete person[key]
    }
    return person
  }

  pair(pairId: string): JsonObject {
    const pair: JsonObject = {}
    for (const message of this.events("$groupidentify").filter((m) => m.properties.$group_key === pairId)) {
      Object.assign(pair, message.properties.$group_set as JsonObject)
    }
    return pair
  }
}

class SilentPushSender implements PushSender {
  async send(): Promise<PushSendResult> {
    return { invalidTokens: [] }
  }
  async sendLiveActivity(): Promise<boolean> {
    return true
  }
}

const PRODUCTION_CONFIG: AppConfig = {
  port: 8080,
  databaseUrl: "postgres://unused",
  googleClientId: "",
  googleAllowedClientIds: [],
  allowDevGoogleTokens: true,
  enableDebugRoutes: false,
  posthogEnvironment: "production",
  posthogInternalEmails: ["founder@elaris.dev"],
}

const INTERNAL_EMAIL = "founder@elaris.dev"
const ANON_ID = "0f8fad5b-d9cb-469f-a165-70867728950e"

describe("Analytics", () => {
  let app: FastifyInstance
  let db: Database
  let transport: RecordingTransport

  async function start(config: Partial<AppConfig> = {}): Promise<void> {
    const memoryDb = newDb({ autoCreateForeignKeyIndices: true })
    const pool = new (memoryDb.adapters.createPg().Pool)()
    await runMigrations(pool)
    db = {
      query: pool.query.bind(pool),
      transaction: async (fn) => {
        await pool.query("BEGIN")
        try {
          const result = await fn({ query: pool.query.bind(pool) })
          await pool.query("COMMIT")
          return result
        } catch (error) {
          await pool.query("ROLLBACK")
          throw error
        }
      },
      end: async () => {
        await pool.end()
      },
    }
    transport = new RecordingTransport()
    app = await createApp({
      db,
      remoteConfig: null,
      pushSender: new SilentPushSender(),
      pushOptions: { debounceMs: 0, widgetReloadDelayMs: 0 },
      analyticsTransport: transport,
      analyticsWorker: { pollIntervalMs: 0 },
      analyticsBackfill: false,
      config: { ...PRODUCTION_CONFIG, ...config },
    })
    await app.ready()
  }

  afterEach(async () => {
    await app?.close()
  })

  /** A second runtime on the same database and transport, standing in for the app's own worker. */
  function runtime(): AnalyticsRuntime {
    return createAnalyticsRuntime(PRODUCTION_CONFIG, db, { transport, worker: { pollIntervalMs: 0 } })
  }

  async function flush(): Promise<void> {
    await runtime().worker?.flush()
  }

  async function outboxCount(): Promise<number> {
    const rows = await db.query<{ count: string | number }>(`SELECT COUNT(*) AS count FROM analytics_outbox WHERE dead_at IS NULL`)
    return Number(rows.rows[0].count)
  }

  async function signIn(email: string, name: string, headers: Record<string, string> = {}) {
    const response = await app.inject({
      method: "POST",
      url: "/auth/google",
      headers,
      payload: { idToken: `dev-google:${email}:${name}` },
    })
    expect(response.statusCode).toBe(200)
    return response.json() as { accessToken: string; userId: string }
  }

  async function completeProfile(accessToken: string, displayName: string, partnerDisplayName: string) {
    const response = await app.inject({
      method: "PUT",
      url: "/me/profile",
      headers: bearer(accessToken),
      payload: { displayName, partnerDisplayName, anniversaryDate: "2026-01-01" },
    })
    expect(response.statusCode).toBe(200)
  }

  async function pairUsers() {
    const inviter = await signIn("subhajit@elaris.dev", "Subhajit")
    await completeProfile(inviter.accessToken, "Subhajit", "Ankita")
    const invite = (await app.inject({ method: "POST", url: "/invites", headers: bearer(inviter.accessToken) })).json()
    const recipient = await signIn("ankita@elaris.dev", "Ankita")
    await completeProfile(recipient.accessToken, "Ankita", "Subhajit")
    await app.inject({ method: "POST", url: "/invites/redeem", headers: bearer(recipient.accessToken), payload: { code: invite.code } })
    const accept = await app.inject({ method: "POST", url: `/invites/${invite.inviteId}/accept`, headers: bearer(recipient.accessToken) })
    expect(accept.statusCode).toBe(200)
    return { inviter, recipient, pairId: accept.json().pairSessionId as string, inviteId: invite.inviteId as string }
  }

  it("records nothing outside production", async () => {
    await start({ posthogEnvironment: "development" })
    await signIn("someone@elaris.dev", "Someone")
    expect(await outboxCount()).toBe(0)
  })

  it("marks everyone internal when analytics is turned on outside production", async () => {
    await start({ posthogEnvironment: "development", posthogDisabled: false })
    const user = await signIn("someone@elaris.dev", "Someone")
    await flush()
    expect(transport.person(user.userId).is_internal).toBe(true)
    expect(transport.events("mulberry_user_signed_up")[0].properties.environment).toBe("development")
  })

  it("records sign-up under the user, with email and name on the person, and joins the install's earlier events", async () => {
    await start()
    const user = await signIn("someone@elaris.dev", "Someone", {
      "x-app-platform": "ios",
      "x-app-build": "12",
      "x-device-family": "ipad",
      "x-analytics-anonymous-id": ANON_ID,
    })
    const internal = await signIn(INTERNAL_EMAIL.toUpperCase(), "Founder")
    await flush()

    const signUp = transport.events("mulberry_user_signed_up").find((m) => m.distinct_id === user.userId)
    expect(signUp?.properties).toMatchObject({ sign_in_method: "google", source: "server", platform: "ios", device_family: "ipad", app_build: 12 })
    expect(transport.person(user.userId)).toMatchObject({ email: "someone@elaris.dev", name: "Someone", is_internal: false, is_paired: false })
    expect(transport.person(internal.userId).is_internal).toBe(true)
    expect(transport.events("$create_alias")[0]).toMatchObject({ distinct_id: user.userId, properties: { alias: `anon:${ANON_ID}` } })
  })

  it("shows pairs as 'Partner 1 x Partner 2' and keys invite events to people", async () => {
    await start()
    const { inviter, recipient, pairId, inviteId } = await pairUsers()
    const stranger = await signIn("stranger@elaris.dev", "Stranger")
    await app.inject({ method: "POST", url: "/invites/redeem", headers: bearer(stranger.accessToken), payload: { code: "000000" } })
    await flush()

    expect(transport.pair(pairId)).toMatchObject({
      name: "Subhajit x Ankita",
      partner_one_email: "subhajit@elaris.dev",
      partner_two_email: "ankita@elaris.dev",
      status: "active",
      is_internal: false,
    })
    expect(transport.person(inviter.userId)).toMatchObject({
      is_paired: true,
      pair_id: pairId,
      pair_name: "Subhajit x Ankita",
      partner_name: "Ankita",
      partner_email: "ankita@elaris.dev",
    })
    expect(transport.events("mulberry_invite_created")[0]).toMatchObject({ distinct_id: inviter.userId, properties: { invite_id: inviteId } })
    expect(transport.events("mulberry_invite_redeemed")[0].distinct_id).toBe(recipient.userId)
    const created = transport.events("mulberry_pair_created")[0]
    expect(created).toMatchObject({ distinct_id: recipient.userId, properties: { $groups: { pair: pairId }, pair_id: pairId } })
    expect(transport.events("mulberry_invite_redeem_failed")[0]).toMatchObject({ distinct_id: stranger.userId, properties: { reason: "not_found" } })
    expect(transport.events("mulberry_onboarding_completed").map((m) => m.distinct_id).sort()).toEqual([inviter.userId, recipient.userId].sort())
  })

  it("records canvas activity, a pair's active day once, and sticker packs", async () => {
    await start()
    const { inviter, recipient, pairId } = await pairUsers()
    for (const [user, id] of [[inviter, "a"], [recipient, "b"]] as const) {
      const response = await app.inject({
        method: "POST",
        url: "/canvas/ops/batch",
        headers: bearer(user.accessToken),
        payload: { batchId: `batch-${id}`, operations: [stickerOperation(`op-${id}`, `sticker-${id}`)] },
      })
      expect(response.statusCode).toBe(200)
    }
    await flush()

    const batches = transport.events("mulberry_canvas_batch_ingested")
    expect(batches.map((m) => m.distinct_id)).toEqual([inviter.userId, recipient.userId])
    expect(batches[0].properties).toMatchObject({ sticker_added_count: 1, sticker_pack_keys: ["love"], pair_id: pairId })
    const activeDays = transport.events("mulberry_pair_active_day")
    expect(activeDays).toHaveLength(1)
    expect(activeDays[0]).toMatchObject({ distinct_id: inviter.userId, properties: { activity_source: "canvas", $groups: { pair: pairId } } })
    expect(transport.events("mulberry_pair_active_week")).toHaveLength(1)
    expect(transport.events("mulberry_canvas_first_action")).toHaveLength(1)
    expect(await outboxCount()).toBe(0)
  })

  it("records reactions and the notification each recipient is sent", async () => {
    await start()
    const { inviter, recipient, pairId } = await pairUsers()
    const token = await app.inject({
      method: "POST",
      url: "/devices/fcm-token",
      headers: bearer(recipient.accessToken),
      payload: { token: "recipient-token", platform: "ANDROID", appEnvironment: "prod" },
    })
    expect(token.statusCode).toBe(200)
    const reaction = await app.inject({ method: "POST", url: "/reactions/send", headers: bearer(inviter.accessToken), payload: { reactionType: "HUG" } })
    expect(reaction.statusCode).toBe(200)
    await eventually(async () => {
      await flush()
      return transport.events("mulberry_push_sent").some((m) => m.properties.push_type === "REACTION")
    })

    expect(transport.events("mulberry_reaction_sent")[0]).toMatchObject({ distinct_id: inviter.userId, properties: { reaction_type: "HUG" } })
    expect(transport.events("mulberry_push_sent").find((m) => m.properties.push_type === "REACTION")).toMatchObject({
      distinct_id: recipient.userId,
      properties: { pair_id: pairId },
    })
    expect(transport.events("mulberry_pair_active_day")[0].properties.activity_source).toBe("reaction")
  })

  it("records an unpair as the pair ending and clears the partners' pair details", async () => {
    await start()
    const { inviter, recipient, pairId } = await pairUsers()
    const response = await app.inject({ method: "POST", url: "/pairing/disconnect", headers: bearer(inviter.accessToken) })
    expect(response.statusCode).toBe(200)
    await flush()

    expect(transport.events("mulberry_pair_ended")[0]).toMatchObject({
      distinct_id: inviter.userId,
      properties: { reason: "unpaired", pair_id: pairId },
    })
    expect(transport.pair(pairId)).toMatchObject({ status: "ended", ended_reason: "unpaired" })
    for (const user of [inviter, recipient]) {
      const person = transport.person(user.userId)
      expect(person.is_paired).toBe(false)
      expect(person.pair_name).toBeUndefined()
      expect(person.partner_email).toBeUndefined()
    }
  })

  it("removes a deleted account's details from PostHog and deletes the person later", async () => {
    await start()
    const { inviter, recipient, pairId } = await pairUsers()
    const response = await app.inject({ method: "DELETE", url: "/me", headers: bearer(recipient.accessToken) })
    expect(response.statusCode).toBeLessThan(300)
    await flush()

    const deleted = transport.person(recipient.userId)
    expect(deleted.email).toBeUndefined()
    expect(deleted.name).toBeUndefined()
    expect(deleted.account_deleted).toBe(true)
    expect(transport.events("mulberry_account_deleted")[0].properties).toMatchObject({ was_paired: true, $process_person_profile: false })
    expect(transport.events("mulberry_pair_ended")[0]).toMatchObject({ distinct_id: inviter.userId, properties: { reason: "account_deleted" } })
    expect(transport.pair(pairId)).toMatchObject({ name: "Subhajit x Deleted account", partner_two_email: null })
    expect(transport.person(inviter.userId).is_paired).toBe(false)

    expect(transport.deletedPersons).toEqual([])
    await db.query(`UPDATE analytics_outbox SET available_at = $1 WHERE kind = 'delete_person'`, [new Date(0).toISOString()])
    await flush()
    expect(transport.deletedPersons).toEqual([recipient.userId])
  })

  it("removes a deleted account's details from pairs it had already left", async () => {
    await start()
    const { inviter, recipient, pairId } = await pairUsers()
    await app.inject({ method: "POST", url: "/pairing/disconnect", headers: bearer(inviter.accessToken) })
    const response = await app.inject({ method: "DELETE", url: "/me", headers: bearer(recipient.accessToken) })
    expect(response.statusCode).toBeLessThan(300)
    await flush()

    expect(transport.pair(pairId)).toMatchObject({
      name: "Subhajit x Deleted account",
      status: "ended",
      partner_two_name: "Deleted account",
      partner_two_email: null,
      partner_one_email: "subhajit@elaris.dev",
    })
  })

  it("accepts the apps' events, before and after sign-in, and drops invalid ones", async () => {
    await start()
    const anonymous = await app.inject({
      method: "POST",
      url: "/analytics/events",
      headers: { "x-analytics-anonymous-id": ANON_ID, "x-app-platform": "android", "x-device-family": "android_phone" },
      payload: {
        events: [
          clientEvent("1b4e28ba-2fa1-4d2e-883f-0016d3cca427", "mulberry_onboarding_step_viewed", { step: "welcome" }),
          clientEvent("6fa459ea-ee8a-4ca4-894e-db77e160355e", "mulberry_onboarding_step_viewed", { step: "made_up" }),
          clientEvent("not-a-uuid", "mulberry_push_opened", { push_type: "REACTION" }),
        ],
      },
    })
    expect(anonymous.statusCode).toBe(202)
    expect(anonymous.json()).toEqual({ accepted: 1, rejected: 2 })

    const user = await signIn("someone@elaris.dev", "Someone")
    const event = clientEvent("9c5b94b1-35ad-49bb-b118-8e8fc24abf80", "mulberry_wallpaper_setup_step", {
      surface_type: "IOS_SHORTCUT",
      step: "install_shortcut",
      outcome: "failed",
      failure_reason: "shortcut_not_found",
    })
    for (let attempt = 0; attempt < 2; attempt += 1) {
      const signedIn = await app.inject({ method: "POST", url: "/analytics/events", headers: bearer(user.accessToken), payload: { events: [event] } })
      expect(signedIn.statusCode).toBe(202)
    }
    const empty = await app.inject({ method: "POST", url: "/analytics/events", payload: { events: [event] } })
    expect(empty.statusCode).toBe(400)
    await flush()

    expect(transport.events("mulberry_onboarding_step_viewed")).toEqual([
      expect.objectContaining({
        uuid: "1b4e28ba-2fa1-4d2e-883f-0016d3cca427",
        distinct_id: `anon:${ANON_ID}`,
        properties: expect.objectContaining({ step: "welcome", source: "app", platform: "android", device_family: "android_phone" }),
      }),
    ])
    const setupSteps = transport.events("mulberry_wallpaper_setup_step")
    expect(setupSteps).toHaveLength(1)
    expect(setupSteps[0]).toMatchObject({ distinct_id: user.userId, properties: { failure_reason: "shortcut_not_found" } })
  })

  it("keeps events through a PostHog outage and sends them once it's back", async () => {
    await start()
    const user = await signIn("someone@elaris.dev", "Someone")
    transport.failNext = 1
    await flush()
    expect(transport.messages).toHaveLength(0)
    expect(await outboxCount()).toBeGreaterThan(0)

    await db.query(`UPDATE analytics_outbox SET available_at = $1`, [new Date(0).toISOString()])
    await flush()
    expect(transport.events("mulberry_user_signed_up").map((m) => m.distinct_id)).toEqual([user.userId])
    expect(await outboxCount()).toBe(0)
  })

  it("records the platforms, device families and builds a person uses", async () => {
    await start()
    const user = await signIn("someone@elaris.dev", "Someone")
    for (const headers of [
      { "x-app-platform": "ios", "x-device-family": "iphone", "x-app-build": "10" },
      { "x-app-platform": "macos", "x-device-family": "mac", "x-app-build": "4" },
    ]) {
      await app.inject({ method: "GET", url: "/bootstrap", headers: { ...bearer(user.accessToken), ...headers } })
    }
    await flush()

    expect(transport.person(user.userId)).toMatchObject({
      platforms: ["ios", "macos"],
      device_families: ["iphone", "mac"],
    })
    expect(transport.events("mulberry_app_opened")).toHaveLength(1)
  })

  it("records expired invites at their expiry, once", async () => {
    await start()
    const inviter = await signIn("subhajit@elaris.dev", "Subhajit")
    await completeProfile(inviter.accessToken, "Subhajit", "Ankita")
    const invite = (await app.inject({ method: "POST", url: "/invites", headers: bearer(inviter.accessToken) })).json()
    const expiredAt = new Date(Date.now() - 60_000).toISOString()
    await db.query(`UPDATE invites SET expires_at = $1`, [expiredAt])
    const { worker } = runtime()
    await worker?.sweepExpiredInvites()
    await worker?.sweepExpiredInvites()
    await flush()

    const expired = transport.events("mulberry_invite_expired")
    expect(expired).toHaveLength(1)
    expect(expired[0]).toMatchObject({ distinct_id: inviter.userId, timestamp: expiredAt, properties: { invite_id: invite.inviteId } })
  })

  it("backfills earlier history once, with fixed IDs", async () => {
    await start()
    const { pairId, recipient } = await pairUsers()
    transport.messages.length = 0
    await db.query(`DELETE FROM analytics_outbox`)
    const { analytics } = runtime()
    const cutoff = new Date(Date.now() + 1_000)
    await runAnalyticsBackfill(db, analytics, cutoff)
    await runAnalyticsBackfill(db, analytics, cutoff)
    await flush()

    expect(transport.events("mulberry_user_signed_up")).toHaveLength(2)
    expect(transport.events("mulberry_user_signed_up")[0].properties.source).toBe("backfill")
    expect(transport.events("mulberry_pair_created")[0]).toMatchObject({ distinct_id: recipient.userId, properties: { pair_id: pairId } })
    expect(transport.pair(pairId).name).toBe("Subhajit x Ankita")
    const done = await db.query(`SELECT name FROM schema_backfills WHERE name = $1`, [ANALYTICS_BACKFILL_NAME])
    expect(done.rows).toHaveLength(1)
  })
})

async function eventually(predicate: () => Promise<boolean>): Promise<void> {
  const startedAt = Date.now()
  while (Date.now() - startedAt < 2_000) {
    if (await predicate()) return
    await new Promise((resolve) => setTimeout(resolve, 20))
  }
  throw new Error("Condition was not met")
}

function bearer(accessToken: string) {
  return { authorization: `Bearer ${accessToken}` }
}

function clientEvent(id: string, event: string, properties: Record<string, string>) {
  return { id, event, occurredAt: new Date().toISOString(), properties }
}

function stickerOperation(clientOperationId: string, elementId: string) {
  return {
    clientOperationId,
    type: "ADD_STICKER_ELEMENT",
    strokeId: elementId,
    payload: { id: elementId, createdAt: 123, center: { x: 0.5, y: 0.5 }, rotationRad: 0, scale: 0.2, packKey: "love", packVersion: 1, stickerId: "heart" },
    clientCreatedAt: new Date().toISOString(),
  }
}
