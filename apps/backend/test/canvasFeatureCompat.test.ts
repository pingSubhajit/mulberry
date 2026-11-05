import { newDb } from "pg-mem"
import type { FastifyInstance } from "fastify"
import { afterEach, beforeEach, describe, expect, it } from "vitest"
import { createApp } from "../src/app/createApp.js"
import { runMigrations, type Database } from "../src/infra/db/database.js"
import type { MulberryPushMessage, PushSender } from "../src/infra/push/types.js"
import type { WallpaperStorage } from "../src/modules/wallpapers/service.js"
import type {
  CanvasOperationEnvelope,
  CanvasOperationType,
  CanvasOpsResponse,
  CanvasPhotoElementPayload,
  CanvasSnapshotResponse,
  ClientCanvasOperation,
} from "../src/contracts/canvas.js"
import {
  clientSupportsPhotos,
  operationForClient,
  snapshotForClient,
} from "../src/modules/canvas/featureCompat.js"

interface SignedIn {
  accessToken: string
  userId: string
  pairSessionId: string
}

interface SnapshotElement {
  kind: string
  id: string
}

interface SnapshotBody extends Omit<CanvasSnapshotResponse, "snapshot"> {
  snapshot: { elements: SnapshotElement[] }
}

interface SyncMessage {
  type: string
  operation?: CanvasOperationEnvelope
  operations?: CanvasOperationEnvelope[]
  missedOperations?: CanvasOperationEnvelope[]
}

type SyncSocket = Awaited<ReturnType<FastifyInstance["injectWS"]>>

type Headers = Record<string, string>

const PHOTOS: Headers = { "x-canvas-features": "photos" }
const LEGACY: Headers = {}

describe("clientSupportsPhotos", () => {
  it.each([
    ["photos", true],
    ["PHOTOS", true],
    ["  Photos  ", true],
    ["foo, Photos", true],
    ["foo,photos,bar", true],
    ["foo, bar", false],
    ["photosynthesis", false],
    ["", false],
  ])("reads %j as %s", (header, expected) => {
    expect(clientSupportsPhotos({ "x-canvas-features": header })).toBe(expected)
  })

  it("treats a missing header as legacy", () => {
    expect(clientSupportsPhotos({})).toBe(false)
  })

  it("reads a header sent more than once", () => {
    expect(clientSupportsPhotos({ "x-canvas-features": ["foo", "bar, photos"] })).toBe(true)
  })
})

describe("operationForClient", () => {
  const photoAdd: CanvasOperationEnvelope = {
    clientOperationId: "op-1",
    actorUserId: "user-1",
    pairSessionId: "pair-1",
    type: "ADD_PHOTO_ELEMENT",
    strokeId: "photo-1",
    payload: photoPayload("photo-1"),
    clientCreatedAt: "2026-11-05T10:00:00.000Z",
    serverRevision: 7,
    createdAt: "2026-11-05T10:00:01.000Z",
  }

  it("rewrites every photo operation as a sticker delete for a legacy client", () => {
    const types: CanvasOperationType[] = ["ADD_PHOTO_ELEMENT", "UPDATE_PHOTO_ELEMENT", "DELETE_PHOTO_ELEMENT"]
    for (const type of types) {
      expect(operationForClient({ ...photoAdd, type }, false)).toEqual({
        ...photoAdd,
        type: "DELETE_STICKER_ELEMENT",
        payload: {},
      })
    }
  })

  it("leaves photo operations alone for a photo-aware client, and other operations for anyone", () => {
    expect(operationForClient(photoAdd, true)).toBe(photoAdd)
    const sticker: CanvasOperationEnvelope = { ...photoAdd, type: "ADD_STICKER_ELEMENT" }
    expect(operationForClient(sticker, false)).toBe(sticker)
  })

  it("leaves a snapshot without elements alone", () => {
    const snapshot: CanvasSnapshotResponse = {
      pairSessionId: "pair-1",
      snapshotRevision: 0,
      latestRevision: 0,
      revision: 0,
      snapshot: { strokes: [] },
      updatedAt: null,
    }
    expect(snapshotForClient(snapshot, false)).toBe(snapshot)
  })
})

