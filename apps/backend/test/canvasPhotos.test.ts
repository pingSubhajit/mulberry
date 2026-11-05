import { mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { DataType, newDb } from "pg-mem"
import sharp from "sharp"
import type { FastifyInstance } from "fastify"
import { afterEach, beforeEach, describe, expect, it } from "vitest"
import { createApp, type CreateAppOptions } from "../src/app/createApp.js"
import { HttpError } from "../src/infra/http/HttpError.js"
import { LocalCanvasPhotoStorage, type CanvasPhotoStorage, type CanvasPhotoUrlResponse } from "../src/modules/canvas/photos.js"
import { sweepOrphanedCanvasPhotos } from "../src/modules/canvas/photoSweep.js"
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

const DAY_MS = 24 * 60 * 60 * 1000

describe("Canvas photos", () => {
  let app: FastifyInstance
  let db: Database
  let storage: MemoryPhotoStorage

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
    storage = new MemoryPhotoStorage()
    app = await createApp(appOptions(db, storage))
    await app.ready()
  })

  afterEach(async () => {
    await app.close()
  })

  function appOptions(database: Database, photoStorage: CanvasPhotoStorage | null): CreateAppOptions {
    return {
      db: database,
      canvasPhotoStorage: photoStorage,
      canvasPhotoSweep: false,
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
    }
  }

  /** A second app on the same database, with other photo storage; closing it leaves the database open. */
  async function appWithStorage(photoStorage: CanvasPhotoStorage | null): Promise<FastifyInstance> {
    const second = await createApp(appOptions({ ...db, end: async () => {} }, photoStorage))
    await second.ready()
    return second
  }

  function appWithoutStorage(): Promise<FastifyInstance> {
    return appWithStorage(null)
  }

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

    it("stores the file at pairs/<pair>/<photo> and keeps only its details in the database", async () => {
      const { inviter } = await pairUsers()

      const jpegId = (await uploadPhoto(inviter.accessToken, await jpeg(40, 30))).json<CanvasPhotoUploadResponse>().photoId
      const cutout = await sharp({ create: { width: 20, height: 20, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } } })
        .png()
        .toBuffer()
      const pngId = (await uploadPhoto(inviter.accessToken, cutout, "image/png")).json<CanvasPhotoUploadResponse>().photoId

      const jpegKey = `pairs/${inviter.pairSessionId}/${jpegId}.jpg`
      const pngKey = `pairs/${inviter.pairSessionId}/${pngId}.png`
      expect([...storage.files.keys()].sort()).toEqual([jpegKey, pngKey].sort())
      expect(storage.files.get(jpegKey)?.contentType).toBe("image/jpeg")
      expect(storage.files.get(pngKey)?.contentType).toBe("image/png")

      const rows = await db.query<{ id: string; uploader_user_id: string; storage_key: string; bytes: Buffer | null }>(
        `SELECT id, uploader_user_id, storage_key, bytes FROM canvas_photos WHERE id = $1`,
        [jpegId],
      )
      expect(rows.rows).toEqual([{ id: jpegId, uploader_user_id: inviter.userId, storage_key: jpegKey, bytes: null }])
    })

    it("answers 503 when no photo storage is configured", async () => {
      const { inviter } = await pairUsers()
      const bare = await appWithoutStorage()
      try {
        const multipart = imageMultipart(await jpeg(40, 30), "image/jpeg")
        const upload = await bare.inject({
          method: "POST",
          url: "/canvas/photos",
          headers: { ...bearer(inviter.accessToken), "content-type": multipart.contentType },
          payload: multipart.body,
        })
        expect(upload.statusCode).toBe(503)
        expect(upload.json().code).toBe("CANVAS_PHOTO_STORAGE_UNAVAILABLE")
        expect((await db.query(`SELECT id FROM canvas_photos`)).rows).toEqual([])
      } finally {
        await bare.close()
      }
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

    it("serves the bytes from storage", async () => {
      const { inviter } = await pairUsers()
      const photoId = await uploadedPhotoId(inviter)
      const key = `pairs/${inviter.pairSessionId}/${photoId}.jpg`
      storage.files.set(key, { data: Buffer.from("stored-bytes"), contentType: "image/jpeg" })

      const download = await downloadPhoto(inviter.accessToken, photoId)
      expect(download.statusCode).toBe(200)
      expect(download.headers["content-type"]).toBe("image/jpeg")
      expect(download.headers["cache-control"]).toBe("private, max-age=31536000, immutable")
      expect(download.rawPayload).toEqual(Buffer.from("stored-bytes"))
    })

    it("moves a photo stored in the database into storage on its first read", async () => {
      const { inviter, recipient } = await pairUsers()
      const legacy = await jpeg(16, 16)
      await insertLegacyPhoto("legacy-1", inviter, legacy)

      const download = await downloadPhoto(recipient.accessToken, "legacy-1")
      expect(download.statusCode).toBe(200)
      expect(download.headers["content-type"]).toBe("image/jpeg")
      expect(download.rawPayload).toEqual(legacy)

      const key = `pairs/${inviter.pairSessionId}/legacy-1.jpg`
      expect(storage.files.get(key)?.data).toEqual(legacy)
      const row = await db.query<{ storage_key: string | null; bytes: Buffer | null }>(
        `SELECT storage_key, bytes FROM canvas_photos WHERE id = 'legacy-1'`,
      )
      expect(row.rows).toEqual([{ storage_key: key, bytes: null }])

      // Read again, it now comes from storage.
      expect((await downloadPhoto(inviter.accessToken, "legacy-1")).rawPayload).toEqual(legacy)
    })

    it("still serves a database-stored photo when no storage is configured", async () => {
      const { inviter } = await pairUsers()
      const legacy = await jpeg(16, 16)
      await insertLegacyPhoto("legacy-bare", inviter, legacy)
      const bare = await appWithoutStorage()
      try {
        const download = await bare.inject({ method: "GET", url: "/canvas/photos/legacy-bare", headers: bearer(inviter.accessToken) })
        expect(download.statusCode).toBe(200)
        expect(download.rawPayload).toEqual(legacy)
      } finally {
        await bare.close()
      }
    })

    it("stops serving a pair's photos and deletes them, files too, once the pair ends", async () => {
      const { inviter, recipient } = await pairUsers()
      const photoId = await uploadedPhotoId(inviter)
      const other = await pairUsers({ inviter: "other-a@elaris.dev", recipient: "other-b@elaris.dev" })
      const otherPhotoId = await uploadedPhotoId(other.inviter)

      const disconnect = await app.inject({ method: "POST", url: "/pairing/disconnect", headers: bearer(recipient.accessToken) })
      expect(disconnect.statusCode).toBe(200)

      expect((await downloadPhoto(inviter.accessToken, photoId)).statusCode).toBe(404)
      expect((await db.query(`SELECT id FROM canvas_photos`)).rows).toEqual([{ id: otherPhotoId }])
      expect([...storage.files.keys()]).toEqual([`pairs/${other.inviter.pairSessionId}/${otherPhotoId}.jpg`])
    })

    it("deletes a pair's photos and their files with either account", async () => {
      const { inviter, recipient } = await pairUsers()
      await uploadedPhotoId(inviter)
      await uploadedPhotoId(recipient)
      expect(storage.files.size).toBe(2)

      const deletion = await app.inject({ method: "DELETE", url: "/me", headers: bearer(recipient.accessToken) })
      expect(deletion.statusCode).toBe(204)
      expect((await db.query(`SELECT id FROM canvas_photos`)).rows).toEqual([])
      expect(storage.files.size).toBe(0)
    })

    it("ends the pair even when storage can't remove the files", async () => {
      const { inviter, recipient } = await pairUsers()
      await uploadedPhotoId(inviter)
      storage.failRemoves = true

      const disconnect = await app.inject({ method: "POST", url: "/pairing/disconnect", headers: bearer(recipient.accessToken) })
      expect(disconnect.statusCode).toBe(200)
      expect((await db.query(`SELECT id FROM canvas_photos`)).rows).toEqual([])
    })
  })

  describe("GET /canvas/photos/:photoId/url", () => {
    it("signs a URL for both people in the pair, and for nobody else", async () => {
      const { inviter, recipient } = await pairUsers()
      const photoId = await uploadedPhotoId(inviter)
      const key = `pairs/${inviter.pairSessionId}/${photoId}.jpg`

      for (const user of [inviter, recipient]) {
        const response = await photoUrl(user.accessToken, photoId)
        expect(response.statusCode).toBe(200)
        expect(response.json<CanvasPhotoUrlResponse>()).toEqual({
          url: `https://storage.example.test/${key}?expiresIn=3600`,
          expiresInSeconds: 3600,
        })
      }

      expect((await photoUrl(null, photoId)).statusCode).toBe(401)
      expect((await photoUrl(inviter.accessToken, "00000000-0000-4000-8000-000000000000")).statusCode).toBe(404)
      const other = await pairUsers({ inviter: "other-a@elaris.dev", recipient: "other-b@elaris.dev" })
      expect((await photoUrl(other.inviter.accessToken, photoId)).statusCode).toBe(404)
      const loner = await signIn("loner@elaris.dev", "Loner")
      expect((await photoUrl(loner.accessToken, photoId)).statusCode).toBe(404)
    })

    it("signs local files with the host the client called, and serves them", async () => {
      const { inviter } = await pairUsers()
      const root = mkdtempSync(join(tmpdir(), "mulberry-canvas-photos-"))
      const local = await appWithStorage(new LocalCanvasPhotoStorage(root))
      try {
        const multipart = imageMultipart(await jpeg(40, 30), "image/jpeg")
        const upload = await local.inject({
          method: "POST",
          url: "/canvas/photos",
          headers: { ...bearer(inviter.accessToken), "content-type": multipart.contentType },
          payload: multipart.body,
        })
        const photoId = upload.json<CanvasPhotoUploadResponse>().photoId

        const response = await local.inject({
          method: "GET",
          url: `/canvas/photos/${photoId}/url`,
          headers: { ...bearer(inviter.accessToken), host: "192.168.1.20:8080" },
        })
        const body = response.json<CanvasPhotoUrlResponse>()
        expect(body.expiresInSeconds).toBe(3600)
        const url = new URL(body.url)
        expect(`${url.origin}${url.pathname}`).toBe(
          `http://192.168.1.20:8080/dev/canvas-photos/pairs/${inviter.pairSessionId}/${photoId}.jpg`,
        )

        const file = await local.inject({ method: "GET", url: `${url.pathname}${url.search}` })
        expect(file.statusCode).toBe(200)
        expect(file.headers["content-type"]).toBe("image/jpeg")
        expect((await sharp(file.rawPayload).metadata()).width).toBe(40)
        const forged = await local.inject({ method: "GET", url: `${url.pathname}?expires=${url.searchParams.get("expires")}&sig=nope` })
        expect(forged.statusCode).toBe(403)
      } finally {
        await local.close()
        rmSync(root, { recursive: true, force: true })
      }
    })

    it("moves a database-stored photo into storage before signing it", async () => {
      const { inviter } = await pairUsers()
      const legacy = await jpeg(16, 16)
      await insertLegacyPhoto("legacy-url", inviter, legacy)

      const response = await photoUrl(inviter.accessToken, "legacy-url")
      expect(response.statusCode).toBe(200)
      const key = `pairs/${inviter.pairSessionId}/legacy-url.jpg`
      expect(response.json<CanvasPhotoUrlResponse>().url).toBe(`https://storage.example.test/${key}?expiresIn=3600`)
      expect(storage.files.get(key)?.data).toEqual(legacy)
    })
  })

  describe("unused photo sweep", () => {
    it("removes only photos no canvas element uses that are older than 14 days", async () => {
      const { inviter } = await pairUsers()
      const placed = await uploadedPhotoId(inviter)
      const cutout = await uploadedPhotoId(inviter)
      const unused = await uploadedPhotoId(inviter)
      const recent = await uploadedPhotoId(inviter)
      await sendOps(inviter, "sweep-batch", [
        photoOperation("ADD_PHOTO_ELEMENT", "op-placed", photoPayload("element-1", { photoId: placed })),
        photoOperation("ADD_PHOTO_ELEMENT", "op-cutout", photoPayload("element-2", { photoId: "gone", style: "STICKER", cutoutPhotoId: cutout })),
      ])
      const now = new Date()
      for (const id of [placed, cutout, unused]) {
        await db.query(`UPDATE canvas_photos SET created_at = $2 WHERE id = $1`, [id, new Date(now.getTime() - 15 * DAY_MS)])
      }
      // A database-stored photo from before storage, unused and old, has no file to remove.
      await insertLegacyPhoto("legacy-old", inviter, await jpeg(8, 8), new Date(now.getTime() - 30 * DAY_MS))

      const result = await sweepOrphanedCanvasPhotos(db, storage, { now })
      expect(result.removed.sort()).toEqual(["legacy-old", unused].sort())
      expect(result.next).toBeNull()

      const remaining = await db.query<{ id: string }>(`SELECT id FROM canvas_photos ORDER BY id`)
      expect(remaining.rows.map((row) => row.id).sort()).toEqual([placed, cutout, recent].sort())
      expect([...storage.files.keys()].sort()).toEqual(
        [placed, cutout, recent].map((id) => `pairs/${inviter.pairSessionId}/${id}.jpg`).sort(),
      )
    })

    it("works through old photos a batch at a time", async () => {
      const { inviter } = await pairUsers()
      const ids: string[] = []
      for (let index = 0; index < 5; index += 1) ids.push(await uploadedPhotoId(inviter))
      const now = new Date()
      for (const [index, id] of ids.entries()) {
        await db.query(`UPDATE canvas_photos SET created_at = $2 WHERE id = $1`, [id, new Date(now.getTime() - (20 - index) * DAY_MS)])
      }
      await sendOps(inviter, "sweep-keep", [
        photoOperation("ADD_PHOTO_ELEMENT", "op-keep", photoPayload("element-keep", { photoId: ids[1] })),
      ])

      const first = await sweepOrphanedCanvasPhotos(db, storage, { now, batchSize: 2 })
      expect([first.scanned, first.removed]).toEqual([2, [ids[0]]])
      expect(first.next?.id).toBe(ids[1])

      const second = await sweepOrphanedCanvasPhotos(db, storage, { now, batchSize: 2, after: first.next })
      expect([second.scanned, second.removed]).toEqual([2, [ids[2], ids[3]]])

      const third = await sweepOrphanedCanvasPhotos(db, storage, { now, batchSize: 2, after: second.next })
      expect([third.scanned, third.removed, third.next]).toEqual([1, [ids[4]], null])
      expect((await db.query<{ id: string }>(`SELECT id FROM canvas_photos`)).rows).toEqual([{ id: ids[1] }])
    })

    it("keeps a photo whose file won't delete, to try again later", async () => {
      const { inviter } = await pairUsers()
      const photoId = await uploadedPhotoId(inviter)
      const now = new Date(Date.now() + 15 * DAY_MS)
      storage.failRemoves = true

      expect((await sweepOrphanedCanvasPhotos(db, storage, { now })).removed).toEqual([])
      expect((await db.query(`SELECT id FROM canvas_photos`)).rows).toEqual([{ id: photoId }])

      storage.failRemoves = false
      expect((await sweepOrphanedCanvasPhotos(db, storage, { now })).removed).toEqual([photoId])
      expect(storage.files.size).toBe(0)
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

  function photoUrl(accessToken: string | null, photoId: string) {
    return app.inject({
      method: "GET",
      url: `/canvas/photos/${photoId}/url`,
      headers: accessToken ? bearer(accessToken) : {},
    })
  }

  /** A photo as stored before storage: its bytes in the row, no storage key. */
  async function insertLegacyPhoto(
    photoId: string,
    user: SignedIn & { pairSessionId: string },
    bytes: Buffer,
    createdAt = new Date(),
  ): Promise<void> {
    await db.query(
      `
      INSERT INTO canvas_photos (id, pair_session_id, uploader_user_id, content_type, width, height, bytes, created_at)
      VALUES ($1, $2, $3, 'image/jpeg', 16, 16, decode($4, 'base64'), $5)
      `,
      [photoId, user.pairSessionId, user.userId, bytes.toString("base64"), createdAt],
    )
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

/** Photo storage in memory, with signed URLs that show their path and lifetime. */
class MemoryPhotoStorage implements CanvasPhotoStorage {
  readonly files = new Map<string, { data: Buffer; contentType: string }>()
  failRemoves = false

  async upload(path: string, data: Buffer, contentType: string): Promise<void> {
    this.files.set(path, { data: Buffer.from(data), contentType })
  }

  async remove(paths: string[]): Promise<void> {
    if (this.failRemoves) throw new HttpError(502, "Unable to delete canvas photo: storage is down")
    for (const path of paths) this.files.delete(path)
  }

  async signedUrl(path: string, expiresInSeconds: number): Promise<string> {
    return `https://storage.example.test/${path}?expiresIn=${expiresInSeconds}`
  }

  async signedUrls(paths: string[], expiresInSeconds: number): Promise<string[]> {
    return Promise.all(paths.map((path) => this.signedUrl(path, expiresInSeconds)))
  }

  async download(path: string): Promise<Buffer> {
    const file = this.files.get(path)
    if (!file) throw new HttpError(404, "Canvas photo asset not found")
    return file.data
  }
}
