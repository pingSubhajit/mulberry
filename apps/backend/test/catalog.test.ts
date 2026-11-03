import Fastify, { type FastifyInstance } from "fastify"
import multipart from "@fastify/multipart"
import { newDb } from "pg-mem"
import sharp from "sharp"
import { afterEach, beforeEach, describe, expect, it } from "vitest"
import { runMigrations, type Database } from "../src/infra/db/database.js"
import { HttpError } from "../src/infra/http/HttpError.js"
import { registerCatalogAdminHooks } from "../src/modules/catalog/adminRoutes.js"
import { StickerCatalogService, type StickerStorage } from "../src/modules/stickers/service.js"
import { registerStickerRoutes } from "../src/modules/stickers/routes.js"
import { WallpaperCatalogService, SharpWallpaperImageProcessor, type WallpaperStorage } from "../src/modules/wallpapers/service.js"
import { registerWallpaperRoutes } from "../src/modules/wallpapers/routes.js"

const headers = { "x-admin-password": "secret" }
const packUrl = "/admin/stickers/packs/cats/1"

describe("agent catalog operations", () => {
  let app: FastifyInstance
  let db: Database
  let assets: Map<string, Buffer>
  let png: Buffer
  let failAudit: boolean

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
    assets = new Map()
    const storage: StickerStorage & WallpaperStorage = {
      upload: async (path, data) => { assets.set(path, data) },
      remove: async (paths) => { for (const path of paths) assets.delete(path) },
      signedUrl: async (path) => `https://assets.test/${path}?signed=yes`,
      signedUrls: async (paths) => paths.map((path) => `https://assets.test/${path}?signed=yes`),
      publicUrl: (path) => `https://assets.test/${path}`,
    }
    png = await sharp({ create: { width: 800, height: 900, channels: 4, background: { r: 255, g: 0, b: 0, alpha: 0.5 } } }).png().toBuffer()
    app = Fastify()
    await app.register(multipart, { limits: { fileSize: 20 * 1024 * 1024 } })
    app.setErrorHandler((error, _request, reply) => {
      if (error instanceof HttpError) return reply.code(error.statusCode).send({ ...error.details, message: (error as Error).message })
      return reply.code(500).send({ message: (error as Error).message })
    })
    registerCatalogAdminHooks(app, "secret")
    registerStickerRoutes(app, new StickerCatalogService(db, storage, "secret"))
    registerWallpaperRoutes(app, new WallpaperCatalogService(db, storage, new SharpWallpaperImageProcessor(), { adminPassword: "secret" }))
    await app.ready()
  })
  afterEach(async () => { await app.close(); await db.end() })

  const json = (method: "POST" | "PATCH" | "DELETE", url: string, payload?: object) => app.inject({ method, url, headers, ...(payload ? { payload } : {}) })
  const upload = (url: string, fields: Record<string, string>, method: "POST" | "PUT" = "POST", data?: Buffer) => {
    const boundary = "catalog-test-boundary"
    const parts: Buffer[] = Object.entries(fields).map(([key, value]) => Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="${key}"\r\n\r\n${value}\r\n`))
    parts.push(Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="image"; filename="image.png"\r\nContent-Type: image/png\r\n\r\n`), data ?? png, Buffer.from(`\r\n--${boundary}--\r\n`))
    return app.inject({ method, url, headers: { ...headers, "content-type": `multipart/form-data; boundary=${boundary}` }, payload: Buffer.concat(parts) })
  }
  const create = (version = "1") => upload("/admin/stickers/packs", { packKey: "cats", packVersion: version, title: "Cats" })
  const sticker = (id = "cat") => upload(`${packUrl}/stickers`, { stickerId: id })
  const get = (url: string) => app.inject({ url, headers })

  it("authenticates every admin operation before reading uploads or history", async () => {
    for (const [method, url] of [["GET", "/admin/stickers/history"], ["POST", "/admin/stickers/packs"], ["PUT", "/admin/wallpapers/x/image"], ["POST", "/admin/wallpapers/history/x/restore"]] as const) {
      const response = await app.inject({ method, url })
      expect(response.statusCode).toBe(401)
      expect(response.json().code).toBe("ADMIN_UNAUTHORIZED")
    }
  })

  it("rejects duplicate versions before uploading and never resets a live pack", async () => {
    expect((await create()).statusCode).toBe(200)
    expect((await sticker()).statusCode).toBe(200)
    await json("PATCH", packUrl, { published: true })
    const count = assets.size
    const response = await create()
    expect(response.statusCode).toBe(409)
    expect(response.json().code).toBe("PACK_VERSION_EXISTS")
    expect(assets.size).toBe(count)
    expect((await get(packUrl)).json()).toMatchObject({ published: true, stickerCount: 1 })
  })

  it("locks published art, covers and sticker changes even after hiding", async () => {
    await create(); await sticker()
    await json("PATCH", packUrl, { published: true })
    await json("PATCH", packUrl, { published: false, title: "Hidden cats" })
    for (const response of [await sticker(), await upload(`${packUrl}/cover`, {}, "PUT"), await json("PATCH", `${packUrl}/stickers/cat`, { sortOrder: 4 }), await json("DELETE", `${packUrl}/stickers/cat`)]) {
      expect(response.statusCode).toBe(409)
      expect(response.json().code).toBe("PACK_VERSION_IMMUTABLE")
    }
  })

  it("copies a live version to a draft, reorders and deletes without changing the source", async () => {
    await create(); await sticker(); await sticker("other")
    await json("PATCH", packUrl, { published: true })
    const copy = await json("POST", `${packUrl}/copy`, { newVersion: 2 })
    expect(copy.statusCode, copy.body).toBe(200)
    expect(copy.json()).toMatchObject({ packVersion: 2, published: false, stickerCount: 2 })
    const original = (await get(packUrl)).json()
    expect((await json("PATCH", "/admin/stickers/packs/cats/2/stickers/cat", { sortOrder: 8 })).statusCode).toBe(200)
    expect((await json("DELETE", "/admin/stickers/packs/cats/2/stickers/other")).statusCode).toBe(200)
    expect((await get(packUrl)).json()).toEqual(original)
    expect((await json("POST", `${packUrl}/copy`, { newVersion: 2 })).statusCode).toBe(409)
    expect((await json("POST", `${packUrl}/copy`, { newVersion: 1 })).statusCode).toBe(400)
  })

  it("requires explicit deletion confirmation, retains shared assets and restores a deleted version", async () => {
    await create(); await sticker()
    await json("POST", `${packUrl}/copy`, { newVersion: 2 })
    const count = assets.size
    expect((await json("DELETE", packUrl)).statusCode).toBe(409)
    expect((await json("DELETE", packUrl, { confirm: "true" } as unknown as object)).statusCode).toBe(400)
    expect((await json("DELETE", packUrl, { confirm: true })).statusCode).toBe(200)
    expect(assets.size).toBe(count)
    expect((await get("/admin/stickers/packs/cats/2")).json().stickerCount).toBe(1)
    expect((await create()).statusCode).toBe(409)
    const history = (await get("/admin/stickers/history")).json().items
    const deletion = history.find((entry: { action: string }) => entry.action === "deleteStickerPackVersionForAdmin")
    expect(deletion.previous.stickers).toHaveLength(1)
    expect(deletion.current).toBeNull()
    const restoreUrl = `/admin/stickers/history/${deletion.id}/restore`
    expect((await json("POST", restoreUrl)).statusCode).toBe(409)
    expect((await json("POST", restoreUrl, { confirm: true })).statusCode).toBe(200)
    expect((await get(packUrl)).json().stickerCount).toBe(1)
  })

  it("rejects bad input without changing content or adding history", async () => {
    await create()
    const count = (await get("/admin/stickers/history")).json().items.length
    for (const body of [{ published: "false" }, { sortOrder: 1.5 }, { title: "" }, { surprise: true }]) {
      expect((await json("PATCH", packUrl, body)).statusCode).toBe(400)
    }
    expect((await upload("/admin/stickers/packs", { packKey: "dogs", title: "Dogs", published: "yes" })).statusCode).toBe(400)
    expect((await create("1.5")).statusCode).toBe(400)
    expect((await get("/admin/stickers/history?limit=0")).statusCode).toBe(400)
    expect((await get("/admin/stickers/history")).json().items).toHaveLength(count)
  })

  it("replaces wallpaper variants at fresh URLs, keeps metadata and can undo", async () => {
    const created = await upload("/admin/wallpapers", { title: "Red", published: "true", sortOrder: "4" })
    expect(created.statusCode).toBe(200)
    const old = created.json()
    const replacement = await upload(`/admin/wallpapers/${old.id}/image`, {}, "PUT")
    expect(replacement.statusCode).toBe(200)
    expect(replacement.json().fullImageUrl).not.toBe(old.fullImageUrl)
    const row = (await get("/admin/wallpapers")).json().items[0]
    expect(row).toMatchObject({ title: "Red", published: true, sortOrder: 4 })
    expect(assets.size).toBe(8)
    const history = (await get("/admin/wallpapers/history")).json().items
    const change = history.find((entry: { action: string }) => entry.action === "replaceWallpaperImage")
    expect((await json("POST", `/admin/wallpapers/history/${change.id}/restore`, { confirm: true })).statusCode).toBe(200)
    expect((await get("/admin/wallpapers")).json().items[0].fullImageUrl).toBe(old.fullImageUrl)
  })

  it("rejects a stale restore and keeps published identities locked when undoing publish", async () => {
    await create(); await sticker()
    await json("PATCH", packUrl, { published: true })
    const change = (await get("/admin/stickers/history")).json().items.find((entry: { action: string }) => entry.action === "updateStickerPackVersionMetadata")
    await json("PATCH", packUrl, { title: "New title" })
    const stale = await json("POST", `/admin/stickers/history/${change.id}/restore`, { confirm: true })
    expect(stale.statusCode).toBe(409)
    expect(stale.json().code).toBe("CATALOG_STATE_CHANGED")
    const latest = (await get("/admin/stickers/history")).json().items.find((entry: { current: { pack?: { title: string } } }) => entry.current?.pack?.title === "New title")
    expect((await json("POST", `/admin/stickers/history/${latest.id}/restore`, { confirm: true })).statusCode).toBe(200)
    expect((await json("POST", `/admin/stickers/history/${change.id}/restore`, { confirm: true })).statusCode).toBe(200)
    expect((await get(packUrl)).json().published).toBe(false)
    expect((await sticker()).json().code).toBe("PACK_VERSION_IMMUTABLE")
  })

  it("rolls back the catalog write when history cannot be saved", async () => {
    await create()
    failAudit = true
    const response = await json("PATCH", packUrl, { title: "Must roll back" })
    expect(response.statusCode).toBe(500)
    expect((await get(packUrl)).json().title).toBe("Cats")
  })

  it("keeps copied draft art and alpha intact when the source is replaced", async () => {
    await create(); await sticker()
    const before = (await get(packUrl)).json().stickers[0]
    expect((await json("POST", `${packUrl}/copy`, { newVersion: 2 })).statusCode).toBe(200)
    const count = assets.size
    expect((await sticker()).statusCode).toBe(200)
    expect((await get(packUrl)).json().stickers[0].fullUrl).not.toBe(before.fullUrl)
    expect((await get("/admin/stickers/packs/cats/2")).json().stickers[0].fullUrl).toBe(before.fullUrl)
    expect(assets.size).toBe(count + 2)
    const art = assets.get(new URL(before.fullUrl).pathname.slice(1))!
    expect((await sharp(art).metadata()).hasAlpha).toBe(true)
    expect((await sharp(art).stats()).channels[3].mean).toBeLessThan(255)
  })

  it("keeps legacy versions locked and new drafts editable across repeated migrations", async () => {
    await create()
    await db.query("ALTER TABLE sticker_packs DROP COLUMN content_locked")
    await runMigrations(db)
    expect((await get(packUrl)).json().contentLocked).toBe(true)
    expect((await sticker()).json().code).toBe("PACK_VERSION_IMMUTABLE")
    expect((await create("2")).statusCode).toBe(200)
    await runMigrations(db)
    expect((await get("/admin/stickers/packs/cats/2")).json().contentLocked).toBe(false)
  })

  it("restores a confirmed wallpaper deletion with its original URLs", async () => {
    const original = (await upload("/admin/wallpapers", { title: "Red" })).json()
    const url = `/admin/wallpapers/${original.id}`
    expect((await json("DELETE", url)).statusCode).toBe(409)
    expect((await json("DELETE", url, { confirm: true })).statusCode).toBe(200)
    expect((await get("/admin/wallpapers")).json().items).toHaveLength(0)
    const deletion = (await get("/admin/wallpapers/history")).json().items.find((entry: { action: string }) => entry.action === "deleteWallpaper")
    expect((await json("POST", `/admin/wallpapers/history/${deletion.id}/restore`, { confirm: true })).statusCode).toBe(200)
    expect((await get("/admin/wallpapers")).json().items[0].fullImageUrl).toBe(original.fullImageUrl)
  })

  it("rejects undecodable files and invalid path versions without writes", async () => {
    expect((await upload("/admin/wallpapers", { title: "Broken" }, "POST", Buffer.from("bad png"))).statusCode).toBe(400)
    expect((await upload("/admin/stickers/packs", { packKey: "bad", title: "Broken" }, "POST", Buffer.from("bad png"))).statusCode).toBe(400)
    for (const version of ["1.5", "-1", "2147483648"]) {
      expect((await json("PATCH", `/admin/stickers/packs/cats/${version}`, { title: "Bad" })).statusCode).toBe(400)
    }
    expect((await get("/admin/stickers/history")).json().items).toHaveLength(0)
    expect((await get("/admin/wallpapers/history")).json().items).toHaveLength(0)
  })

  it("rejects too-small wallpaper art and missing targets without catalog writes", async () => {
    const tiny = await sharp({ create: { width: 10, height: 10, channels: 3, background: "red" } }).png().toBuffer()
    expect((await upload("/admin/wallpapers", { title: "Tiny" }, "POST", tiny)).statusCode).toBe(400)
    expect((await upload("/admin/wallpapers/missing/image", {}, "PUT")).statusCode).toBe(404)
    expect((await json("DELETE", `${packUrl}/stickers/missing`)).statusCode).toBe(404)
    expect((await get("/admin/wallpapers/history")).json().items).toHaveLength(0)
  })
})
