import { DataType, newDb } from "pg-mem"
import sharp from "sharp"
import type { FastifyInstance } from "fastify"
import { afterEach, beforeEach, describe, expect, it } from "vitest"
import { createApp } from "../src/app/createApp.js"
import { runMigrations, type Database } from "../src/infra/db/database.js"
import type { MulberryPushMessage, PushSender } from "../src/infra/push/types.js"
import type { WallpaperStorage } from "../src/modules/wallpapers/service.js"
import type {
  CanvasPhotoElement,
  CanvasPhotoElementPayload,
  CanvasPhotoUploadResponse,
  CanvasSnapshotResponse,
  ClientCanvasOperation,
} from "../src/contracts/canvas.js"

interface SignedIn {
  accessToken: string
  userId: string
}

interface CanvasSnapshotElement {
  kind: string
  id: string
}

interface CanvasSnapshotBody extends Omit<CanvasSnapshotResponse, "snapshot"> {
  snapshot: { elements: CanvasSnapshotElement[] }
}

describe("Canvas photos", () => {
  let app: FastifyInstance
  let db: Database

  beforeEach(async () => {
    const memoryDb = newDb({ autoCreateForeignKeyIndices: true })
    // Postgres has decode(); pg-mem needs it spelled out.
    memoryDb.public.registerFunction({
      name: "decode",
      args: [DataType.text, DataType.text],
      returns: DataType.bytea,
      implementation: (value: string, format: BufferEncoding) => Buffer.from(value, format),
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

  describe("photo elements", () => {
    it("materialises added, updated and deleted photos into the snapshot", async () => {
      const { inviter } = await pairUsers()

      await sendOps(inviter, "photo-batch-1", [
        photoOperation("ADD_PHOTO_ELEMENT", "op-1", photoPayload("photo-1", { scale: 0.4, style: "POLAROID" })),
        photoOperation("ADD_PHOTO_ELEMENT", "op-2", photoPayload("photo-2", { style: "STICKER", cutoutPhotoId: "cutout-2" })),
      ])
      expect(await photoElements(inviter)).toEqual([
        { kind: "PHOTO", ...photoPayload("photo-1", { scale: 0.4, style: "POLAROID" }) },
        { kind: "PHOTO", ...photoPayload("photo-2", { style: "STICKER", cutoutPhotoId: "cutout-2" }) },
      ])

      // A later batch reads the stored snapshot back, so this also proves PHOTO survives it.
      await sendOps(inviter, "photo-batch-2", [
        photoOperation("UPDATE_PHOTO_ELEMENT", "op-3", photoPayload("photo-1", { center: { x: 0.2, y: 0.8 }, rotationRad: 1.5 })),
      ])
      expect(await photoElements(inviter)).toEqual([
        { kind: "PHOTO", ...photoPayload("photo-2", { style: "STICKER", cutoutPhotoId: "cutout-2" }) },
        { kind: "PHOTO", ...photoPayload("photo-1", { center: { x: 0.2, y: 0.8 }, rotationRad: 1.5 }) },
      ])

      await sendOps(inviter, "photo-batch-3", [deletePhotoOperation("op-4", "photo-2")])
      expect((await photoElements(inviter)).map((element) => element.id)).toEqual(["photo-1"])

      await sendOps(inviter, "photo-batch-4", [
        { clientOperationId: "op-5", type: "CLEAR_CANVAS", strokeId: null, payload: {}, clientCreatedAt: new Date().toISOString() },
      ])
      expect(await photoElements(inviter)).toEqual([])
    })

    it("clamps the scale, reads an unknown style as FRAME and drops an invalid cutout", async () => {
      const { inviter } = await pairUsers()

      await sendOps(inviter, "photo-normalise", [
        photoOperation("ADD_PHOTO_ELEMENT", "op-big", photoPayloadWith("big", { scale: 5, style: "NEON", cutoutPhotoId: "" })),
        photoOperation("ADD_PHOTO_ELEMENT", "op-small", photoPayloadWith("small", { scale: 0.01, cutoutPhotoId: 42 })),
      ])
      const elements = await photoElements(inviter)
      expect(elements.map((element) => [element.id, element.scale, element.style])).toEqual([
        ["big", 1.2, "FRAME"],
        ["small", 0.12, "FRAME"],
      ])
      expect(elements.every((element) => !("cutoutPhotoId" in element))).toBe(true)
    })

    it("ignores malformed photo payloads", async () => {
      const { inviter } = await pairUsers()

      await sendOps(inviter, "photo-malformed", [
        photoOperation("ADD_PHOTO_ELEMENT", "op-no-photo", photoPayloadWith("no-photo", { photoId: "  " })),
        photoOperation("ADD_PHOTO_ELEMENT", "op-flat", photoPayloadWith("flat", { aspectRatio: 0 })),
        photoOperation("ADD_PHOTO_ELEMENT", "op-nan", photoPayloadWith("nan", { aspectRatio: "wide" })),
        photoOperation("ADD_PHOTO_ELEMENT", "op-no-center", photoPayloadWith("no-center", { center: null })),
        photoOperation("ADD_PHOTO_ELEMENT", "op-no-id", photoPayloadWith("no-id", { id: 7 })),
      ])
      expect(await photoElements(inviter)).toEqual([])
    })

    it("counts adding a photo as drawing", async () => {
      const { inviter } = await pairUsers()

      await sendOps(inviter, "photo-draw", [
        photoOperation("ADD_PHOTO_ELEMENT", "op-draw", photoPayload("photo-1")),
      ])
      const drew = await db.query<{ first_drew_at: Date | null }>(
        `SELECT first_drew_at FROM users WHERE id = $1`,
        [inviter.userId],
      )
      expect(drew.rows[0].first_drew_at).not.toBeNull()
      const days = await db.query(`SELECT activity_day FROM pair_activity_days WHERE pair_session_id = $1`, [inviter.pairSessionId])
      expect(days.rows).toHaveLength(1)
    })
  })

  describe("POST /canvas/photos", () => {
    it("requires a session", async () => {
      const upload = await uploadPhoto(null, await jpeg(40, 30))
      expect(upload.statusCode).toBe(401)
    })

    it("requires being paired", async () => {
      const loner = await signIn("loner@elaris.dev", "Loner")
      const upload = await uploadPhoto(loner.accessToken, await jpeg(40, 30))
      expect(upload.statusCode).toBe(403)
      expect(upload.json().code).toBe("NOT_PAIRED")
      const stored = await db.query(`SELECT id FROM canvas_photos`)
      expect(stored.rows).toEqual([])
    })

    it("rejects what isn't a JPEG, PNG, WebP or HEIC", async () => {
      const { inviter } = await pairUsers()

      const gif = await uploadPhoto(inviter.accessToken, await solid(20, 20).gif().toBuffer(), "image/gif")
      expect(gif.statusCode).toBe(400)
      expect(gif.json().code).toBe("CANVAS_PHOTO_UNSUPPORTED")

      const text = await uploadPhoto(inviter.accessToken, Buffer.from("not a photo"), "image/jpeg")
      expect(text.statusCode).toBe(400)
      expect(text.json().code).toBe("CANVAS_PHOTO_UNSUPPORTED")

      const missing = await app.inject({
        method: "POST",
        url: "/canvas/photos",
        headers: bearer(inviter.accessToken),
        payload: { image: "nope" },
      })
      expect(missing.statusCode).toBe(400)
    })

    it("rejects photos over 12 MB", async () => {
      const { inviter } = await pairUsers()
      const upload = await uploadPhoto(inviter.accessToken, Buffer.alloc(12 * 1024 * 1024 + 1, 1))
      expect(upload.statusCode).toBe(413)
      expect(upload.json().code).toBe("CANVAS_PHOTO_TOO_LARGE")
    })

    it("turns a photo upright, fits it inside 2048 and stores it as JPEG", async () => {
      const { inviter } = await pairUsers()
      // Landscape pixels with EXIF orientation 6: the photo was taken in portrait.
      const original = await solid(3000, 1500).jpeg().withMetadata({ orientation: 6 }).toBuffer()

      const upload = await uploadPhoto(inviter.accessToken, original)
      expect(upload.statusCode).toBe(200)
      const body = upload.json<CanvasPhotoUploadResponse>()
      expect(body).toEqual({ photoId: expect.any(String), width: 1024, height: 2048, contentType: "image/jpeg" })

      const download = await downloadPhoto(inviter.accessToken, body.photoId)
      expect(download.statusCode).toBe(200)
      expect(download.headers["content-type"]).toBe("image/jpeg")
      expect(download.headers["cache-control"]).toBe("private, max-age=31536000, immutable")
      const stored = await sharp(download.rawPayload).metadata()
      expect([stored.format, stored.width, stored.height, stored.orientation]).toEqual(["jpeg", 1024, 2048, undefined])
    })

    it("keeps a photo with transparency as PNG, without enlarging it", async () => {
      const { inviter } = await pairUsers()
      const cutout = await sharp({
        create: { width: 400, height: 300, channels: 4, background: { r: 200, g: 40, b: 80, alpha: 0 } },
      })
        .composite([{ input: await solid(200, 150).png().toBuffer(), top: 75, left: 100 }])
        .png()
        .toBuffer()

      const upload = await uploadPhoto(inviter.accessToken, cutout, "image/png")
      expect(upload.statusCode).toBe(200)
      const body = upload.json<CanvasPhotoUploadResponse>()
      expect(body).toEqual({ photoId: expect.any(String), width: 400, height: 300, contentType: "image/png" })

      const download = await downloadPhoto(inviter.accessToken, body.photoId)
      expect(download.statusCode).toBe(200)
      expect(download.headers["content-type"]).toBe("image/png")
      const stored = sharp(download.rawPayload)
      const metadata = await stored.metadata()
      expect([metadata.format, metadata.width, metadata.height, metadata.hasAlpha]).toEqual(["png", 400, 300, true])
      expect((await stored.stats()).isOpaque).toBe(false)
    })

    it("stores an opaque PNG and a WebP as JPEG", async () => {
      const { inviter } = await pairUsers()
      const opaquePng = await solid(64, 48).ensureAlpha(1).png().toBuffer()
      const webp = await solid(64, 48).webp().toBuffer()

      for (const [image, contentType] of [[opaquePng, "image/png"], [webp, "image/webp"]] as const) {
        const upload = await uploadPhoto(inviter.accessToken, image, contentType)
        expect(upload.statusCode).toBe(200)
        expect(upload.json<CanvasPhotoUploadResponse>()).toMatchObject({ width: 64, height: 48, contentType: "image/jpeg" })
      }
    })
  })

  describe("GET /canvas/photos/:photoId", () => {
    it("serves a photo to both people in the pair, and to nobody else", async () => {
      const { inviter, recipient } = await pairUsers()
      const photoId = await uploadedPhotoId(inviter)

      expect((await downloadPhoto(inviter.accessToken, photoId)).statusCode).toBe(200)
      expect((await downloadPhoto(recipient.accessToken, photoId)).statusCode).toBe(200)
      expect((await downloadPhoto(null, photoId)).statusCode).toBe(401)
      expect((await downloadPhoto(inviter.accessToken, "00000000-0000-4000-8000-000000000000")).statusCode).toBe(404)

      const other = await pairUsers({ inviter: "other-a@elaris.dev", recipient: "other-b@elaris.dev" })
      expect((await downloadPhoto(other.inviter.accessToken, photoId)).statusCode).toBe(404)
      expect((await downloadPhoto(other.recipient.accessToken, photoId)).statusCode).toBe(404)

      const loner = await signIn("loner@elaris.dev", "Loner")
      expect((await downloadPhoto(loner.accessToken, photoId)).statusCode).toBe(404)
    })

    it("stops serving a pair's photos and deletes them once the pair ends", async () => {
      const { inviter, recipient } = await pairUsers()
      const photoId = await uploadedPhotoId(inviter)

      const disconnect = await app.inject({ method: "POST", url: "/pairing/disconnect", headers: bearer(recipient.accessToken) })
      expect(disconnect.statusCode).toBe(200)

      expect((await downloadPhoto(inviter.accessToken, photoId)).statusCode).toBe(404)
      expect((await db.query(`SELECT id FROM canvas_photos`)).rows).toEqual([])
    })

    it("deletes a pair's photos with either account", async () => {
      const { inviter, recipient } = await pairUsers()
      await uploadedPhotoId(inviter)
      await uploadedPhotoId(recipient)

      const deletion = await app.inject({ method: "DELETE", url: "/me", headers: bearer(recipient.accessToken) })
      expect(deletion.statusCode).toBe(204)
      expect((await db.query(`SELECT id FROM canvas_photos`)).rows).toEqual([])
    })
  })

  async function signIn(email: string, name: string): Promise<SignedIn> {
    const response = await app.inject({
      method: "POST",
      url: "/auth/google",
      payload: { idToken: `dev-google:${email}:${name}` },
    })
    expect(response.statusCode).toBe(200)
    return response.json<SignedIn>()
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

  async function pairUsers(emails = { inviter: "subhajit@elaris.dev", recipient: "ankita@elaris.dev" }) {
    const inviter = await signIn(emails.inviter, "Subhajit")
    await completeProfile(inviter.accessToken, "Subhajit", "Ankita")
    const invite = (await app.inject({ method: "POST", url: "/invites", headers: bearer(inviter.accessToken) }))
      .json<{ code: string; inviteId: string }>()

    const recipient = await signIn(emails.recipient, "Ankita")
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

  async function sendOps(user: SignedIn, batchId: string, operations: ClientCanvasOperation[]) {
    const response = await app.inject({
      method: "POST",
      url: "/canvas/ops/batch",
      headers: bearer(user.accessToken),
      payload: { batchId, clientCreatedAt: new Date().toISOString(), operations },
    })
    expect(response.statusCode).toBe(200)
  }

  async function photoElements(user: SignedIn): Promise<CanvasPhotoElement[]> {
    const response = await app.inject({
      method: "GET",
      url: "/canvas/snapshot",
      // Legacy clients get the snapshot without photos.
      headers: { ...bearer(user.accessToken), "x-canvas-features": "photos" },
    })
    expect(response.statusCode).toBe(200)
    const elements = response.json<CanvasSnapshotBody>().snapshot.elements ?? []
    return elements.filter((element): element is CanvasPhotoElement => element.kind === "PHOTO")
  }

  function uploadPhoto(accessToken: string | null, image: Buffer, contentType = "image/jpeg") {
    const multipart = imageMultipart(image, contentType)
    return app.inject({
      method: "POST",
      url: "/canvas/photos",
      headers: {
        ...(accessToken ? bearer(accessToken) : {}),
        "content-type": multipart.contentType,
      },
      payload: multipart.body,
    })
  }

  async function uploadedPhotoId(user: SignedIn): Promise<string> {
    const upload = await uploadPhoto(user.accessToken, await jpeg(40, 30))
    expect(upload.statusCode).toBe(200)
    return upload.json<CanvasPhotoUploadResponse>().photoId
  }

  function downloadPhoto(accessToken: string | null, photoId: string) {
    return app.inject({
      method: "GET",
      url: `/canvas/photos/${photoId}`,
      headers: accessToken ? bearer(accessToken) : {},
    })
  }
})

function bearer(accessToken: string) {
  return { authorization: `Bearer ${accessToken}` }
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

/** A payload with fields a well-behaved client would never send. */
function photoPayloadWith(id: string, overrides: Record<string, unknown>): Record<string, unknown> {
  return { ...photoPayload(id), ...overrides }
}

function photoOperation(
  type: "ADD_PHOTO_ELEMENT" | "UPDATE_PHOTO_ELEMENT",
  clientOperationId: string,
  payload: CanvasPhotoElementPayload | Record<string, unknown>,
): ClientCanvasOperation {
  return {
    clientOperationId,
    type,
    strokeId: typeof payload.id === "string" ? payload.id : null,
    payload,
    clientCreatedAt: new Date().toISOString(),
  }
}

function deletePhotoOperation(clientOperationId: string, elementId: string): ClientCanvasOperation {
  return {
    clientOperationId,
    type: "DELETE_PHOTO_ELEMENT",
    strokeId: elementId,
    payload: {},
    clientCreatedAt: new Date().toISOString(),
  }
}

function solid(width: number, height: number): sharp.Sharp {
  return sharp({ create: { width, height, channels: 3, background: { r: 120, g: 30, b: 60 } } })
}

function jpeg(width: number, height: number): Promise<Buffer> {
  return solid(width, height).jpeg().toBuffer()
}

function imageMultipart(image: Buffer, contentType: string) {
  const boundary = `----mulberry-canvas-photo-${Date.now()}-${Math.random()}`
  return {
    body: Buffer.concat([
      Buffer.from(`--${boundary}\r\n`),
      Buffer.from(`Content-Disposition: form-data; name="image"; filename="photo"\r\nContent-Type: ${contentType}\r\n\r\n`),
      image,
      Buffer.from(`\r\n--${boundary}--\r\n`),
    ]),
    contentType: `multipart/form-data; boundary=${boundary}`,
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
