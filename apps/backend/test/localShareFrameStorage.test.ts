import { mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import Fastify from "fastify"
import { afterEach, describe, expect, it } from "vitest"
import { loadConfig } from "../src/infra/config/config.js"
import { registerShareFrameRoutes } from "../src/modules/shareFrames/routes.js"
import type { ShareFrameCatalogService } from "../src/modules/shareFrames/service.js"
import { absolutizeLocalShareFrameUrls, createShareFrameStorage, LocalShareFrameStorage, SupabaseShareFrameStorage } from "../src/modules/shareFrames/storage.js"

describe("LocalShareFrameStorage", () => {
  const roots: string[] = []
  const root = () => {
    const dir = mkdtempSync(join(tmpdir(), "mulberry-share-frames-"))
    roots.push(dir)
    return dir
  }
  afterEach(() => roots.splice(0).forEach((dir) => rmSync(dir, { recursive: true, force: true })))

  it("stores, reads back and serves assets inside its root only", async () => {
    const storage = new LocalShareFrameStorage(root())
    await storage.upload("share-frames/plain/1/a/original.png", Buffer.from("png"))
    await expect(storage.download("share-frames/plain/1/a/original.png")).resolves.toEqual(Buffer.from("png"))
    await expect(storage.read("share-frames/plain/1/a/full.webp")).rejects.toThrow("Share frame asset not found")
    await expect(storage.upload("../escape.webp", Buffer.from("x"))).rejects.toThrow("Invalid share frame asset path")
    expect(storage.publicUrl("share-frames/plain/1/a/full.webp")).toBe("/dev/share-frame-assets/share-frames/plain/1/a/full.webp")
  })

  it("returns absolute URLs on the host the client called and serves the file", async () => {
    const storage = new LocalShareFrameStorage(root())
    await storage.upload("share-frames/plain/1/a/full.webp", Buffer.from("webp"))
    const catalog = {
      listPublished: async () => ({ items: [{ frameUrl: storage.publicUrl("share-frames/plain/1/a/full.webp") }] }),
    } as unknown as ShareFrameCatalogService
    const app = Fastify()
    registerShareFrameRoutes(app, catalog, storage)
    try {
      const list = await app.inject({ url: "/share-frames", headers: { host: "192.168.0.10:8080", authorization: "Bearer token" } })
      const url: string = list.json().items[0].frameUrl
      expect(url).toBe("http://192.168.0.10:8080/dev/share-frame-assets/share-frames/plain/1/a/full.webp")
      const asset = await app.inject({ url: url.replace("http://192.168.0.10:8080", "") })
      expect(asset.statusCode).toBe(200)
      expect(asset.headers["content-type"]).toBe("image/webp")
      expect(asset.headers["cache-control"]).toContain("immutable")
      expect(asset.body).toBe("webp")
    } finally {
      await app.close()
    }
  })

  it("prefers local disk, leaves other URLs alone and is refused in production", () => {
    const dir = root()
    expect(createShareFrameStorage({ shareFrameLocalStorageDir: dir, supabaseUrl: "https://x.supabase.co", supabaseServiceRoleKey: "k", supabaseWallpaperBucket: "b" }))
      .toBeInstanceOf(LocalShareFrameStorage)
    expect(createShareFrameStorage({ supabaseUrl: "https://x.supabase.co", supabaseServiceRoleKey: "k", supabaseWallpaperBucket: "b" }))
      .toBeInstanceOf(SupabaseShareFrameStorage)
    expect(createShareFrameStorage({})).toBeNull()
    expect(absolutizeLocalShareFrameUrls({ url: "https://cdn/x.webp" }, "http://h")).toEqual({ url: "https://cdn/x.webp" })
    expect(loadConfig({ DATABASE_URL: "postgres://x", GOOGLE_SERVER_CLIENT_ID: "test-client", SHARE_FRAME_LOCAL_STORAGE_DIR: dir }).shareFrameLocalStorageDir).toBe(dir)
    // Blank the other local directories a developer's .env may set, so this checks the share frame one.
    const otherLocalDirs = { STICKER_LOCAL_STORAGE_DIR: "", WALLPAPER_LOCAL_STORAGE_DIR: "", CANVAS_PHOTO_LOCAL_STORAGE_DIR: "" }
    expect(() => loadConfig({ NODE_ENV: "production", DATABASE_URL: "postgres://x", GOOGLE_SERVER_CLIENT_ID: "test-client", ...otherLocalDirs, SHARE_FRAME_LOCAL_STORAGE_DIR: "/tmp/x" }))
      .toThrow("SHARE_FRAME_LOCAL_STORAGE_DIR is for local development only")
  })
})
