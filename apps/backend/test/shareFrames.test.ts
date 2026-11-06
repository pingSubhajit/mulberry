import { readFileSync } from "node:fs"
import Fastify, { type FastifyInstance } from "fastify"
import multipart from "@fastify/multipart"
import { newDb } from "pg-mem"
import sharp from "sharp"
import { afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest"
import { runMigrations, type Database } from "../src/infra/db/database.js"
import { HttpError } from "../src/infra/http/HttpError.js"
import { registerCatalogAdminHooks } from "../src/modules/catalog/adminRoutes.js"
import { createSession, requireSessionContext } from "../src/modules/_shared/session.js"
import type { ShareFrameLayout } from "../src/modules/shareFrames/layout.js"
import { registerShareFrameRoutes } from "../src/modules/shareFrames/routes.js"
import { ShareFrameCatalogService, type ShareFrameAdminItem } from "../src/modules/shareFrames/service.js"
import type { ShareFrameStorage } from "../src/modules/shareFrames/storage.js"

const headers = { "x-admin-password": "secret" }
const fixtureDir = new URL("./fixtures/share-frames/plain/", import.meta.url)
const realLayoutJson = readFileSync(new URL("layout.json", fixtureDir), "utf8")
const realLayout = JSON.parse(realLayoutJson) as ShareFrameLayout
const frameUrl = "/admin/share-frames/plain/1"

const WIDTH = 1080
const HEIGHT = 1920

/** Pixel centres inside the plain layout's quad, shrunk by `inset` on each side in quad units. */
function insideQuad(x: number, y: number, inset: number, layout: ShareFrameLayout = realLayout): boolean {
  const { topLeft, topRight, bottomLeft } = layout.content.quad
  const ux = topRight.x - topLeft.x
  const uy = topRight.y - topLeft.y
  const vx = bottomLeft.x - topLeft.x
  const vy = bottomLeft.y - topLeft.y
  const det = ux * vy - uy * vx
  const dx = x + 0.5 - topLeft.x
  const dy = y + 0.5 - topLeft.y
  const u = (vy * dx - vx * dy) / det
  const v = (-uy * dx + ux * dy) / det
  return u >= inset && u <= 1 - inset && v >= inset && v <= 1 - inset
}

/** A synthetic 1080×1920 frame: opaque, with a transparent screen inside the quad and an optional stray hole. */
async function syntheticFrame(options: { hole?: { x: number; y: number } } = {}): Promise<Buffer> {
  const raw = Buffer.alloc(WIDTH * HEIGHT * 4)
  for (let y = 0; y < HEIGHT; y += 1) {
    for (let x = 0; x < WIDTH; x += 1) {
      const offset = (y * WIDTH + x) * 4
      raw[offset] = 200
      raw[offset + 1] = 40
      raw[offset + 2] = 60
      raw[offset + 3] = insideQuad(x, y, 0.02) ? (y % 97 === 0 ? 12 : 0) : 255
    }
  }
  if (options.hole) raw[(options.hole.y * WIDTH + options.hole.x) * 4 + 3] = 128
  return sharp(raw, { raw: { width: WIDTH, height: HEIGHT, channels: 4 } }).png({ compressionLevel: 1 }).toBuffer()
}

interface Part {
  name: string
  value: string | Buffer
  filename?: string
  type?: string
}

describe("share frame catalog", () => {
  let frame: Buffer
  let strayAlphaFrame: Buffer
  let realFrame: Buffer
  let app: FastifyInstance
  let db: Database
  let assets: Map<string, Buffer>
  let failAudit: boolean
  let accessToken: string

  beforeAll(async () => {
    frame = await syntheticFrame()
    strayAlphaFrame = await syntheticFrame({ hole: { x: 4, y: 4 } })
    realFrame = readFileSync(new URL("frame.png", fixtureDir))
  })

  beforeEach(async () => {
    // pg-mem otherwise rejects constraints in repeated CREATE TABLE IF NOT EXISTS no-ops.
    const memory = newDb({ noAstCoverageCheck: true })
    const { Pool } = memory.adapters.createPg()
    const pool = new Pool()
    await runMigrations(pool)
    failAudit = false
    db = {
      query: async <T>(sql: string, values?: unknown[]) => {
        if (failAudit && sql.includes("INSERT INTO catalog_admin.admin_catalog_changes")) throw new Error("audit unavailable")
        return pool.query(sql, values) as Promise<{ rows: T[] }>
      },
      transaction: async (fn) => {
        const backup = memory.backup()
        try { return await fn(db) } catch (error) { backup.restore(); throw error }
      },
      end: async () => pool.end(),
    }
    await db.query("INSERT INTO users (id, google_subject, email) VALUES ('user-1', 'google-1', 'one@example.com')")
    accessToken = (await createSession(db, "user-1")).access_token

    assets = new Map()
    const storage: ShareFrameStorage = {
      upload: async (path, data) => { assets.set(path, data) },
      publicUrl: (path) => `https://assets.test/${path}`,
      download: async (path) => {
        const data = assets.get(path)
        if (!data) throw new HttpError(404, "missing")
        return data
      },
    }
    app = Fastify()
    await app.register(multipart, { limits: { fileSize: 20 * 1024 * 1024, files: 1 } })
    app.setErrorHandler((error, _request, reply) => {
      if (error instanceof HttpError) return reply.code(error.statusCode).send({ ...error.details, message: error.message })
      const status = (error as { statusCode?: number }).statusCode
      if (status && status < 500) return reply.code(status).send({ code: "INVALID_CATALOG_INPUT", message: (error as Error).message })
      return reply.code(500).send({ message: (error as Error).message })
    })
    registerCatalogAdminHooks(app, "secret")
    registerShareFrameRoutes(app, new ShareFrameCatalogService(db, storage, "secret"), storage, (token) => requireSessionContext(db, token))
    await app.ready()
  })
  afterEach(async () => { await app.close(); await db.end() })

  const send = (url: string, parts: Part[], method: "POST" | "PUT" = "POST") => {
    const boundary = "share-frame-test-boundary"
    const chunks: Buffer[] = []
    for (const part of parts) {
      const disposition = `form-data; name="${part.name}"${part.filename ? `; filename="${part.filename}"` : ""}`
      const type = part.filename ? `\r\nContent-Type: ${part.type ?? "application/octet-stream"}` : ""
      chunks.push(Buffer.from(`--${boundary}\r\nContent-Disposition: ${disposition}${type}\r\n\r\n`), Buffer.from(part.value), Buffer.from("\r\n"))
    }
    chunks.push(Buffer.from(`--${boundary}--\r\n`))
    return app.inject({ method, url, headers: { ...headers, "content-type": `multipart/form-data; boundary=${boundary}` }, payload: Buffer.concat(chunks) })
  }
  const image = (data: Buffer = frame, type = "image/png"): Part => ({ name: "image", value: data, filename: "frame.png", type })
  const layoutPart = (layout: unknown = realLayout): Part => ({ name: "layout", value: typeof layout === "string" ? layout : JSON.stringify(layout) })
  const fields = (values: Record<string, string>): Part[] => Object.entries(values).map(([name, value]) => ({ name, value }))
  const create = (values: Record<string, string> = {}, layout: unknown = realLayout, data: Buffer = frame) =>
    send("/admin/share-frames", [...fields({ frameKey: "plain", title: "Plain", ...values }), layoutPart(layout), image(data)])
  const json = (method: "POST" | "PATCH" | "DELETE", url: string, payload?: object) => app.inject({ method, url, headers, ...(payload ? { payload } : {}) })
  const get = (url: string) => app.inject({ url, headers })
  const history = async () => (await get("/admin/share-frames/history")).json().items as { id: string; action: string; previous: Record<string, unknown> | null; current: Record<string, unknown> | null }[]
  const publicList = () => app.inject({ url: "/share-frames", headers: { authorization: `Bearer ${accessToken}` } })
  const withQuad = (quad: Partial<ShareFrameLayout["content"]["quad"]>): ShareFrameLayout =>
    ({ ...realLayout, content: { ...realLayout.content, quad: { ...realLayout.content.quad, ...quad } } })

  it("authenticates every admin route before reading uploads, and needs a session for the public list", async () => {
    for (const [method, url] of [
      ["GET", "/admin/share-frames"], ["GET", frameUrl], ["POST", "/admin/share-frames"], ["PATCH", frameUrl],
      ["PUT", `${frameUrl}/content`], ["POST", `${frameUrl}/copy`], ["DELETE", frameUrl],
      ["GET", "/admin/share-frames/history"], ["POST", "/admin/share-frames/history/x/restore"],
    ] as const) {
      const response = await app.inject({ method, url })
      expect(response.statusCode, `${method} ${url}`).toBe(401)
      expect(response.json().code).toBe("ADMIN_UNAUTHORIZED")
    }
    expect((await app.inject({ url: "/admin/share-frames", headers: { "x-admin-password": "wrong" } })).statusCode).toBe(401)
    expect((await app.inject({ url: "/share-frames" })).statusCode).toBe(401)
    expect((await app.inject({ url: "/share-frames", headers: { authorization: "Bearer not-a-session" } })).statusCode).toBe(401)
    expect((await publicList()).statusCode).toBe(200)
    expect(assets.size).toBe(0)
  })

  it("creates a draft with the original PNG and WebP variants at fresh immutable paths", async () => {
    const response = await create({ sortOrder: "20", availableFrom: "2027-02-01", availableUntil: "2027-02-15" })
    expect(response.statusCode, response.body).toBe(200)
    const item = response.json() as ShareFrameAdminItem
    expect(item).toMatchObject({
      key: "plain", version: 1, title: "Plain", sortOrder: 20, availableFrom: "2027-02-01", availableUntil: "2027-02-15",
      width: 1080, height: 1920, published: false, contentLocked: false, publishedAt: null, layout: realLayout,
    })
    expect(item.message).toBeTruthy()
    const pathOf = (url: string) => new URL(url).pathname.slice(1)
    expect(pathOf(item.frameUrl)).toMatch(/^share-frames\/plain\/1\/[0-9a-f-]{36}\/full\.webp$/)
    expect(pathOf(item.thumbnailUrl)).toMatch(/^share-frames\/plain\/1\/[0-9a-f-]{36}\/thumb\.webp$/)
    expect(pathOf(item.originalUrl)).toMatch(/^share-frames\/plain\/1\/[0-9a-f-]{36}\/original\.png$/)
    expect(assets.size).toBe(3)
    expect(assets.get(pathOf(item.originalUrl))).toEqual(frame)
    const full = await sharp(assets.get(pathOf(item.frameUrl))!).metadata()
    expect(full).toMatchObject({ format: "webp", width: 1080, height: 1920, hasAlpha: true })
    const thumb = await sharp(assets.get(pathOf(item.thumbnailUrl))!).metadata()
    expect(thumb).toMatchObject({ format: "webp", width: 270, height: 480, hasAlpha: true })
    expect((await get(frameUrl)).json()).toMatchObject({ key: "plain", version: 1, published: false })
    expect((await publicList()).json().items).toEqual([])
    expect((await history()).map((entry) => entry.action)).toEqual(["createShareFrame"])
  })

  it("rejects images that are the wrong size, have no alpha or don't decode, without writes", async () => {
    const small = await sharp({ create: { width: 540, height: 960, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } } }).png().toBuffer()
    const opaque = await sharp({ create: { width: 1080, height: 1920, channels: 3, background: "red" } }).png().toBuffer()
    const jpeg = await sharp({ create: { width: 1080, height: 1920, channels: 3, background: "red" } }).jpeg().toBuffer()
    for (const [data, message] of [[small, "exactly 1080×1920"], [opaque, "alpha channel"], [jpeg, "must be a PNG"], [Buffer.from("not a png"), "could not be decoded"]] as const) {
      const response = await create({}, realLayout, data)
      expect(response.statusCode).toBe(400)
      expect(response.json().code).toBe("INVALID_FRAME_IMAGE")
      expect(response.json().message).toContain(message)
    }
    expect((await send("/admin/share-frames", [...fields({ frameKey: "plain", title: "Plain" }), layoutPart()])).json().message).toContain("image is required")
    expect(assets.size).toBe(0)
    expect(await history()).toHaveLength(0)
  })

  it("rejects bad layouts: schema, version, key, ratio, shape and alpha outside the quad", async () => {
    const { topRight, bottomRight } = realLayout.content.quad
    const cases: [unknown, string, Buffer?][] = [
      ["{not json", "not valid JSON"],
      [{ ...realLayout, brand: undefined }, "layout.brand is required"],
      [{ ...realLayout, extra: true }, "layout.extra is not a layout field"],
      [{ ...realLayout, layoutVersion: 2 }, "layout.layoutVersion must be 1"],
      [{ ...realLayout, key: "polaroid" }, "must equal frameKey"],
      [{ ...realLayout, canvas: { width: 1080, height: 1080 } }, "layout.canvas"],
      [{ ...realLayout, caption: { ...realLayout.caption, inkColor: "red" } }, "#RRGGBB"],
      [{ ...realLayout, caption: { ...realLayout.caption, anchor: "center" } }, "layout.caption.anchor"],
      [{ ...realLayout, caption: { ...realLayout.caption, minFontSize: 80 } }, "minFontSize"],
      [withQuad({ topRight: { x: topRight.x + 40, y: topRight.y }, bottomRight: { x: bottomRight.x + 40, y: bottomRight.y } }), "within 1%"],
      [withQuad({ bottomRight: { x: bottomRight.x + 5, y: bottomRight.y } }), "parallelogram"],
      [realLayout, "outside layout.content.quad", strayAlphaFrame],
    ]
    for (const [layout, message, data] of cases) {
      const response = await create({}, layout, data)
      expect(response.statusCode, message).toBe(400)
      expect(response.json().code).toBe("INVALID_FRAME_LAYOUT")
      expect(response.json().message).toContain(message)
    }
    const stray = await create({}, realLayout, strayAlphaFrame)
    expect(stray.json()).toMatchObject({ outsidePixels: 1, outsideBounds: { minX: 4, minY: 4, maxX: 4, maxY: 4 } })
    expect(assets.size).toBe(0)
    expect(await history()).toHaveLength(0)
  })

  it("rejects bad keys, versions, dates and fields without writes", async () => {
    const cases: [Record<string, string>, string][] = [
      [{ frameKey: "Bad Key" }, "frameKey"],
      [{ frameKey: "history" }, "frameKey"],
      [{ frameVersion: "0" }, "frameVersion"],
      [{ frameVersion: "1.5" }, "integers"],
      [{ title: " " }, "title is required"],
      [{ availableFrom: "2027-02-30" }, "real calendar date"],
      [{ availableUntil: "2027-2-1" }, "real calendar date"],
      [{ availableFrom: "2027-03-01", availableUntil: "2027-02-01" }, "must not be after"],
      [{ published: "yes" }, "true or false"],
      [{ surprise: "1" }, "Unknown field surprise"],
    ]
    for (const [values, message] of cases) {
      const response = await create(values)
      expect(response.statusCode, message).toBe(400)
      expect(response.json().code).toBe("INVALID_CATALOG_INPUT")
      expect(response.json().message).toContain(message)
    }
    expect(assets.size).toBe(0)
    expect(await history()).toHaveLength(0)
  })

  it("rejects a duplicate version before uploading anything", async () => {
    expect((await create()).statusCode).toBe(200)
    const count = assets.size
    const response = await create()
    expect(response.statusCode).toBe(409)
    expect(response.json().code).toBe("FRAME_VERSION_EXISTS")
    expect(assets.size).toBe(count)
  })

  it("replaces a draft's image or layout, checking a new layout against the stored image", async () => {
    const original = (await create()).json() as ShareFrameAdminItem
    const replaced = await send(`${frameUrl}/content`, [image()], "PUT")
    expect(replaced.statusCode, replaced.body).toBe(200)
    expect(replaced.json().frameUrl).not.toBe(original.frameUrl)
    expect(assets.size).toBe(6)

    // The synthetic screen is inset 2% inside the quad, so a quad 3% smaller leaves transparent pixels outside it.
    const { topLeft, topRight, bottomLeft } = realLayout.content.quad
    const shrink = (point: { x: number; y: number }, center: { x: number; y: number }) => ({ x: center.x + (point.x - center.x) * 0.94, y: center.y + (point.y - center.y) * 0.94 })
    const center = { x: (topRight.x + bottomLeft.x) / 2, y: (topRight.y + bottomLeft.y) / 2 }
    const tl = shrink(topLeft, center)
    const tr = shrink(topRight, center)
    const bl = shrink(bottomLeft, center)
    const smaller = withQuad({ topLeft: tl, topRight: tr, bottomLeft: bl, bottomRight: { x: tr.x + bl.x - tl.x, y: tr.y + bl.y - tl.y } })
    const rejected = await send(`${frameUrl}/content`, [layoutPart(smaller)], "PUT")
    expect(rejected.statusCode).toBe(400)
    expect(rejected.json().code).toBe("INVALID_FRAME_LAYOUT")

    const moved = { ...realLayout, caption: { ...realLayout.caption, rotationDegrees: -5 } }
    const layoutOnly = await send(`${frameUrl}/content`, [{ name: "layout", value: JSON.stringify(moved), filename: "layout.json", type: "application/json" }], "PUT")
    expect(layoutOnly.statusCode, layoutOnly.body).toBe(200)
    expect(layoutOnly.json()).toMatchObject({ frameUrl: replaced.json().frameUrl, layout: moved })
    expect((await send(`${frameUrl}/content`, [], "PUT")).json().message).toContain("new image, a new layout, or both")
    expect((await send("/admin/share-frames/plain/9/content", [image()], "PUT")).json().code).toBe("FRAME_NOT_FOUND")
  })

  it("locks the image and layout once published, even after hiding, while metadata stays editable", async () => {
    await create({ sortOrder: "10" })
    const published = await json("PATCH", frameUrl, { published: true })
    expect(published.json()).toMatchObject({ published: true, contentLocked: true })
    expect(published.json().publishedAt).toBeTruthy()
    for (const response of [await send(`${frameUrl}/content`, [image()], "PUT"), await send(`${frameUrl}/content`, [layoutPart()], "PUT")]) {
      expect(response.statusCode).toBe(409)
      expect(response.json().code).toBe("FRAME_VERSION_IMMUTABLE")
    }

    const edited = await json("PATCH", frameUrl, { title: "Plain red", sortOrder: 5, availableFrom: "2026-12-15", availableUntil: "2026-12-26" })
    expect(edited.statusCode, edited.body).toBe(200)
    expect(edited.json()).toMatchObject({ title: "Plain red", sortOrder: 5, availableFrom: "2026-12-15", availableUntil: "2026-12-26", published: true })
    expect((await json("PATCH", frameUrl, { availableUntil: null })).json()).toMatchObject({ availableFrom: "2026-12-15", availableUntil: null })
    expect((await json("PATCH", frameUrl, { availableUntil: "2026-12-01" })).json().message).toContain("must not be after")
    for (const body of [{ availableFrom: 20261215 }, { availableFrom: "15/12/2026" }, { published: "false" }, { sortOrder: 1.5 }, { title: "" }, { description: "x" }, { layout: {} }]) {
      expect((await json("PATCH", frameUrl, body)).statusCode, JSON.stringify(body)).toBe(400)
    }

    const hidden = await json("PATCH", frameUrl, { published: false })
    expect(hidden.json()).toMatchObject({ published: false, contentLocked: true, publishedAt: null })
    expect((await send(`${frameUrl}/content`, [image()], "PUT")).json().code).toBe("FRAME_VERSION_IMMUTABLE")
    expect((await publicList()).json().items).toEqual([])
  })

  it("copies a version to a new unlocked draft that shares its files", async () => {
    await create({ availableFrom: "2027-02-01", availableUntil: "2027-02-15" })
    await json("PATCH", frameUrl, { published: true })
    const source = (await get(frameUrl)).json() as ShareFrameAdminItem
    const count = assets.size
    const copy = await json("POST", `${frameUrl}/copy`, { newVersion: 2 })
    expect(copy.statusCode, copy.body).toBe(200)
    expect(copy.json()).toMatchObject({
      key: "plain", version: 2, title: "Plain", published: false, contentLocked: false,
      availableFrom: "2027-02-01", availableUntil: "2027-02-15", frameUrl: source.frameUrl, layout: realLayout,
    })
    expect(assets.size).toBe(count)
    expect((await send("/admin/share-frames/plain/2/content", [image()], "PUT")).statusCode).toBe(200)
    expect((await get(frameUrl)).json()).toEqual({ ...source, message: "Catalog loaded." })
    expect((await json("POST", `${frameUrl}/copy`, { newVersion: 2 })).json().code).toBe("FRAME_VERSION_EXISTS")
    expect((await json("POST", `${frameUrl}/copy`, { newVersion: 1 })).statusCode).toBe(400)
    expect((await json("POST", `${frameUrl}/copy`, { newVersion: "3" } as unknown as object)).statusCode).toBe(400)
    expect((await json("POST", "/admin/share-frames/plain/7/copy", { newVersion: 8 })).json().code).toBe("FRAME_NOT_FOUND")
  })

  it("needs confirmation to delete, keeps assets, reserves the version and restores the deletion", async () => {
    const created = (await create({ availableFrom: "2027-10-20", availableUntil: "2027-11-01" })).json() as ShareFrameAdminItem
    const count = assets.size
    const unconfirmed = await json("DELETE", frameUrl)
    expect(unconfirmed.statusCode).toBe(409)
    expect(unconfirmed.json()).toMatchObject({ code: "CONFIRMATION_REQUIRED", current: { key: "plain", version: 1 } })
    expect((await json("DELETE", frameUrl, { confirm: "true" } as unknown as object)).statusCode).toBe(400)
    const deleted = await json("DELETE", frameUrl, { confirm: true })
    expect(deleted.statusCode).toBe(200)
    expect(deleted.json().message).toContain("deleted")
    expect((await get("/admin/share-frames")).json().items).toEqual([])
    expect((await get(frameUrl)).json().code).toBe("FRAME_NOT_FOUND")
    expect(assets.size).toBe(count)
    expect((await create()).json().code).toBe("FRAME_VERSION_EXISTS")

    const deletion = (await history()).find((entry) => entry.action === "deleteShareFrame")!
    expect(deletion.previous).toMatchObject({ frame_key: "plain", available_from: "2027-10-20", available_until: "2027-11-01" })
    expect(deletion.current).toBeNull()
    const restoreUrl = `/admin/share-frames/history/${deletion.id}/restore`
    expect((await json("POST", restoreUrl)).json().code).toBe("CONFIRMATION_REQUIRED")
    expect((await json("POST", restoreUrl, { confirm: true })).statusCode).toBe(200)
    const restored = (await get(frameUrl)).json()
    expect(restored).toMatchObject({ frameUrl: created.frameUrl, availableFrom: "2027-10-20", availableUntil: "2027-11-01", layout: realLayout })
  })

  it("restores history without erasing later edits or unlocking published content", async () => {
    await create({ availableFrom: "2026-10-29", availableUntil: "2026-11-09" })
    await json("PATCH", frameUrl, { published: true })
    const publish = (await history()).find((entry) => entry.action === "updateShareFrame")!
    await json("PATCH", frameUrl, { title: "Later", availableFrom: null })
    const stale = await json("POST", `/admin/share-frames/history/${publish.id}/restore`, { confirm: true })
    expect(stale.statusCode).toBe(409)
    expect(stale.json().code).toBe("CATALOG_STATE_CHANGED")

    const later = (await history()).find((entry) => (entry.current as { title?: string } | null)?.title === "Later")!
    expect((await json("POST", `/admin/share-frames/history/${later.id}/restore`, { confirm: true })).statusCode).toBe(200)
    expect((await get(frameUrl)).json()).toMatchObject({ title: "Plain", availableFrom: "2026-10-29", availableUntil: "2026-11-09", published: true })
    expect((await json("POST", `/admin/share-frames/history/${publish.id}/restore`, { confirm: true })).statusCode).toBe(200)
    expect((await get(frameUrl)).json()).toMatchObject({ published: false, contentLocked: true })
    expect((await send(`${frameUrl}/content`, [image()], "PUT")).json().code).toBe("FRAME_VERSION_IMMUTABLE")
    expect((await json("POST", "/admin/share-frames/history/missing/restore", { confirm: true })).json().code).toBe("CATALOG_CHANGE_NOT_FOUND")
  })

  it("rolls back the catalog write when history cannot be saved", async () => {
    await create()
    failAudit = true
    expect((await json("PATCH", frameUrl, { title: "Must roll back" })).statusCode).toBe(500)
    failAudit = false
    expect((await get(frameUrl)).json().title).toBe("Plain")
  })

  it("lists every published frame, in or out of its window, in the documented shape", async () => {
    const polaroid = { ...realLayout, key: "polaroid" }
    const halloween = { ...realLayout, key: "halloween" }
    await create({ frameKey: "polaroid", title: "Polaroid", sortOrder: "10", published: "true" }, polaroid)
    await create({ frameKey: "halloween", title: "Halloween", sortOrder: "40", availableFrom: "2020-10-20", availableUntil: "2020-11-01", published: "true" }, halloween)
    await create({ frameKey: "valentines", title: "Valentine's", sortOrder: "30" }, { ...realLayout, key: "valentines" })

    const response = await publicList()
    expect(response.statusCode).toBe(200)
    const items = response.json().items as Record<string, unknown>[]
    expect(items.map((item) => item.key)).toEqual(["polaroid", "halloween"])
    expect(Object.keys(items[1]).sort()).toEqual(
      ["availableFrom", "availableUntil", "frameUrl", "height", "key", "layout", "sortOrder", "thumbnailUrl", "title", "version", "width"])
    expect(items[1]).toMatchObject({
      key: "halloween", version: 1, title: "Halloween", sortOrder: 40, availableFrom: "2020-10-20", availableUntil: "2020-11-01",
      width: 1080, height: 1920, layout: halloween,
    })
    expect(items[0]).toMatchObject({ availableFrom: null, availableUntil: null })
    expect(items[1].frameUrl).toMatch(/\/full\.webp$/)
    expect(items[1].thumbnailUrl).toMatch(/\/thumb\.webp$/)
    expect((await get("/admin/share-frames")).json().items).toHaveLength(3)
  })

  it("accepts the real plain frame with its layout sent as a file, keeping alpha exact in the WebP", async () => {
    const response = await send("/admin/share-frames", [
      ...fields({ frameKey: "plain", title: "Plain", sortOrder: "20", published: "true" }),
      { name: "layout", value: realLayoutJson, filename: "layout.json", type: "application/json" },
      image(realFrame),
    ])
    expect(response.statusCode, response.body).toBe(200)
    const item = response.json() as ShareFrameAdminItem
    expect(item).toMatchObject({ key: "plain", published: true, contentLocked: true, layout: realLayout })
    const webp = assets.get(new URL(item.frameUrl).pathname.slice(1))!
    const [pngAlpha, webpAlpha] = await Promise.all([realFrame, webp].map((data) => sharp(data).extractChannel("alpha").raw().toBuffer()))
    expect(webpAlpha.equals(pngAlpha)).toBe(true)
    expect((await publicList()).json().items).toHaveLength(1)
  })
})
