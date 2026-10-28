import { mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import Fastify from "fastify"
import { afterEach, describe, expect, it } from "vitest"
import { loadConfig } from "../src/infra/config/config.js"
import { absolutizeLocalStickerUrls, LocalStickerStorage } from "../src/modules/stickers/localStorage.js"
import { registerStickerRoutes } from "../src/modules/stickers/routes.js"
import type { StickerCatalogService } from "../src/modules/stickers/service.js"

describe("LocalStickerStorage", () => {
  const roots: string[] = []
  const root = () => {
    const dir = mkdtempSync(join(tmpdir(), "mulberry-stickers-"))
    roots.push(dir)
    return dir
  }
  afterEach(() => roots.splice(0).forEach((dir) => rmSync(dir, { recursive: true, force: true })))

  it("serves uploaded assets only through unexpired signed URLs", async () => {
    const storage = new LocalStickerStorage(root())
    await storage.upload("stickers/spring/1/a-full.png", Buffer.from("png"))
    const url = new URL(await storage.signedUrl("stickers/spring/1/a-full.png", 60), "http://local")
    const expires = Number(url.searchParams.get("expires"))
    const sig = url.searchParams.get("sig") ?? ""

    await expect(storage.read("stickers/spring/1/a-full.png", expires, sig)).resolves.toEqual(Buffer.from("png"))
    await expect(storage.read("stickers/spring/1/other.png", expires, sig)).rejects.toThrow("signature")
    await expect(storage.read("stickers/spring/1/a-full.png", Date.now() / 1000 - 1, sig)).rejects.toThrow("expired")
    await expect(storage.upload("../escape.png", Buffer.from("x"))).rejects.toThrow("Invalid sticker asset path")
  })

  it("returns absolute URLs on the host the client called and serves the file", async () => {
    const storage = new LocalStickerStorage(root())
    await storage.upload("stickers/spring/1/a-thumb.png", Buffer.from("thumb"))
    const catalog = {
      listPublishedStickerPacks: async () => ({
        items: [{ coverThumbnailUrl: await storage.signedUrl("stickers/spring/1/a-thumb.png", 60) }],
      }),
    } as unknown as StickerCatalogService
    const app = Fastify()
    registerStickerRoutes(app, catalog, storage)
    try {
      const list = await app.inject({ url: "/stickers/packs", headers: { host: "192.168.0.10:8080", authorization: "Bearer t" } })
      const coverUrl: string = list.json().items[0].coverThumbnailUrl
      expect(coverUrl.startsWith("http://192.168.0.10:8080/dev/sticker-assets/stickers/spring/1/a-thumb.png?")).toBe(true)
      const asset = await app.inject({ url: coverUrl.replace("http://192.168.0.10:8080", "") })
      expect(asset.statusCode).toBe(200)
      expect(asset.headers["content-type"]).toBe("image/png")
      expect(asset.body).toBe("thumb")
    } finally {
      await app.close()
    }
  })

  it("leaves other URLs alone and is refused in production", () => {
    expect(absolutizeLocalStickerUrls({ url: "https://cdn/x.png" }, "http://h")).toEqual({ url: "https://cdn/x.png" })
    expect(() => loadConfig({ NODE_ENV: "production", DATABASE_URL: "postgres://x", STICKER_LOCAL_STORAGE_DIR: "/tmp/x" }))
      .toThrow("local development only")
  })
})