describe("Canvas photo compatibility for legacy clients", () => {
  let app: FastifyInstance
  let db: Database

  beforeEach(async () => {
    const memoryDb = newDb({ autoCreateForeignKeyIndices: true })
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
    app = await createApp({
      db,
      remoteConfig: null,
      pushSender: new SilentPushSender(),
      pushOptions: { debounceMs: 20, widgetReloadDelayMs: 0 },
      wallpaperStorage: new SilentWallpaperStorage(),
      analyticsBackfill: false,
      config: {
        port: 8080,
        databaseUrl: "postgres://unused",
        googleClientId: "",
        googleAllowedClientIds: [],
        allowDevGoogleTokens: true,
        enableDebugRoutes: false,
        adminPassword: "admin-password",
        cannySsoPrivateKey: "test-canny-secret",
        wallpaperShortcutLinks: {},
      },
    })
    await app.ready()
  })

  afterEach(async () => {
    await app.close()
  })

  describe("HTTP", () => {
    it("sends photo operations to a legacy client as sticker deletes with the same revisions", async () => {
      const { inviter, recipient } = await pairUsers()
      const sent = await sendOps(inviter, PHOTOS, "mixed", mixedOperations())
      expect(sent.map((operation) => operation.type)).toEqual([
        "ADD_STICKER_ELEMENT",
        "ADD_PHOTO_ELEMENT",
        "UPDATE_PHOTO_ELEMENT",
        "DELETE_PHOTO_ELEMENT",
      ])

      const legacy = await listOps(recipient, LEGACY)
      expect(legacy.map((operation) => [operation.serverRevision, operation.type, operation.strokeId])).toEqual([
        [1, "ADD_STICKER_ELEMENT", "sticker-1"],
        [2, "DELETE_STICKER_ELEMENT", "photo-1"],
        [3, "DELETE_STICKER_ELEMENT", "photo-1"],
        [4, "DELETE_STICKER_ELEMENT", "photo-1"],
      ])
      expect(legacy[0]).toEqual(sent[0])
      legacy.slice(1).forEach((operation, index) => {
        expect(operation).toEqual({ ...sent[index + 1], type: "DELETE_STICKER_ELEMENT", payload: {} })
      })
      expect(await listOps(recipient, { "x-canvas-features": "other" })).toEqual(legacy)

      expect(await listOps(recipient, PHOTOS)).toEqual(sent)
      expect(await listOps(recipient, { "x-canvas-features": "foo, Photos" })).toEqual(sent)
    })

    it("translates the batch response for a legacy sender", async () => {
      const { inviter } = await pairUsers()
      const accepted = await sendOps(inviter, LEGACY, "legacy-sender", mixedOperations())
      expect(accepted.map((operation) => operation.type)).toEqual([
        "ADD_STICKER_ELEMENT",
        "DELETE_STICKER_ELEMENT",
        "DELETE_STICKER_ELEMENT",
        "DELETE_STICKER_ELEMENT",
      ])
      expect(accepted.map((operation) => operation.serverRevision)).toEqual([1, 2, 3, 4])
    })

    it("drops PHOTO elements from a legacy client's snapshot and keeps them for a photo-aware one", async () => {
      const { inviter, recipient } = await pairUsers()
      await sendOps(inviter, PHOTOS, "snapshot", [
        stickerOperation("op-sticker", "sticker-1"),
        photoOperation("ADD_PHOTO_ELEMENT", "op-photo", "photo-1"),
      ])

      const legacy = await snapshot(recipient, LEGACY)
      expect(legacy.snapshot.elements.map((element) => [element.kind, element.id])).toEqual([["STICKER", "sticker-1"]])
      expect([legacy.revision, legacy.latestRevision]).toEqual([2, 2])

      const aware = await snapshot(recipient, { "x-canvas-features": " PHOTOS " })
      expect(aware.snapshot.elements.map((element) => [element.kind, element.id])).toEqual([
        ["STICKER", "sticker-1"],
        ["PHOTO", "photo-1"],
      ])
    })

    it("keeps photo operations and elements as they are in storage", async () => {
      const { inviter, recipient } = await pairUsers()
      await sendOps(inviter, LEGACY, "storage", mixedOperations())
      await listOps(recipient, LEGACY)
      await snapshot(recipient, LEGACY)

      const stored = await db.query<{ type: string }>(
        `SELECT type FROM canvas_operations ORDER BY server_revision ASC`,
      )
      expect(stored.rows.map((row) => row.type)).toEqual([
        "ADD_STICKER_ELEMENT",
        "ADD_PHOTO_ELEMENT",
        "UPDATE_PHOTO_ELEMENT",
        "DELETE_PHOTO_ELEMENT",
      ])

      await sendOps(inviter, LEGACY, "storage-2", [photoOperation("ADD_PHOTO_ELEMENT", "op-again", "photo-2")])
      await snapshot(recipient, LEGACY)
      const storedSnapshot = await db.query<{ snapshot_json: { elements: SnapshotElement[] } }>(
        `SELECT snapshot_json FROM canvas_snapshots`,
      )
      expect(storedSnapshot.rows[0].snapshot_json.elements.map((element) => element.kind)).toEqual(["STICKER", "PHOTO"])
    })
  })

  describe("WebSocket sync", () => {
    it("sends each recipient socket the form it supports", async () => {
      const { inviter, recipient } = await pairUsers()
      const sender = await connect(inviter, PHOTOS)
      const senderLegacyDevice = await connect(inviter, LEGACY)
      const partnerLegacy = await connect(recipient, LEGACY)
      const partnerAware = await connect(recipient, { "x-canvas-features": "Foo, Photos" })

      const senderMessages = nextMessages(sender, 2)
      const broadcasts = Promise.all([senderLegacyDevice, partnerLegacy, partnerAware].map((socket) => nextMessages(socket, 1)))
      sender.send(JSON.stringify({
        type: "CLIENT_OP_BATCH",
        batchId: "socket-mixed",
        clientCreatedAt: new Date().toISOString(),
        operations: mixedOperations(),
      }))

      const [[ack, flowControl], [[ownLegacy], [legacy], [aware]]] = await Promise.all([senderMessages, broadcasts])
      expect([ack.type, flowControl.type]).toEqual(["ACK_BATCH", "FLOW_CONTROL"])
      const real = ack.operations ?? []
      expect(real.map((operation) => operation.type)).toEqual([
        "ADD_STICKER_ELEMENT",
        "ADD_PHOTO_ELEMENT",
        "UPDATE_PHOTO_ELEMENT",
        "DELETE_PHOTO_ELEMENT",
      ])
      const translated = real.map((operation, index) =>
        index === 0 ? operation : { ...operation, type: "DELETE_STICKER_ELEMENT", payload: {} },
      )

      expect(aware).toEqual({ type: "SERVER_OP_BATCH", operations: real })
      expect(legacy).toEqual({ type: "SERVER_OP_BATCH", operations: translated })
      expect(ownLegacy).toEqual({ type: "SERVER_OP_BATCH", operations: translated })
      expect(translated.map((operation) => operation.serverRevision)).toEqual([1, 2, 3, 4])

      for (const socket of [sender, senderLegacyDevice, partnerLegacy, partnerAware]) socket.close()
    })

    it("translates a single operation's broadcast and acknowledgement", async () => {
      const { inviter, recipient } = await pairUsers()
      const legacySender = await connect(inviter, LEGACY)
      const awarePartner = await connect(recipient, PHOTOS)

      const ack = nextMessages(legacySender, 1)
      const broadcast = nextMessages(awarePartner, 1)
      legacySender.send(JSON.stringify({
        type: "CLIENT_OP",
        operation: photoOperation("ADD_PHOTO_ELEMENT", "op-single", "photo-1"),
      }))

      const [[ackMessage], [serverOp]] = await Promise.all([ack, broadcast])
      expect(ackMessage.type).toBe("ACK")
      expect(ackMessage.operation).toMatchObject({ type: "DELETE_STICKER_ELEMENT", strokeId: "photo-1", serverRevision: 1, payload: {} })
      expect(serverOp.type).toBe("SERVER_OP")
      expect(serverOp.operation).toMatchObject({ type: "ADD_PHOTO_ELEMENT", strokeId: "photo-1", serverRevision: 1 })
      expect(serverOp.operation?.payload).toEqual(photoPayload("photo-1"))

      legacySender.close()
      awarePartner.close()
    })

    it("translates the operations a socket catches up on", async () => {
      const { inviter, recipient } = await pairUsers()
      await sendOps(inviter, PHOTOS, "catch-up", mixedOperations())

      const legacy = await connectWithReady(recipient, LEGACY)
      expect(legacy.ready.missedOperations?.map((operation) => [operation.serverRevision, operation.type])).toEqual([
        [1, "ADD_STICKER_ELEMENT"],
        [2, "DELETE_STICKER_ELEMENT"],
        [3, "DELETE_STICKER_ELEMENT"],
        [4, "DELETE_STICKER_ELEMENT"],
      ])

      const aware = await connectWithReady(recipient, PHOTOS)
      expect(aware.ready.missedOperations).toEqual(await listOps(recipient, PHOTOS))

      legacy.socket.close()
      aware.socket.close()
    })
  })

  async function connect(user: SignedIn, headers: Headers): Promise<SyncSocket> {
    return (await connectWithReady(user, headers)).socket
  }

  async function connectWithReady(user: SignedIn, headers: Headers): Promise<{ socket: SyncSocket; ready: SyncMessage }> {
    const socket = await app.injectWS("/canvas/sync", { headers })
    const ready = nextMessages(socket, 1)
    socket.send(JSON.stringify({
      type: "HELLO",
      accessToken: user.accessToken,
      pairSessionId: user.pairSessionId,
      lastAppliedServerRevision: 0,
    }))
    const [message] = await ready
    expect(message.type).toBe("READY")
    return { socket, ready: message }
  }

  async function signIn(email: string, name: string): Promise<Omit<SignedIn, "pairSessionId">> {
    const response = await app.inject({
      method: "POST",
      url: "/auth/google",
      payload: { idToken: `dev-google:${email}:${name}` },
    })
    expect(response.statusCode).toBe(200)
    return response.json<Omit<SignedIn, "pairSessionId">>()
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

  async function pairUsers(): Promise<{ inviter: SignedIn; recipient: SignedIn }> {
    const inviter = await signIn("subhajit@elaris.dev", "Subhajit")
    await completeProfile(inviter.accessToken, "Subhajit", "Ankita")
    const invite = (await app.inject({ method: "POST", url: "/invites", headers: bearer(inviter.accessToken) }))
      .json<{ code: string; inviteId: string }>()

    const recipient = await signIn("ankita@elaris.dev", "Ankita")
    await completeProfile(recipient.accessToken, "Ankita", "Subhajit")
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
    const pairSessionId = accept.json<{ pairSessionId: string }>().pairSessionId
    return {
      inviter: { ...inviter, pairSessionId },
      recipient: { ...recipient, pairSessionId },
    }
  }

  async function sendOps(
    user: SignedIn,
    headers: Headers,
    batchId: string,
    operations: ClientCanvasOperation[],
  ): Promise<CanvasOperationEnvelope[]> {
    const response = await app.inject({
      method: "POST",
      url: "/canvas/ops/batch",
      headers: { ...bearer(user.accessToken), ...headers },
      payload: { batchId, clientCreatedAt: new Date().toISOString(), operations },
    })
    expect(response.statusCode).toBe(200)
    return response.json<CanvasOpsResponse>().operations
  }

  async function listOps(user: SignedIn, headers: Headers): Promise<CanvasOperationEnvelope[]> {
    const response = await app.inject({
      method: "GET",
      url: "/canvas/ops?afterRevision=0",
      headers: { ...bearer(user.accessToken), ...headers },
    })
    expect(response.statusCode).toBe(200)
    return response.json<CanvasOpsResponse>().operations
  }

  async function snapshot(user: SignedIn, headers: Headers): Promise<SnapshotBody> {
    const response = await app.inject({
      method: "GET",
      url: "/canvas/snapshot",
      headers: { ...bearer(user.accessToken), ...headers },
    })
    expect(response.statusCode).toBe(200)
    return response.json<SnapshotBody>()
  }
})

function nextMessages(socket: SyncSocket, count: number): Promise<SyncMessage[]> {
  return new Promise((resolve) => {
    const messages: SyncMessage[] = []
    const onMessage = (raw: unknown) => {
      messages.push(JSON.parse(String(raw)) as SyncMessage)
      if (messages.length >= count) {
        socket.removeListener("message", onMessage)
        resolve(messages)
      }
    }
    socket.on("message", onMessage)
  })
}

function bearer(accessToken: string): Headers {
  return { authorization: `Bearer ${accessToken}` }
}

/** A sticker, then a photo added, moved and deleted: revisions 1 to 4. */
function mixedOperations(): ClientCanvasOperation[] {
  return [
    stickerOperation("op-sticker", "sticker-1"),
    photoOperation("ADD_PHOTO_ELEMENT", "op-add", "photo-1"),
    photoOperation("UPDATE_PHOTO_ELEMENT", "op-update", "photo-1", { center: { x: 0.2, y: 0.8 } }),
    {
      clientOperationId: "op-delete",
      type: "DELETE_PHOTO_ELEMENT",
      strokeId: "photo-1",
      payload: {},
      clientCreatedAt: new Date().toISOString(),
    },
  ]
}

function photoPayload(id: string, overrides: Partial<CanvasPhotoElementPayload> = {}): CanvasPhotoElementPayload {
  return {
    id,
    createdAt: 123,
    center: { x: 0.5, y: 0.5 },
    rotationRad: 0,
    scale: 0.5,
    photoId: `upload-${id}`,
    aspectRatio: 1.5,
    style: "FRAME",
    ...overrides,
  }
}

function photoOperation(
  type: "ADD_PHOTO_ELEMENT" | "UPDATE_PHOTO_ELEMENT",
  clientOperationId: string,
  id: string,
  overrides: Partial<CanvasPhotoElementPayload> = {},
): ClientCanvasOperation {
  return {
    clientOperationId,
    type,
    strokeId: id,
    payload: photoPayload(id, overrides),
    clientCreatedAt: new Date().toISOString(),
  }
}

function stickerOperation(clientOperationId: string, id: string): ClientCanvasOperation {
  return {
    clientOperationId,
    type: "ADD_STICKER_ELEMENT",
    strokeId: id,
    payload: {
      id,
      createdAt: 123,
      center: { x: 0.4, y: 0.4 },
      rotationRad: 0,
      scale: 0.22,
      packKey: "hearts",
      packVersion: 1,
      stickerId: "heart-1",
    },
    clientCreatedAt: new Date().toISOString(),
  }
}

class SilentPushSender implements PushSender {
  async send(message: MulberryPushMessage) {
    return { invalidTokens: message.tokens.filter(() => false) }
  }

  async sendLiveActivity() {
    return true
  }
}

class SilentWallpaperStorage implements WallpaperStorage {
  async upload(): Promise<void> {}

  async remove(): Promise<void> {}

  publicUrl(path: string): string {
    return `https://storage.example.test/${path}`
  }
}
