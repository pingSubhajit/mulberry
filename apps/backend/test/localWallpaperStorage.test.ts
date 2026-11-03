import { mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import Fastify from "fastify"
import { afterEach, describe, expect, it } from "vitest"
import { loadConfig } from "../src/infra/config/config.js"
import { absolutizeLocalWallpaperUrls, LocalWallpaperStorage } from "../src/modules/wallpapers/localStorage.js"
import { registerWallpaperRoutes } from "../src/modules/wallpapers/routes.js"
import type { WallpaperCatalogService } from "../src/modules/wallpapers/service.js"

describe("LocalWallpaperStorage", () => {
  const roots: string[] = []
  const root = () => {
    const dir = mkdtempSync(join(tmpdir(), "mulberry-wallpapers-"))
    roots.push(dir)
    return dir
  }
  afterEach(() => roots.splice(0).forEach((dir) => rmSync(dir, { recursive: true, force: true })))

  it("stores, reads and removes assets inside its root only", async () => {
    const storage = new LocalWallpaperStorage(root())
    await storage.upload("wallpapers/a/full.webp", Buffer.from("webp"))
    await expect(storage.read("wallpapers/a/full.webp")).resolves.toEqual({ data: Buffer.from("webp"), contentType: "image/webp" })
    await storage.remove(["wallpapers/a/full.webp"])
    await expect(storage.read("wallpapers/a/full.webp")).rejects.toThrow("not found")
    await expect(storage.upload("../escape.webp", Buffer.from("x"))).rejects.toThrow("Invalid wallpaper asset path")
  })

  it("returns absolute URLs on the host the client called and serves the file", async () => {
    const storage = new LocalWallpaperStorage(root())
    await storage.upload("wallpapers/a/thumbnail.webp", Buffer.from("thumb"))
    const catalog = {
      listPublishedWallpapers: async () => ({
        items: [{ thumbnailUrl: storage.publicUrl("wallpapers/a/thumbnail.webp") }],
        nextCursor: null,
      }),
    } as unknown as WallpaperCatalogService
    const app = Fastify()
    registerWallpaperRoutes(app, catalog, storage)
    try {
      const list = await app.inject({ url: "/wallpapers?limit=24", headers: { host: "192.168.0.10:8080" } })
      const thumbnailUrl: string = list.json().items[0].thumbnailUrl
      expect(thumbnailUrl).toBe("http://192.168.0.10:8080/dev/wallpaper-assets/wallpapers/a/thumbnail.webp")
      const asset = await app.inject({ url: thumbnailUrl.replace("http://192.168.0.10:8080", "") })
      expect(asset.statusCode).toBe(200)
      expect(asset.headers["content-type"]).toBe("image/webp")
      expect(asset.body).toBe("thumb")
    } finally {
      await app.close()
    }
  })

  it("leaves other URLs alone and is refused in production", () => {
    expect(absolutizeLocalWallpaperUrls({ url: "https://cdn/x.webp" }, "http://h")).toEqual({ url: "https://cdn/x.webp" })
    expect(() => loadConfig({ NODE_ENV: "production", DATABASE_URL: "postgres://x", WALLPAPER_LOCAL_STORAGE_DIR: "/tmp/x" }))
      .toThrow("local development only")
  })
})
