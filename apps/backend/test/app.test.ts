import { newDb } from "pg-mem"
import type { FastifyInstance } from "fastify"
import { afterEach, beforeEach, describe, expect, it } from "vitest"
import { createApp } from "../src/app.js"
import { runMigrations, type Database } from "../src/db.js"
import { PushDispatchService, type MulberryPushMessage, type PushSender } from "../src/push.js"
import type {
  ProcessedWallpaperImage,
  WallpaperImageProcessor,
  WallpaperStorage,
} from "../src/wallpapers.js"

describe("Mulberry backend", () => {
  let app: FastifyInstance
  let db: Database
  let pushSender: RecordingPushSender
  let wallpaperStorage: RecordingWallpaperStorage

  beforeEach(async () => {
    const memoryDb = newDb({
      autoCreateForeignKeyIndices: true,
    })
    const adapter = memoryDb.adapters.createPg()
    const pool = new adapter.Pool()
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
    pushSender = new RecordingPushSender()
    wallpaperStorage = new RecordingWallpaperStorage()
    app = await createApp({
      db,
      pushSender,
      pushOptions: { debounceMs: 20 },
      wallpaperStorage,
      wallpaperImageProcessor: new FakeWallpaperImageProcessor(),
      config: {
        port: 8080,
        databaseUrl: "postgres://unused",
        googleClientId: "",
        allowDevGoogleTokens: true,
        wallpaperAdminPassword: "admin-password",
      },
    })
    await app.ready()
  })

  afterEach(async () => {
    await app.close()
  })

  it("issues an app session from a development Google token", async () => {
    const response = await app.inject({
      method: "POST",
      url: "/auth/google",
      payload: {
        idToken: "dev-google:subhajit@elaris.dev:Subhajit",
      },
    })

    expect(response.statusCode).toBe(200)
    const body = response.json()
    expect(body.accessToken).toBeTruthy()
    expect(body.refreshToken).toBeTruthy()
    expect(body.bootstrapState.authStatus).toBe("SIGNED_IN")
    expect(body.bootstrapState.onboardingCompleted).toBe(false)
  })

  it("stores the Google profile photo and returns it in bootstrap", async () => {
    const photoUrl = "https://example.test/avatar.png"
    const response = await app.inject({
      method: "POST",
      url: "/auth/google",
      payload: {
        idToken: `dev-google:subhajit@elaris.dev:Subhajit:${photoUrl}`,
      },
    })

    expect(response.statusCode).toBe(200)
    const body = response.json()
    expect(body.bootstrapState.userPhotoUrl).toBe(photoUrl)
    expect(body.bootstrapState.userEmail).toBe("subhajit@elaris.dev")

    const bootstrap = await app.inject({
      method: "GET",
      url: "/bootstrap",
      headers: bearer(body.accessToken),
    })

    expect(bootstrap.statusCode).toBe(200)
    expect(bootstrap.json().userPhotoUrl).toBe(photoUrl)
  })

  it("rotates the session on refresh", async () => {
    const auth = await signIn("subhajit@elaris.dev", "Subhajit")

    const response = await app.inject({
      method: "POST",
      url: "/auth/refresh",
      payload: {
        refreshToken: auth.refreshToken,
      },
    })

    expect(response.statusCode).toBe(200)
    const body = response.json()
    expect(body.accessToken).not.toBe(auth.accessToken)
    expect(body.refreshToken).not.toBe(auth.refreshToken)
  })

  it("persists onboarding profile and requires all fields", async () => {
    const auth = await signIn("subhajit@elaris.dev", "Subhajit")

    const invalid = await app.inject({
      method: "PUT",
      url: "/me/profile",
      headers: bearer(auth.accessToken),
      payload: {
        displayName: "Subhajit",
        partnerDisplayName: "",
        anniversaryDate: "2026-01-01",
      },
    })

    expect(invalid.statusCode).toBe(400)

    const valid = await app.inject({
      method: "PUT",
      url: "/me/profile",
      headers: bearer(auth.accessToken),
      payload: {
        displayName: "Subhajit",
        partnerDisplayName: "Ankita",
        anniversaryDate: "2026-01-01",
      },
    })

    expect(valid.statusCode).toBe(200)
    const body = valid.json()
    expect(body.onboardingCompleted).toBe(true)
    expect(body.userDisplayName).toBe("Subhajit")
    expect(body.partnerDisplayName).toBe("Ankita")
  })

  it("updates the signed-in user's profile name and propagates it to a paired partner", async () => {
    const { inviter, recipient } = await pairUsers()

    const invalid = await app.inject({
      method: "PUT",
      url: "/me/display-name",
      headers: bearer(inviter.accessToken),
      payload: { displayName: " " },
    })
    expect(invalid.statusCode).toBe(400)

    const update = await app.inject({
      method: "PUT",
      url: "/me/display-name",
      headers: bearer(inviter.accessToken),
      payload: { displayName: "Subhajit Kundu" },
    })
    expect(update.statusCode).toBe(200)
    expect(update.json().userDisplayName).toBe("Subhajit Kundu")

    const recipientBootstrap = await app.inject({
      method: "GET",
      url: "/bootstrap",
      headers: bearer(recipient.accessToken),
    })
    expect(recipientBootstrap.statusCode).toBe(200)
    expect(recipientBootstrap.json().partnerDisplayName).toBe("Subhajit Kundu")
  })

  it("returns paired metadata in bootstrap", async () => {
    const { inviter } = await pairUsers()

    const bootstrap = await app.inject({
      method: "GET",
      url: "/bootstrap",
      headers: bearer(inviter.accessToken),
    })

    expect(bootstrap.statusCode).toBe(200)
    expect(bootstrap.json().pairedAt).toBeTruthy()
    expect(bootstrap.json().currentStreakDays).toBe(0)
  })

  it("uses custom profile photos before Google photo fallback", async () => {
    const { inviter, recipient } = await pairUsers()
    const upload = createImageUploadPayload()

    const response = await app.inject({
      method: "PUT",
      url: "/me/profile-photo",
      headers: {
        ...bearer(inviter.accessToken),
        "content-type": upload.contentType,
      },
      payload: upload.body,
    })

    expect(response.statusCode).toBe(200)
    expect(response.json().userPhotoUrl).toMatch(/^https:\/\/storage\.example\.test\/profile-photos\//)

    const recipientBootstrap = await app.inject({
      method: "GET",
      url: "/bootstrap",
      headers: bearer(recipient.accessToken),
    })
    expect(recipientBootstrap.statusCode).toBe(200)
    expect(recipientBootstrap.json().partnerPhotoUrl).toBe(response.json().userPhotoUrl)
  })

  it("creates a six digit invite code", async () => {
    const auth = await signIn("subhajit@elaris.dev", "Subhajit")
    await completeProfile(auth.accessToken, "Subhajit", "Ankita", "2026-01-01")

    const response = await app.inject({
      method: "POST",
      url: "/invites",
      headers: bearer(auth.accessToken),
    })

    expect(response.statusCode).toBe(200)
    const body = response.json()
    expect(body.code).toMatch(/^\d{6}$/)
    expect(body.expiresAt).toBeTruthy()
  })

  it("returns only published wallpapers in stable cursor pages", async () => {
    await db.query(
      `
        INSERT INTO wallpapers (
          id,
          title,
          description,
          storage_path,
          thumbnail_path,
          preview_path,
          full_path,
          width,
          height,
          dominant_color,
          sort_order,
          published_at,
          created_at
        )
        VALUES
          ('wallpaper-a', 'A', 'First', 'a/original.webp', 'a/thumb.webp', 'a/preview.webp', 'a/full.webp', 1000, 1600, '#111111', 0, NOW(), '2026-01-01T00:00:03Z'),
          ('wallpaper-b', 'B', 'Second', 'b/original.webp', 'b/thumb.webp', 'b/preview.webp', 'b/full.webp', 1000, 1600, '#222222', 0, NOW(), '2026-01-01T00:00:02Z'),
          ('wallpaper-c', 'C', 'Third', 'c/original.webp', 'c/thumb.webp', 'c/preview.webp', 'c/full.webp', 1000, 1600, '#333333', 1, NOW(), '2026-01-01T00:00:01Z'),
          ('wallpaper-hidden', 'Hidden', 'Hidden', 'h/original.webp', 'h/thumb.webp', 'h/preview.webp', 'h/full.webp', 1000, 1600, '#444444', 0, NULL, '2026-01-01T00:00:04Z')
      `,
    )

    const firstPage = await app.inject({
      method: "GET",
      url: "/wallpapers?limit=2",
    })

    expect(firstPage.statusCode).toBe(200)
    expect(firstPage.json().items.map((item: { id: string }) => item.id)).toEqual([
      "wallpaper-a",
      "wallpaper-b",
    ])
    expect(firstPage.json().nextCursor).toBeTruthy()

    const secondPage = await app.inject({
      method: "GET",
      url: `/wallpapers?limit=2&cursor=${encodeURIComponent(firstPage.json().nextCursor)}`,
    })

    expect(secondPage.statusCode).toBe(200)
    expect(secondPage.json().items.map((item: { id: string }) => item.id)).toEqual([
      "wallpaper-c",
    ])
    expect(secondPage.json().nextCursor).toBeNull()
  })

  it("requires the admin password for wallpaper admin routes", async () => {
    const response = await app.inject({
      method: "PATCH",
      url: "/admin/wallpapers/missing",
      payload: {
        title: "Updated",
      },
    })

    expect(response.statusCode).toBe(401)
  })

  it("uploads a wallpaper and stores generated variants", async () => {
    const multipart = createMultipartPayload({
      title: "Mulberry Glow",
      description: "Dark red abstract waves",
      sortOrder: "3",
      published: "true",
      fileName: "wallpaper.png",
      contentType: "image/png",
      fileBody: Buffer.from("fake image"),
    })

    const response = await app.inject({
      method: "POST",
      url: "/admin/wallpapers",
      headers: {
        "x-wallpaper-admin-password": "admin-password",
        "content-type": multipart.contentType,
      },
      payload: multipart.body,
    })

    expect(response.statusCode).toBe(200)
    const body = response.json()
    expect(body.title).toBe("Mulberry Glow")
    expect(body.thumbnailUrl).toContain("/thumbnail.webp")

    const rows = await db.query<{ count: string }>("SELECT COUNT(*)::text AS count FROM wallpapers")
    expect(rows.rows[0].count).toBe("1")
  })

  it("lists unpublished wallpapers for admin and supports publish updates", async () => {
    await db.query(
      `
        INSERT INTO wallpapers (
          id,
          title,
          description,
          storage_path,
          thumbnail_path,
          preview_path,
          full_path,
          width,
          height,
          dominant_color,
          sort_order,
          published_at
        )
        VALUES ('draft-wallpaper', 'Draft', 'Draft wallpaper', 'd/original.webp', 'd/thumb.webp', 'd/preview.webp', 'd/full.webp', 1000, 1600, '#111111', 7, NULL)
      `,
    )

    const adminList = await app.inject({
      method: "GET",
      url: "/admin/wallpapers",
      headers: {
        "x-wallpaper-admin-password": "admin-password",
      },
    })

    expect(adminList.statusCode).toBe(200)
    expect(adminList.json().items[0].published).toBe(false)

    const update = await app.inject({
      method: "PATCH",
      url: "/admin/wallpapers/draft-wallpaper",
      headers: {
        "x-wallpaper-admin-password": "admin-password",
      },
      payload: {
        title: "Published Draft",
        published: true,
      },
    })

    expect(update.statusCode).toBe(200)
    expect(update.json().title).toBe("Published Draft")
  })

  it("deletes a wallpaper and removes its storage assets", async () => {
    await db.query(
      `
        INSERT INTO wallpapers (
          id,
          title,
          description,
          storage_path,
          thumbnail_path,
          preview_path,
          full_path,
          width,
          height,
          dominant_color,
          sort_order,
          published_at
        )
        VALUES ('delete-wallpaper', 'Delete me', 'To be removed', 'x/original.webp', 'x/thumb.webp', 'x/preview.webp', 'x/full.webp', 1000, 1600, '#111111', 0, NOW())
      `,
    )

    const response = await app.inject({
      method: "DELETE",
      url: "/admin/wallpapers/delete-wallpaper",
      headers: {
        "x-wallpaper-admin-password": "admin-password",
      },
    })

    expect(response.statusCode).toBe(204)
    const rows = await db.query<{ count: string }>(
      "SELECT COUNT(*)::text AS count FROM wallpapers WHERE id = 'delete-wallpaper'",
    )
    expect(rows.rows[0].count).toBe("0")
    expect(wallpaperStorage.removedPaths).toEqual([
      "x/original.webp",
      "x/thumb.webp",
      "x/preview.webp",
      "x/full.webp",
    ])
  })

  it("redeems and accepts an invite into a paired session", async () => {
    const inviterPhoto = "https://example.test/subhajit.png"
    const recipientPhoto = "https://example.test/ankita.png"
    const inviter = await signIn("subhajit@elaris.dev", "Subhajit", inviterPhoto)
    await completeProfile(inviter.accessToken, "Subhajit", "Ankita", "2026-01-01")
    const createInvite = await app.inject({
      method: "POST",
      url: "/invites",
      headers: bearer(inviter.accessToken),
    })
    const invite = createInvite.json()

    const recipient = await signIn("ankita@elaris.dev", "Ankita", recipientPhoto)
    await completeProfile(recipient.accessToken, "Ankita", "Subhajit", "2026-01-01")
    const redeem = await app.inject({
      method: "POST",
      url: "/invites/redeem",
      headers: bearer(recipient.accessToken),
      payload: {
        code: invite.code,
      },
    })

    expect(redeem.statusCode).toBe(200)
    expect(redeem.json().status).toBe("REDEEMED")

    const accept = await app.inject({
      method: "POST",
      url: `/invites/${invite.inviteId}/accept`,
      headers: bearer(recipient.accessToken),
    })

    expect(accept.statusCode).toBe(200)
    expect(accept.json().pairSessionId).toBeTruthy()
    expect(accept.json().bootstrapState.pairingStatus).toBe("PAIRED")
    expect(accept.json().bootstrapState.partnerPhotoUrl).toBe(inviterPhoto)

    const inviterBootstrap = await app.inject({
      method: "GET",
      url: "/bootstrap",
      headers: bearer(inviter.accessToken),
    })
    expect(inviterBootstrap.statusCode).toBe(200)
    expect(inviterBootstrap.json().partnerPhotoUrl).toBe(recipientPhoto)
  })

  it("pushes a pairing confirmation to the inviter when the invitee accepts", async () => {
    const inviter = await signIn("subhajit@elaris.dev", "Subhajit")
    await completeProfile(inviter.accessToken, "Subhajit", "Ankita", "2026-01-01")
    await registerFcmToken(inviter.accessToken, "inviter-token")
    const createInvite = await app.inject({
      method: "POST",
      url: "/invites",
      headers: bearer(inviter.accessToken),
    })
    const invite = createInvite.json()

    const recipient = await signIn("ankita@elaris.dev", "Ankita")
    await completeProfile(recipient.accessToken, "Ankita", "Subhajit", "2026-01-01")
    await app.inject({
      method: "POST",
      url: "/invites/redeem",
      headers: bearer(recipient.accessToken),
      payload: { code: invite.code },
    })
    const accept = await app.inject({
      method: "POST",
      url: `/invites/${invite.inviteId}/accept`,
      headers: bearer(recipient.accessToken),
    })

    expect(accept.statusCode).toBe(200)
    await eventually(() => pushSender.sentMessages.length === 1)
    expect(pushSender.sentMessages[0]).toMatchObject({
      tokens: ["inviter-token"],
      data: {
        type: "PAIRING_CONFIRMED",
        pairSessionId: accept.json().pairSessionId,
        actorUserId: recipient.userId,
        actorDisplayName: "Ankita",
      },
      android: {
        priority: "high",
        collapseKey: `pairing-${accept.json().pairSessionId}`,
        ttlMs: 60_000,
      },
    })
  })

  it("sends a debug pairing confirmation push to the paired peer", async () => {
    const { inviter, recipient } = await pairUsers()
    await registerFcmToken(recipient.accessToken, "recipient-token")

    const response = await app.inject({
      method: "POST",
      url: "/debug/pairing-confirmation-push",
      headers: bearer(inviter.accessToken),
    })

    expect(response.statusCode).toBe(200)
    await eventually(() => pushSender.sentMessages.length === 1)
    expect(pushSender.sentMessages[0]).toMatchObject({
      tokens: ["recipient-token"],
      data: {
        type: "PAIRING_CONFIRMED",
        pairSessionId: inviter.pairSessionId,
        actorUserId: inviter.userId,
        actorDisplayName: "Subhajit",
      },
    })
  })

  it("pushes a pairing disconnected to the peer when a user disconnects", async () => {
    const { inviter, recipient } = await pairUsers()
    await registerFcmToken(recipient.accessToken, "recipient-token")

    const disconnect = await app.inject({
      method: "POST",
      url: "/pairing/disconnect",
      headers: bearer(inviter.accessToken),
    })

    expect(disconnect.statusCode).toBe(200)
    await eventually(() => pushSender.sentMessages.length === 1)
    expect(pushSender.sentMessages[0]).toMatchObject({
      tokens: ["recipient-token"],
      data: {
        type: "PAIRING_DISCONNECTED",
        pairSessionId: inviter.pairSessionId,
        actorUserId: inviter.userId,
        actorDisplayName: "Subhajit",
      },
      android: {
        priority: "high",
        collapseKey: `pairing-${inviter.pairSessionId}`,
        ttlMs: 60_000,
      },
    })
  })

  it("sends a debug pairing disconnected push to the paired peer", async () => {
    const { inviter, recipient } = await pairUsers()
    await registerFcmToken(recipient.accessToken, "recipient-token")

    const response = await app.inject({
      method: "POST",
      url: "/debug/pairing-disconnected-push",
      headers: bearer(inviter.accessToken),
    })

    expect(response.statusCode).toBe(200)
    await eventually(() => pushSender.sentMessages.length === 1)
    expect(pushSender.sentMessages[0]).toMatchObject({
      tokens: ["recipient-token"],
      data: {
        type: "PAIRING_DISCONNECTED",
        pairSessionId: inviter.pairSessionId,
        actorUserId: inviter.userId,
        actorDisplayName: "Subhajit",
      },
    })
  })

  it("initializes per-user draw reminders when pairing is created", async () => {
    const { inviter, recipient } = await pairUsers()

    const reminders = await db.query<{ user_id: string }>(
      `
      SELECT user_id
      FROM draw_reminders
      WHERE pair_session_id = $1
      ORDER BY user_id ASC
      `,
      [inviter.pairSessionId],
    )

    expect(reminders.rows.map((row) => row.user_id).sort()).toEqual(
      [inviter.userId, recipient.userId].sort(),
    )
  })

  it("resets the user's draw reminder schedule when they finish a stroke", async () => {
    const { inviter } = await pairUsers()

    const before = await db.query<{ due_at: string | Date; reminder_count: number }>(
      `
      SELECT due_at, reminder_count
      FROM draw_reminders
      WHERE pair_session_id = $1 AND user_id = $2
      LIMIT 1
      `,
      [inviter.pairSessionId, inviter.userId],
    )
    expect(before.rows[0]).toBeTruthy()
    expect(before.rows[0].reminder_count).toBe(0)
    const beforeDue = new Date(before.rows[0].due_at).getTime()

    const socket = await app.injectWS("/canvas/sync")
    socket.send(
      JSON.stringify({
        type: "HELLO",
        accessToken: inviter.accessToken,
        pairSessionId: inviter.pairSessionId,
        lastAppliedServerRevision: 0,
      }),
    )
    await nextWsJson(socket)
    socket.send(
      JSON.stringify({
        type: "CLIENT_OP_BATCH",
        batchId: "draw-reminder-batch",
        clientCreatedAt: new Date().toISOString(),
        operations: [
          addStrokeOperation("draw-reminder-op-1", "draw-reminder-stroke-1", 1, 2),
          {
            clientOperationId: "draw-reminder-op-2",
            type: "FINISH_STROKE",
            strokeId: "draw-reminder-stroke-1",
            payload: {},
            clientCreatedAt: new Date().toISOString(),
          },
        ],
      }),
    )
    await nextWsJson(socket)
    socket.close()

    const after = await db.query<{ due_at: string | Date; reminder_count: number }>(
      `
      SELECT due_at, reminder_count
      FROM draw_reminders
      WHERE pair_session_id = $1 AND user_id = $2
      LIMIT 1
      `,
      [inviter.pairSessionId, inviter.userId],
    )
    expect(after.rows[0]).toBeTruthy()
    expect(after.rows[0].reminder_count).toBe(0)
    const afterDue = new Date(after.rows[0].due_at).getTime()
    expect(afterDue).toBeGreaterThan(beforeDue)
  })

  it("dispatches draw reminder pushes to the idle user and reschedules with backoff", async () => {
    const memoryDb = newDb({
      autoCreateForeignKeyIndices: true,
    })
    const adapter = memoryDb.adapters.createPg()
    const pool = new adapter.Pool()
    await runMigrations(pool)
    const localDb: Database = {
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

    const sender = new RecordingPushSender()
    const dispatch = new PushDispatchService(localDb, sender, {
      debounceMs: 0,
      canvasNudgePollIntervalMs: 1_000_000,
      drawReminderPollIntervalMs: 10,
      drawReminderTtlMs: 60_000,
      drawReminderMaxBackoffDays: 7,
    })

    const pairSessionId = "pair-1"
    const userOneId = "user-1"
    const userTwoId = "user-2"

    await localDb.query(
      `
      INSERT INTO users (id, google_subject, email)
      VALUES ($1, $2, $3), ($4, $5, $6)
      `,
      [userOneId, "sub-1", "one@example.test", userTwoId, "sub-2", "two@example.test"],
    )
    await localDb.query(
      `
      INSERT INTO user_profiles (user_id, display_name, partner_display_name)
      VALUES ($1, $2, $3), ($4, $5, $6)
      `,
      [userOneId, "One", "Two", userTwoId, "Two", "One"],
    )
    await localDb.query(
      `
      INSERT INTO pair_sessions (id, user_one_id, user_two_id)
      VALUES ($1, $2, $3)
      `,
      [pairSessionId, userOneId, userTwoId],
    )
    await localDb.query(
      `
      INSERT INTO device_tokens (id, user_id, token, platform, app_environment)
      VALUES ($1, $2, $3, 'ANDROID', 'dev')
      `,
      ["token-1", userOneId, "user-one-token"],
    )

    await localDb.query(
      `
      INSERT INTO draw_reminders (pair_session_id, user_id, last_draw_at, reminder_count, due_at)
      VALUES ($1, $2, NOW(), 0, NOW())
      `,
      [pairSessionId, userOneId],
    )

    await eventually(() => sender.sentMessages.some((msg) => msg.data.type === "DRAW_REMINDER"))
    const message = sender.sentMessages.find((msg) => msg.data.type === "DRAW_REMINDER")
    expect(message).toBeTruthy()
    expect(message).toMatchObject({
      tokens: ["user-one-token"],
      data: {
        type: "DRAW_REMINDER",
        pairSessionId,
        partnerDisplayName: "Two",
        reminderCount: "0",
      },
      android: {
        priority: "high",
        collapseKey: `draw-reminder-${pairSessionId}-${userOneId}`,
      },
    })

    const reminderRow = await localDb.query<{ reminder_count: number; due_at: string | Date }>(
      `
      SELECT reminder_count, due_at
      FROM draw_reminders
      WHERE pair_session_id = $1 AND user_id = $2
      LIMIT 1
      `,
      [pairSessionId, userOneId],
    )
    expect(reminderRow.rows[0]?.reminder_count).toBe(1)

    dispatch.dispose()
    await localDb.end()
  })

  it("disconnects a paired session while clearing active partner metadata", async () => {
    const { inviter, recipient } = await pairUsers()

    const disconnect = await app.inject({
      method: "POST",
      url: "/pairing/disconnect",
      headers: bearer(inviter.accessToken),
    })

    expect(disconnect.statusCode).toBe(200)
    expect(disconnect.json().pairingStatus).toBe("UNPAIRED")
    expect(disconnect.json().onboardingCompleted).toBe(true)
    expect(disconnect.json().userDisplayName).toBe("Subhajit")
    expect(disconnect.json().partnerDisplayName).toBeNull()
    expect(disconnect.json().anniversaryDate).toBeNull()

    const recipientBootstrap = await app.inject({
      method: "GET",
      url: "/bootstrap",
      headers: bearer(recipient.accessToken),
    })
    expect(recipientBootstrap.statusCode).toBe(200)
    expect(recipientBootstrap.json().pairingStatus).toBe("UNPAIRED")
    expect(recipientBootstrap.json().onboardingCompleted).toBe(true)
    expect(recipientBootstrap.json().userDisplayName).toBe("Ankita")
    expect(recipientBootstrap.json().partnerDisplayName).toBeNull()
    expect(recipientBootstrap.json().anniversaryDate).toBeNull()

    const secondDisconnect = await app.inject({
      method: "POST",
      url: "/pairing/disconnect",
      headers: bearer(inviter.accessToken),
    })
    expect(secondDisconnect.statusCode).toBe(400)
  })

  it("requires fresh partner details before inviting after disconnect", async () => {
    const { inviter } = await pairUsers()

    const disconnect = await app.inject({
      method: "POST",
      url: "/pairing/disconnect",
      headers: bearer(inviter.accessToken),
    })
    expect(disconnect.statusCode).toBe(200)

    const blockedInvite = await app.inject({
      method: "POST",
      url: "/invites",
      headers: bearer(inviter.accessToken),
    })
    expect(blockedInvite.statusCode).toBe(400)
    expect(blockedInvite.json().message).toBe("Partner details are required before creating an invite")

    const updatePartner = await updatePartnerProfile(inviter.accessToken, "Priya", "2026-02-14")
    expect(updatePartner.statusCode).toBe(200)
    expect(updatePartner.json().partnerDisplayName).toBe("Priya")
    expect(updatePartner.json().anniversaryDate).toBe("2026-02-14")

    const createInvite = await app.inject({
      method: "POST",
      url: "/invites",
      headers: bearer(inviter.accessToken),
    })
    expect(createInvite.statusCode).toBe(200)

    const recipient = await signIn("priya@elaris.dev", "Priya Google")
    const redeem = await app.inject({
      method: "POST",
      url: "/invites/redeem",
      headers: bearer(recipient.accessToken),
      payload: { code: createInvite.json().code },
    })
    expect(redeem.statusCode).toBe(200)
    expect(redeem.json().bootstrapState.userDisplayName).toBe("Priya")
    expect(redeem.json().bootstrapState.partnerDisplayName).toBe("Subhajit")
    expect(redeem.json().bootstrapState.anniversaryDate).toBe("2026-02-14")
  })

  it("allows unpaired users to update partner details without cooldown", async () => {
    const auth = await signIn("subhajit-unpaired-edit@elaris.dev", "Subhajit")
    await completeProfile(auth.accessToken, "Subhajit", "Ankita", "2026-01-01")

    const first = await updatePartnerProfile(auth.accessToken, "Ankita Nandi", "2026-01-02")
    expect(first.statusCode).toBe(200)
    expect(first.json().partnerProfileNextUpdateAt).toBeNull()

    const second = await updatePartnerProfile(auth.accessToken, "Priya", "2026-02-14")
    expect(second.statusCode).toBe(200)
    expect(second.json().partnerDisplayName).toBe("Priya")
    expect(second.json().anniversaryDate).toBe("2026-02-14")
    expect(second.json().partnerProfileNextUpdateAt).toBeNull()
  })

  it("enforces partner detail update cooldown only while paired", async () => {
    const { inviter, recipient } = await pairUsers()

    const first = await updatePartnerProfile(inviter.accessToken, "Ankita Nandi", "2026-01-02")
    expect(first.statusCode).toBe(200)
    expect(first.json().partnerDisplayName).toBe("Ankita Nandi")
    expect(first.json().partnerProfileNextUpdateAt).toBeTruthy()

    const recipientBootstrap = await app.inject({
      method: "GET",
      url: "/bootstrap",
      headers: bearer(recipient.accessToken),
    })
    expect(recipientBootstrap.statusCode).toBe(200)
    expect(recipientBootstrap.json().userDisplayName).toBe("Ankita Nandi")
    expect(recipientBootstrap.json().anniversaryDate).toBe("2026-01-02")

    const blocked = await updatePartnerProfile(inviter.accessToken, "Ankita", "2026-01-03")
    expect(blocked.statusCode).toBe(409)
    expect(blocked.json().message).toMatch(/^Partner details can be updated again at /)

    await db.query(
      `
      UPDATE user_profiles
      SET partner_profile_updated_at = NOW() - INTERVAL '73 hours'
      WHERE user_id = $1
      `,
      [inviter.userId],
    )

    const afterCooldown = await updatePartnerProfile(inviter.accessToken, "Ankita", "2026-01-03")
    expect(afterCooldown.statusCode).toBe(200)
    expect(afterCooldown.json().partnerDisplayName).toBe("Ankita")
    expect(afterCooldown.json().anniversaryDate).toBe("2026-01-03")
  })

  it("applies partner photo uploads through shared identity and cooldown", async () => {
    const { inviter, recipient } = await pairUsers()

    const firstUpload = createImageUploadPayload()
    const first = await app.inject({
      method: "PUT",
      url: "/me/partner-profile-photo",
      headers: {
        ...bearer(inviter.accessToken),
        "content-type": firstUpload.contentType,
      },
      payload: firstUpload.body,
    })
    expect(first.statusCode).toBe(200)
    expect(first.json().partnerPhotoUrl).toMatch(/^https:\/\/storage\.example\.test\/profile-photos\//)
    expect(first.json().partnerProfileNextUpdateAt).toBeTruthy()

    const recipientBootstrap = await app.inject({
      method: "GET",
      url: "/bootstrap",
      headers: bearer(recipient.accessToken),
    })
    expect(recipientBootstrap.statusCode).toBe(200)
    expect(recipientBootstrap.json().userPhotoUrl).toBe(first.json().partnerPhotoUrl)

    const blockedUpload = createImageUploadPayload()
    const blocked = await app.inject({
      method: "PUT",
      url: "/me/partner-profile-photo",
      headers: {
        ...bearer(inviter.accessToken),
        "content-type": blockedUpload.contentType,
      },
      payload: blockedUpload.body,
    })
    expect(blocked.statusCode).toBe(409)
  })

  it("does not reuse an active invite when partner details have been cleared", async () => {
    const auth = await signIn("subhajit-active-invite@elaris.dev", "Subhajit")
    await completeProfile(auth.accessToken, "Subhajit", "Ankita", "2026-01-01")
    const createInvite = await app.inject({
      method: "POST",
      url: "/invites",
      headers: bearer(auth.accessToken),
    })
    expect(createInvite.statusCode).toBe(200)

    await db.query(
      `
      UPDATE user_profiles
      SET partner_display_name = NULL, anniversary_date = NULL
      WHERE user_id = $1
      `,
      [auth.userId],
    )

    const retry = await app.inject({
      method: "POST",
      url: "/invites",
      headers: bearer(auth.accessToken),
    })
    expect(retry.statusCode).toBe(400)
    expect(retry.json().message).toBe("Partner details are required before creating an invite")
  })

  it("hydrates invitee onboarding from inviter profile when code is redeemed during onboarding", async () => {
    const inviter = await signIn("subhajit@elaris.dev", "Subhajit")
    await completeProfile(inviter.accessToken, "Subhajit", "Ankita", "2026-01-01")
    const createInvite = await app.inject({
      method: "POST",
      url: "/invites",
      headers: bearer(inviter.accessToken),
    })
    const invite = createInvite.json()

    const recipient = await signIn("ankita@elaris.dev", "Ankita Google")
    const redeem = await app.inject({
      method: "POST",
      url: "/invites/redeem",
      headers: bearer(recipient.accessToken),
      payload: {
        code: invite.code,
      },
    })

    expect(redeem.statusCode).toBe(200)
    const body = redeem.json()
    expect(body.bootstrapState.onboardingCompleted).toBe(true)
    expect(body.bootstrapState.pairingStatus).toBe("INVITE_PENDING_ACCEPTANCE")
    expect(body.bootstrapState.userDisplayName).toBe("Ankita")
    expect(body.bootstrapState.partnerDisplayName).toBe("Subhajit")
    expect(body.bootstrapState.anniversaryDate).toBe("2026-01-01")
    expect(body.bootstrapState.invite.inviterDisplayName).toBe("Subhajit")
    expect(body.bootstrapState.invite.recipientDisplayName).toBe("Ankita")

    const retry = await app.inject({
      method: "POST",
      url: "/invites/redeem",
      headers: bearer(recipient.accessToken),
      payload: {
        code: invite.code,
      },
    })

    expect(retry.statusCode).toBe(200)
    expect(retry.json().bootstrapState.pairingStatus).toBe("INVITE_PENDING_ACCEPTANCE")
  })

  it("updates an already-onboarded invitee profile from inviter partner details", async () => {
    const inviter = await signIn("subhajit-home-invite@elaris.dev", "Subhajit")
    await completeProfile(inviter.accessToken, "Subhajit", "Old Partner", "2026-01-01")
    const updatePartner = await updatePartnerProfile(inviter.accessToken, "Priya", "2026-02-14")
    expect(updatePartner.statusCode).toBe(200)

    const createInvite = await app.inject({
      method: "POST",
      url: "/invites",
      headers: bearer(inviter.accessToken),
    })
    expect(createInvite.statusCode).toBe(200)
    const invite = createInvite.json()

    const recipient = await signIn("priya-home@elaris.dev", "Priya Google")
    await completeProfile(recipient.accessToken, "Existing Name", "Someone", "2026-01-01")
    const redeem = await app.inject({
      method: "POST",
      url: "/invites/redeem",
      headers: bearer(recipient.accessToken),
      payload: { code: invite.code },
    })

    expect(redeem.statusCode).toBe(200)
    expect(redeem.json().bootstrapState.userDisplayName).toBe("Priya")
    expect(redeem.json().bootstrapState.partnerDisplayName).toBe("Subhajit")
    expect(redeem.json().bootstrapState.anniversaryDate).toBe("2026-02-14")

    const accept = await app.inject({
      method: "POST",
      url: `/invites/${invite.inviteId}/accept`,
      headers: bearer(recipient.accessToken),
    })
    expect(accept.statusCode).toBe(200)
    expect(accept.json().bootstrapState.userDisplayName).toBe("Priya")
    expect(accept.json().bootstrapState.partnerDisplayName).toBe("Subhajit")
    expect(accept.json().bootstrapState.anniversaryDate).toBe("2026-02-14")
  })

  it("hydrates invitee profile photo from inviter partner photo metadata", async () => {
    const inviter = await signIn("subhajit-photo-invite@elaris.dev", "Subhajit")
    await completeProfile(inviter.accessToken, "Subhajit", "Priya", "2026-02-14")
    const photo = createImageUploadPayload()
    const upload = await app.inject({
      method: "PUT",
      url: "/me/partner-profile-photo",
      headers: {
        ...bearer(inviter.accessToken),
        "content-type": photo.contentType,
      },
      payload: photo.body,
    })
    expect(upload.statusCode).toBe(200)

    const createInvite = await app.inject({
      method: "POST",
      url: "/invites",
      headers: bearer(inviter.accessToken),
    })
    expect(createInvite.statusCode).toBe(200)

    const recipient = await signIn("priya-photo-invite@elaris.dev", "Priya Google")
    await completeProfile(recipient.accessToken, "Existing Name", "Someone", "2026-01-01")
    const redeem = await app.inject({
      method: "POST",
      url: "/invites/redeem",
      headers: bearer(recipient.accessToken),
      payload: { code: createInvite.json().code },
    })

    expect(redeem.statusCode).toBe(200)
    expect(redeem.json().bootstrapState.userPhotoUrl).toBe(upload.json().partnerPhotoUrl)
  })

  it("declining an invite clears invite-derived onboarding and returns to onboarding", async () => {
    const inviter = await signIn("subhajit@elaris.dev", "Subhajit")
    await completeProfile(inviter.accessToken, "Subhajit", "Ankita", "2026-01-01")
    const createInvite = await app.inject({
      method: "POST",
      url: "/invites",
      headers: bearer(inviter.accessToken),
    })
    const invite = createInvite.json()
    const recipient = await signIn("ankita@elaris.dev", "Ankita Google")
    await app.inject({
      method: "POST",
      url: "/invites/redeem",
      headers: bearer(recipient.accessToken),
      payload: {
        code: invite.code,
      },
    })

    const decline = await app.inject({
      method: "POST",
      url: `/invites/${invite.inviteId}/decline`,
      headers: bearer(recipient.accessToken),
    })

    expect(decline.statusCode).toBe(200)
    const body = decline.json()
    expect(body.onboardingCompleted).toBe(false)
    expect(body.pairingStatus).toBe("UNPAIRED")
    expect(body.userDisplayName).toBeNull()
    expect(body.partnerDisplayName).toBeNull()
    expect(body.anniversaryDate).toBeNull()
  })

  it("rejects expired and already consumed invite codes deterministically", async () => {
    const inviter = await signIn("subhajit@elaris.dev", "Subhajit")
    await completeProfile(inviter.accessToken, "Subhajit", "Ankita", "2026-01-01")
    const createInvite = await app.inject({
      method: "POST",
      url: "/invites",
      headers: bearer(inviter.accessToken),
    })
    const invite = createInvite.json()

    await db.query(
      `UPDATE invites SET expires_at = NOW() - INTERVAL '1 day' WHERE id = $1`,
      [invite.inviteId],
    )

    const recipient = await signIn("ankita@elaris.dev", "Ankita")
    const expiredRedeem = await app.inject({
      method: "POST",
      url: "/invites/redeem",
      headers: bearer(recipient.accessToken),
      payload: {
        code: invite.code,
      },
    })

    expect(expiredRedeem.statusCode).toBe(400)

    const secondInviteResponse = await app.inject({
      method: "POST",
      url: "/invites",
      headers: bearer(inviter.accessToken),
    })
    const secondInvite = secondInviteResponse.json()

    await completeProfile(recipient.accessToken, "Ankita", "Subhajit", "2026-01-01")
    await app.inject({
      method: "POST",
      url: "/invites/redeem",
      headers: bearer(recipient.accessToken),
      payload: {
        code: secondInvite.code,
      },
    })
    await app.inject({
      method: "POST",
      url: `/invites/${secondInvite.inviteId}/accept`,
      headers: bearer(recipient.accessToken),
    })

    const thirdUser = await signIn("third@elaris.dev", "Third")
    const consumedRedeem = await app.inject({
      method: "POST",
      url: "/invites/redeem",
      headers: bearer(thirdUser.accessToken),
      payload: {
        code: secondInvite.code,
      },
    })

    expect(consumedRedeem.statusCode).toBe(400)
  })

  it("persists canvas operations with monotonic revisions and deduplicates client retries", async () => {
    const { inviter } = await pairUsers()
    const operation = {
      clientOperationId: "client-op-1",
      type: "ADD_STROKE",
      strokeId: "stroke-1",
      payload: {
        id: "stroke-1",
        colorArgb: 4278190080,
        width: 8,
        createdAt: 123,
        firstPoint: { x: 10, y: 20 },
      },
      clientCreatedAt: new Date().toISOString(),
    }

    const first = await app.injectWS("/canvas/sync")
    first.send(
      JSON.stringify({
        type: "HELLO",
        accessToken: inviter.accessToken,
        pairSessionId: inviter.pairSessionId,
        lastAppliedServerRevision: 0,
      }),
    )
    await nextWsJson(first)
    first.send(JSON.stringify({ type: "CLIENT_OP", operation }))
    const ack = await nextWsJson(first)
    expect(ack.type).toBe("ACK")
    expect(ack.serverRevision).toBe(1)

    first.send(JSON.stringify({ type: "CLIENT_OP", operation }))
    const duplicateAck = await nextWsJson(first)
    expect(duplicateAck.serverRevision).toBe(1)

    const ops = await app.inject({
      method: "GET",
      url: "/canvas/ops?afterRevision=0",
      headers: bearer(inviter.accessToken),
    })
    expect(ops.statusCode).toBe(200)
    expect(ops.json().operations).toHaveLength(1)
    first.close()
  })

  it("broadcasts accepted canvas operations to the paired peer", async () => {
    const { inviter, recipient } = await pairUsers()
    const first = await app.injectWS("/canvas/sync")
    const second = await app.injectWS("/canvas/sync")

    first.send(
      JSON.stringify({
        type: "HELLO",
        accessToken: inviter.accessToken,
        pairSessionId: inviter.pairSessionId,
        lastAppliedServerRevision: 0,
      }),
    )
    second.send(
      JSON.stringify({
        type: "HELLO",
        accessToken: recipient.accessToken,
        pairSessionId: recipient.pairSessionId,
        lastAppliedServerRevision: 0,
      }),
    )
    await nextWsJson(first)
    await nextWsJson(second)

    const peerMessage = nextWsJson(second)
    first.send(
      JSON.stringify({
        type: "CLIENT_OP",
        operation: {
          clientOperationId: "client-op-broadcast",
          type: "CLEAR_CANVAS",
          strokeId: null,
          payload: {},
          clientCreatedAt: new Date().toISOString(),
        },
      }),
    )

    const ack = await nextWsJson(first)
    const serverOp = await peerMessage
    expect(ack.type).toBe("ACK")
    expect(serverOp.type).toBe("SERVER_OP")
    expect(serverOp.operation.serverRevision).toBe(1)
    await expectNoWsJson(first)
    first.close()
    second.close()
  })

  it("broadcasts accepted canvas batches only to the paired peer", async () => {
    const { inviter, recipient } = await pairUsers()
    const first = await app.injectWS("/canvas/sync")
    const second = await app.injectWS("/canvas/sync")

    first.send(
      JSON.stringify({
        type: "HELLO",
        accessToken: inviter.accessToken,
        pairSessionId: inviter.pairSessionId,
        lastAppliedServerRevision: 0,
      }),
    )
    second.send(
      JSON.stringify({
        type: "HELLO",
        accessToken: recipient.accessToken,
        pairSessionId: recipient.pairSessionId,
        lastAppliedServerRevision: 0,
      }),
    )
    await nextWsJson(first)
    await nextWsJson(second)

    const senderMessages = nextNWsJson(first, 2)
    const peerMessage = nextWsJson(second)
    first.send(
      JSON.stringify({
        type: "CLIENT_OP_BATCH",
        batchId: "peer-only-batch",
        clientCreatedAt: new Date().toISOString(),
        operations: [
          addStrokeOperation("peer-only-op-1", "peer-only-stroke", 1, 1),
          finishStrokeOperation("peer-only-op-2", "peer-only-stroke"),
        ],
      }),
    )

    const [messages, serverBatch] = await Promise.all([senderMessages, peerMessage])
    expect(messages.map((message) => message.type)).toEqual(["ACK_BATCH", "FLOW_CONTROL"])
    expect(serverBatch.type).toBe("SERVER_OP_BATCH")
    expect(serverBatch.operations.map((operation: { serverRevision: number }) => operation.serverRevision))
      .toEqual([1, 2])
    await expectNoWsJson(first)
    first.close()
    second.close()
  })

  it("accepts canvas operation batches with contiguous revisions", async () => {
    const { inviter } = await pairUsers()
    const first = await app.injectWS("/canvas/sync")

    first.send(
      JSON.stringify({
        type: "HELLO",
        accessToken: inviter.accessToken,
        pairSessionId: inviter.pairSessionId,
        lastAppliedServerRevision: 0,
      }),
    )
    await nextWsJson(first)
    first.send(
      JSON.stringify({
        type: "CLIENT_OP_BATCH",
        batchId: "batch-1",
        clientCreatedAt: new Date().toISOString(),
        operations: [
          {
            clientOperationId: "batch-op-1",
            type: "ADD_STROKE",
            strokeId: "batch-stroke-1",
            payload: {
              id: "batch-stroke-1",
              colorArgb: 4278190080,
              width: 8,
              createdAt: 123,
              firstPoint: { x: 1, y: 2 },
            },
            clientCreatedAt: new Date().toISOString(),
          },
          {
            clientOperationId: "batch-op-2",
            type: "FINISH_STROKE",
            strokeId: "batch-stroke-1",
            payload: {},
            clientCreatedAt: new Date().toISOString(),
          },
        ],
      }),
    )

    const ack = await nextWsJson(first)
    expect(ack.type).toBe("ACK_BATCH")
    expect(ack.ackedClientOperationIds).toEqual(["batch-op-1", "batch-op-2"])
    expect(ack.ackedThroughRevision).toBe(2)
    expect(ack.operations.map((operation: { serverRevision: number }) => operation.serverRevision))
      .toEqual([1, 2])
    first.close()
  })

  it("keeps canvas snapshot materialized to the latest current state", async () => {
    const { inviter } = await pairUsers()
    const first = await app.injectWS("/canvas/sync")

    first.send(
      JSON.stringify({
        type: "HELLO",
        accessToken: inviter.accessToken,
        pairSessionId: inviter.pairSessionId,
        lastAppliedServerRevision: 0,
      }),
    )
    await nextWsJson(first)
    first.send(
      JSON.stringify({
        type: "CLIENT_OP_BATCH",
        batchId: "snapshot-batch-1",
        clientCreatedAt: new Date().toISOString(),
        operations: [
          addStrokeOperation("snapshot-op-1", "deleted-stroke", 1, 1),
          appendPointsOperation("snapshot-op-2", "deleted-stroke", [{ x: 2, y: 2 }]),
          finishStrokeOperation("snapshot-op-3", "deleted-stroke"),
          deleteStrokeOperation("snapshot-op-4", "deleted-stroke"),
          clearCanvasOperation("snapshot-op-5"),
          addStrokeOperation("snapshot-op-6", "current-stroke", 9, 9),
          finishStrokeOperation("snapshot-op-7", "current-stroke"),
        ],
      }),
    )

    const ack = await nextWsJson(first)
    expect(ack.type).toBe("ACK_BATCH")
    expect(ack.ackedThroughRevision).toBe(7)

    const snapshot = await app.inject({
      method: "GET",
      url: "/canvas/snapshot",
      headers: bearer(inviter.accessToken),
    })
    const tail = await app.inject({
      method: "GET",
      url: "/canvas/ops?afterRevision=5",
      headers: bearer(inviter.accessToken),
    })

    expect(snapshot.statusCode).toBe(200)
    expect(snapshot.json().revision).toBe(7)
    expect(snapshot.json().snapshotRevision).toBe(7)
    expect(snapshot.json().latestRevision).toBe(7)
    expect(snapshot.json().snapshot.strokes.map((stroke: { id: string }) => stroke.id))
      .toEqual(["current-stroke"])
    expect(snapshot.json().snapshot.textElements ?? []).toEqual([])
    expect(tail.json().operations.map((operation: { serverRevision: number }) => operation.serverRevision))
      .toEqual([6, 7])
    first.close()
  })

  it("defers snapshot materialization for in-progress strokes until finish", async () => {
    const { inviter } = await pairUsers()
    const first = await app.injectWS("/canvas/sync")

    first.send(
      JSON.stringify({
        type: "HELLO",
        accessToken: inviter.accessToken,
        pairSessionId: inviter.pairSessionId,
        lastAppliedServerRevision: 0,
      }),
    )
    await nextWsJson(first)
    first.send(
      JSON.stringify({
        type: "CLIENT_OP_BATCH",
        batchId: "in-progress-batch",
        clientCreatedAt: new Date().toISOString(),
        operations: [
          addStrokeOperation("in-progress-op-1", "in-progress-stroke", 1, 1),
          appendPointsOperation("in-progress-op-2", "in-progress-stroke", [{ x: 2, y: 2 }]),
        ],
      }),
    )

    const inProgressAck = await nextWsJson(first)
    expect(inProgressAck.type).toBe("ACK_BATCH")
    expect(inProgressAck.ackedThroughRevision).toBe(2)

    const inProgressSnapshot = await app.inject({
      method: "GET",
      url: "/canvas/snapshot",
      headers: bearer(inviter.accessToken),
    })

    expect(inProgressSnapshot.statusCode).toBe(200)
    expect(inProgressSnapshot.json().snapshotRevision).toBe(0)
    expect(inProgressSnapshot.json().latestRevision).toBe(2)
    expect(inProgressSnapshot.json().snapshot.strokes).toEqual([])
    expect(inProgressSnapshot.json().snapshot.textElements ?? []).toEqual([])

    first.send(
      JSON.stringify({
        type: "CLIENT_OP_BATCH",
        batchId: "finish-in-progress-batch",
        clientCreatedAt: new Date().toISOString(),
        operations: [finishStrokeOperation("in-progress-op-3", "in-progress-stroke")],
      }),
    )
    await nextWsJson(first)

    const finishedSnapshot = await app.inject({
      method: "GET",
      url: "/canvas/snapshot",
      headers: bearer(inviter.accessToken),
    })
    const stroke = finishedSnapshot.json().snapshot.strokes[0]
    expect(finishedSnapshot.json().snapshotRevision).toBe(3)
    expect(finishedSnapshot.json().latestRevision).toBe(3)
    expect(stroke.points).toEqual([{ x: 1, y: 1 }, { x: 2, y: 2 }])
    expect(finishedSnapshot.json().snapshot.textElements ?? []).toEqual([])
    first.close()
  })

  it("materializes text elements into canvas snapshots", async () => {
    const { inviter } = await pairUsers()
    const first = await app.injectWS("/canvas/sync")

    first.send(
      JSON.stringify({
        type: "HELLO",
        accessToken: inviter.accessToken,
        pairSessionId: inviter.pairSessionId,
        lastAppliedServerRevision: 0,
      }),
    )
    await nextWsJson(first)

    first.send(
      JSON.stringify({
        type: "CLIENT_OP_BATCH",
        batchId: "text-batch-1",
        clientCreatedAt: new Date().toISOString(),
        operations: [
          addTextElementOperation("text-op-1", "text-1", "Hello", 0.5, 0.5),
          updateTextElementOperation("text-op-2", "text-1", "Hello world", 0.5, 0.5),
        ],
      }),
    )

    const ack = await nextWsJson(first)
    expect(ack.type).toBe("ACK_BATCH")
    expect(ack.ackedThroughRevision).toBe(2)

    const snapshotAfterUpsert = await app.inject({
      method: "GET",
      url: "/canvas/snapshot",
      headers: bearer(inviter.accessToken),
    })
    expect(snapshotAfterUpsert.statusCode).toBe(200)
    expect(snapshotAfterUpsert.json().snapshot.textElements.map((element: { id: string }) => element.id))
      .toEqual(["text-1"])
    expect(snapshotAfterUpsert.json().snapshot.textElements[0].text).toBe("Hello world")

    first.send(
      JSON.stringify({
        type: "CLIENT_OP",
        operation: deleteTextElementOperation("text-op-3", "text-1"),
      }),
    )
    const deleteAck = await nextWsJson(first)
    expect(deleteAck.type).toBe("ACK")
    expect(deleteAck.serverRevision).toBe(3)

    const snapshotAfterDelete = await app.inject({
      method: "GET",
      url: "/canvas/snapshot",
      headers: bearer(inviter.accessToken),
    })
    expect(snapshotAfterDelete.statusCode).toBe(200)
    expect(snapshotAfterDelete.json().snapshot.textElements).toEqual([])
    first.close()
  })

  it("registers and revokes FCM device tokens", async () => {
    const auth = await signIn("subhajit@elaris.dev", "Subhajit")

    const first = await app.inject({
      method: "POST",
      url: "/devices/fcm-token",
      headers: bearer(auth.accessToken),
      payload: {
        token: "fcm-token-1",
        platform: "ANDROID",
        appEnvironment: "dev",
      },
    })
    const second = await app.inject({
      method: "POST",
      url: "/devices/fcm-token",
      headers: bearer(auth.accessToken),
      payload: {
        token: "fcm-token-1",
        platform: "ANDROID",
        appEnvironment: "dev",
      },
    })

    expect(first.statusCode).toBe(200)
    expect(second.statusCode).toBe(200)
    expect(first.json().userId).toBe(auth.userId)
    expect(second.json().revokedAt).toBeNull()

    const deleted = await app.inject({
      method: "DELETE",
      url: "/devices/fcm-token",
      headers: bearer(auth.accessToken),
      payload: {
        token: "fcm-token-1",
      },
    })
    expect(deleted.statusCode).toBe(204)

    const tokenRows = await db.query<{ revoked_at: Date | string | null }>(
      `SELECT revoked_at FROM device_tokens WHERE token = $1`,
      ["fcm-token-1"],
    )
    expect(tokenRows.rows[0].revoked_at).toBeTruthy()
  })

  it("dispatches canvas update pushes to only the paired peer for durable milestones", async () => {
    const { inviter, recipient } = await pairUsers()
    await registerFcmToken(inviter.accessToken, "actor-token")
    await registerFcmToken(recipient.accessToken, "peer-token")

    const first = await app.injectWS("/canvas/sync")
    first.send(
      JSON.stringify({
        type: "HELLO",
        accessToken: inviter.accessToken,
        pairSessionId: inviter.pairSessionId,
        lastAppliedServerRevision: 0,
      }),
    )
    await nextWsJson(first)
    first.send(
      JSON.stringify({
        type: "CLIENT_OP_BATCH",
        batchId: "push-batch-1",
        clientCreatedAt: new Date().toISOString(),
        operations: [
          addStrokeOperation("push-op-1", "push-stroke", 1, 1),
          appendPointsOperation("push-op-2", "push-stroke", [{ x: 2, y: 2 }]),
          finishStrokeOperation("push-op-3", "push-stroke"),
        ],
      }),
    )
    await nextWsJson(first)
    await eventually(() => pushSender.sentMessages.length === 1)

    const message = pushSender.sentMessages[0]
    expect(message.tokens).toEqual(["peer-token"])
    expect(message.data).toMatchObject({
      type: "CANVAS_UPDATED",
      pairSessionId: inviter.pairSessionId,
      latestRevision: "3",
      snapshotRevision: "3",
      actorUserId: inviter.userId,
    })
    expect(message.android.priority).toBe("high")
    expect(message.android.collapseKey).toBe(`canvas-${inviter.pairSessionId}`)
    expect(message.android.ttlMs).toBe(24 * 60 * 60 * 1_000)
    first.close()
  })

  it("debounces canvas update pushes and sends the latest revision", async () => {
    const { inviter, recipient } = await pairUsers()
    await registerFcmToken(recipient.accessToken, "debounced-peer-token")
    const first = await app.injectWS("/canvas/sync")
    first.send(
      JSON.stringify({
        type: "HELLO",
        accessToken: inviter.accessToken,
        pairSessionId: inviter.pairSessionId,
        lastAppliedServerRevision: 0,
      }),
    )
    await nextWsJson(first)
    first.send(
      JSON.stringify({
        type: "CLIENT_OP_BATCH",
        batchId: "debounce-batch-1",
        clientCreatedAt: new Date().toISOString(),
        operations: [
          clearCanvasOperation("debounce-op-1"),
          clearCanvasOperation("debounce-op-2"),
          clearCanvasOperation("debounce-op-3"),
        ],
      }),
    )
    await nextWsJson(first)
    await eventually(() => pushSender.sentMessages.length === 1)
    expect(pushSender.sentMessages[0].data).toMatchObject({
      type: "CANVAS_UPDATED",
      latestRevision: "3",
    })
    first.close()
  })

  it("tracks pair streak days from finished strokes", async () => {
    const { inviter } = await pairUsers()
    const today = offsetDate(0)
    const yesterday = offsetDate(-1)

    const response = await app.inject({
      method: "POST",
      url: "/canvas/ops/batch",
      headers: bearer(inviter.accessToken),
      payload: {
        batchId: "streak-batch",
        clientCreatedAt: new Date().toISOString(),
        operations: [
          { ...finishStrokeOperation("streak-yesterday", "stroke-a"), clientLocalDate: yesterday },
          { ...finishStrokeOperation("streak-today-1", "stroke-b"), clientLocalDate: today },
          { ...finishStrokeOperation("streak-today-2", "stroke-c"), clientLocalDate: today },
        ],
      },
    })
    expect(response.statusCode).toBe(200)

    const bootstrap = await app.inject({
      method: "GET",
      url: "/bootstrap",
      headers: bearer(inviter.accessToken),
    })
    expect(bootstrap.statusCode).toBe(200)
    expect(bootstrap.json().currentStreakDays).toBe(2)
  })

  it("resets current streak when the latest activity day is stale", async () => {
    const { inviter } = await pairUsers()
    const staleDay = offsetDate(-3)

    const response = await app.inject({
      method: "POST",
      url: "/canvas/ops/batch",
      headers: bearer(inviter.accessToken),
      payload: {
        batchId: "stale-streak-batch",
        clientCreatedAt: new Date().toISOString(),
        operations: [
          { ...finishStrokeOperation("stale-streak", "stroke-old"), clientLocalDate: staleDay },
        ],
      },
    })
    expect(response.statusCode).toBe(200)

    const bootstrap = await app.inject({
      method: "GET",
      url: "/bootstrap",
      headers: bearer(inviter.accessToken),
    })
    expect(bootstrap.statusCode).toBe(200)
    expect(bootstrap.json().currentStreakDays).toBe(0)
  })

  it("marks permanently invalid push tokens revoked", async () => {
    const { inviter, recipient } = await pairUsers()
    await registerFcmToken(recipient.accessToken, "invalid-peer-token")
    pushSender.invalidTokens.add("invalid-peer-token")
    const first = await app.injectWS("/canvas/sync")
    first.send(
      JSON.stringify({
        type: "HELLO",
        accessToken: inviter.accessToken,
        pairSessionId: inviter.pairSessionId,
        lastAppliedServerRevision: 0,
      }),
    )
    await nextWsJson(first)
    first.send(
      JSON.stringify({
        type: "CLIENT_OP",
        operation: clearCanvasOperation("invalid-token-op"),
      }),
    )
    await nextWsJson(first)
    await eventually(async () => {
      const rows = await db.query<{ revoked_at: Date | string | null }>(
        `SELECT revoked_at FROM device_tokens WHERE token = $1`,
        ["invalid-peer-token"],
      )
      return Boolean(rows.rows[0]?.revoked_at)
    })
    first.close()
  })

  async function signIn(email: string, name: string, photoUrl?: string) {
    const response = await app.inject({
      method: "POST",
      url: "/auth/google",
      payload: {
        idToken: `dev-google:${email}:${name}${photoUrl ? `:${photoUrl}` : ""}`,
      },
    })
    expect(response.statusCode).toBe(200)
    return response.json() as {
      accessToken: string
      refreshToken: string
      userId: string
    }
  }

  async function completeProfile(
    accessToken: string,
    displayName: string,
    partnerDisplayName: string,
    anniversaryDate: string,
  ) {
    const response = await app.inject({
      method: "PUT",
      url: "/me/profile",
      headers: bearer(accessToken),
      payload: {
        displayName,
        partnerDisplayName,
        anniversaryDate,
      },
    })
    expect(response.statusCode).toBe(200)
  }

  async function updatePartnerProfile(
    accessToken: string,
    partnerDisplayName: string,
    anniversaryDate: string,
  ) {
    return app.inject({
      method: "PUT",
      url: "/me/partner-profile",
      headers: bearer(accessToken),
      payload: {
        partnerDisplayName,
        anniversaryDate,
      },
    })
  }

  async function pairUsers() {
    const inviter = await signIn("subhajit@elaris.dev", "Subhajit")
    await completeProfile(inviter.accessToken, "Subhajit", "Ankita", "2026-01-01")
    const createInvite = await app.inject({
      method: "POST",
      url: "/invites",
      headers: bearer(inviter.accessToken),
    })
    const invite = createInvite.json()

    const recipient = await signIn("ankita@elaris.dev", "Ankita")
    await completeProfile(recipient.accessToken, "Ankita", "Subhajit", "2026-01-01")
    await app.inject({
      method: "POST",
      url: "/invites/redeem",
      headers: bearer(recipient.accessToken),
      payload: { code: invite.code },
    })
    const accept = await app.inject({
      method: "POST",
      url: `/invites/${invite.inviteId}/accept`,
      headers: bearer(recipient.accessToken),
    })
    expect(accept.statusCode).toBe(200)
    const pairSessionId = accept.json().pairSessionId as string
    return {
      inviter: { ...inviter, pairSessionId },
      recipient: { ...recipient, pairSessionId },
    }
  }

  async function registerFcmToken(accessToken: string, token: string) {
    const response = await app.inject({
      method: "POST",
      url: "/devices/fcm-token",
      headers: bearer(accessToken),
      payload: {
        token,
        platform: "ANDROID",
        appEnvironment: "dev",
      },
    })
    expect(response.statusCode).toBe(200)
  }
})

function bearer(accessToken: string) {
  return {
    authorization: `Bearer ${accessToken}`,
  }
}

function nextWsJson(socket: { once: (event: string, listener: (raw: unknown) => void) => void }) {
  return new Promise<Record<string, any>>((resolve) => {
    socket.once("message", (raw) => {
      resolve(JSON.parse(String(raw)))
    })
  })
}

function nextNWsJson(
  socket: {
    on: (event: string, listener: (raw: unknown) => void) => void
    removeListener: (event: string, listener: (raw: unknown) => void) => void
  },
  count: number,
) {
  return new Promise<Array<Record<string, any>>>((resolve) => {
    const messages: Array<Record<string, any>> = []
    const onMessage = (raw: unknown) => {
      messages.push(JSON.parse(String(raw)))
      if (messages.length >= count) {
        socket.removeListener("message", onMessage)
        resolve(messages)
      }
    }
    socket.on("message", onMessage)
  })
}

function expectNoWsJson(
  socket: {
    on: (event: string, listener: (raw: unknown) => void) => void
    removeListener: (event: string, listener: (raw: unknown) => void) => void
  },
  timeoutMs = 50,
) {
  return new Promise<void>((resolve, reject) => {
    const onMessage = (raw: unknown) => {
      cleanup()
      reject(new Error(`Unexpected websocket message: ${String(raw)}`))
    }
    const cleanup = () => {
      clearTimeout(timer)
      socket.removeListener("message", onMessage)
    }
    const timer = setTimeout(() => {
      cleanup()
      resolve()
    }, timeoutMs)
    socket.on("message", onMessage)
  })
}

async function eventually(predicate: () => boolean | Promise<boolean>) {
  const startedAt = Date.now()
  while (Date.now() - startedAt < 1_000) {
    if (await predicate()) return
    await new Promise((resolve) => setTimeout(resolve, 10))
  }
  throw new Error("Condition was not met")
}

function addStrokeOperation(
  clientOperationId: string,
  strokeId: string,
  x: number,
  y: number,
) {
  return {
    clientOperationId,
    type: "ADD_STROKE",
    strokeId,
    payload: {
      id: strokeId,
      colorArgb: 4278190080,
      width: 8,
      createdAt: 123,
      firstPoint: { x, y },
    },
    clientCreatedAt: new Date().toISOString(),
  }
}

function appendPointsOperation(
  clientOperationId: string,
  strokeId: string,
  points: Array<{ x: number; y: number }>,
) {
  return {
    clientOperationId,
    type: "APPEND_POINTS",
    strokeId,
    payload: { points },
    clientCreatedAt: new Date().toISOString(),
  }
}

function finishStrokeOperation(clientOperationId: string, strokeId: string) {
  return {
    clientOperationId,
    type: "FINISH_STROKE",
    strokeId,
    payload: {},
    clientCreatedAt: new Date().toISOString(),
  }
}

function deleteStrokeOperation(clientOperationId: string, strokeId: string) {
  return {
    clientOperationId,
    type: "DELETE_STROKE",
    strokeId,
    payload: {},
    clientCreatedAt: new Date().toISOString(),
  }
}

function clearCanvasOperation(clientOperationId: string) {
  return {
    clientOperationId,
    type: "CLEAR_CANVAS",
    strokeId: null,
    payload: {},
    clientCreatedAt: new Date().toISOString(),
  }
}

function addTextElementOperation(
  clientOperationId: string,
  elementId: string,
  text: string,
  x: number,
  y: number,
) {
  return {
    clientOperationId,
    type: "ADD_TEXT_ELEMENT",
    strokeId: elementId,
    payload: {
      id: elementId,
      text,
      createdAt: 123,
      center: { x, y },
      rotationRad: 0,
      scale: 1,
      boxWidth: 0.7,
      colorArgb: 4278190080,
      backgroundPillEnabled: false,
      font: "POPPINS",
      alignment: "CENTER",
    },
    clientCreatedAt: new Date().toISOString(),
  }
}

function updateTextElementOperation(
  clientOperationId: string,
  elementId: string,
  text: string,
  x: number,
  y: number,
) {
  return {
    clientOperationId,
    type: "UPDATE_TEXT_ELEMENT",
    strokeId: elementId,
    payload: {
      id: elementId,
      text,
      createdAt: 123,
      center: { x, y },
      rotationRad: 0,
      scale: 1,
      boxWidth: 0.7,
      colorArgb: 4278190080,
      backgroundPillEnabled: false,
      font: "POPPINS",
      alignment: "CENTER",
    },
    clientCreatedAt: new Date().toISOString(),
  }
}

function deleteTextElementOperation(clientOperationId: string, elementId: string) {
  return {
    clientOperationId,
    type: "DELETE_TEXT_ELEMENT",
    strokeId: elementId,
    payload: {},
    clientCreatedAt: new Date().toISOString(),
  }
}

function createMultipartPayload(input: {
  title: string
  description: string
  sortOrder: string
  published: string
  fileName: string
  contentType: string
  fileBody: Buffer
}) {
  const boundary = `----mulberry-${Date.now()}`
  const chunks: Buffer[] = []
  const appendField = (name: string, value: string) => {
    chunks.push(Buffer.from(`--${boundary}\r\n`))
    chunks.push(Buffer.from(`Content-Disposition: form-data; name="${name}"\r\n\r\n`))
    chunks.push(Buffer.from(`${value}\r\n`))
  }

  appendField("title", input.title)
  appendField("description", input.description)
  appendField("sortOrder", input.sortOrder)
  appendField("published", input.published)
  chunks.push(Buffer.from(`--${boundary}\r\n`))
  chunks.push(
    Buffer.from(
      `Content-Disposition: form-data; name="image"; filename="${input.fileName}"\r\n` +
        `Content-Type: ${input.contentType}\r\n\r\n`,
    ),
  )
  chunks.push(input.fileBody)
  chunks.push(Buffer.from(`\r\n--${boundary}--\r\n`))

  return {
    body: Buffer.concat(chunks),
    contentType: `multipart/form-data; boundary=${boundary}`,
  }
}

function createImageUploadPayload() {
  const boundary = `----mulberry-photo-${Date.now()}-${Math.random()}`
  const png = Buffer.from(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=",
    "base64",
  )
  const body = Buffer.concat([
    Buffer.from(`--${boundary}\r\n`),
    Buffer.from(
      `Content-Disposition: form-data; name="image"; filename="avatar.png"\r\n` +
        `Content-Type: image/png\r\n\r\n`,
    ),
    png,
    Buffer.from(`\r\n--${boundary}--\r\n`),
  ])
  return {
    body,
    contentType: `multipart/form-data; boundary=${boundary}`,
  }
}

function offsetDate(deltaDays: number): string {
  const date = new Date()
  date.setUTCDate(date.getUTCDate() + deltaDays)
  return date.toISOString().slice(0, 10)
}

class RecordingPushSender implements PushSender {
  readonly sentMessages: MulberryPushMessage[] = []
  readonly invalidTokens = new Set<string>()

  async send(message: MulberryPushMessage) {
    this.sentMessages.push(message)
    return {
      invalidTokens: message.tokens.filter((token) => this.invalidTokens.has(token)),
    }
  }
}

class RecordingWallpaperStorage implements WallpaperStorage {
  readonly uploadedPaths: string[] = []
  readonly removedPaths: string[] = []

  async upload(path: string): Promise<void> {
    this.uploadedPaths.push(path)
  }

  async remove(paths: string[]): Promise<void> {
    this.removedPaths.push(...paths)
  }

  publicUrl(path: string): string {
    return `https://storage.example.test/${path}`
  }
}

class FakeWallpaperImageProcessor implements WallpaperImageProcessor {
  async process(): Promise<ProcessedWallpaperImage> {
    return {
      width: 1440,
      height: 2560,
      dominantColor: "#B31329",
      thumbnail: Buffer.from("thumbnail"),
      preview: Buffer.from("preview"),
      full: Buffer.from("full"),
      contentType: "image/webp",
      extension: "webp",
    }
  }
}
