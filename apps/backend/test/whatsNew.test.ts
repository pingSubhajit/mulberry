import Fastify from "fastify"
import { describe, expect, it, beforeEach, afterEach } from "vitest"
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs"
import { join } from "node:path"
import { tmpdir } from "node:os"
import { registerWhatsNewRoutes } from "../src/modules/whatsNew/routes.js"

describe("whats-new routes", () => {
  let baseDir: string
  let app: ReturnType<typeof Fastify>

  beforeEach(async () => {
    baseDir = mkdtempSync(join(tmpdir(), "mulberry-whats-new-"))
    mkdirSync(join(baseDir, "assets"), { recursive: true })
    app = Fastify({ logger: false })
    registerWhatsNewRoutes(app, { baseDir })
    await app.ready()
  })

  afterEach(async () => {
    await app.close()
    rmSync(baseDir, { recursive: true, force: true })
  })

  it("serves the latest entry by released_at and ignores non-version markdown", async () => {
    writeFileSync(join(baseDir, "README.md"), "# docs", "utf8")
    writeFileSync(
      join(baseDir, "1.0.0.md"),
      ["---", "version: 1.0.0", "released_at: 2026-01-01", "---", "", "v1"].join("\n"),
      "utf8",
    )
    writeFileSync(
      join(baseDir, "1.1.0.md"),
      ["---", "version: 1.1.0", "released_at: 2026-05-06", "---", "", "v1.1"].join("\n"),
      "utf8",
    )

    const response = await app.inject({ method: "GET", url: "/whats-new/latest.md" })
    expect(response.statusCode).toBe(200)
    expect(response.headers["content-type"]).toContain("text/markdown")
    expect(response.body).toContain("version: 1.1.0")
  })

  it("lists entries in pages from newest to oldest", async () => {
    writeFileSync(join(baseDir, "README.md"), "# docs", "utf8")
    writeFileSync(
      join(baseDir, "1.0.0.md"),
      ["---", "version: 1.0.0", "released_at: 2026-01-01", "---", "", "v1"].join("\n"),
      "utf8",
    )
    writeFileSync(
      join(baseDir, "1.1.0.md"),
      ["---", "version: 1.1.0", "released_at: 2026-05-06", "---", "", "v1.1"].join("\n"),
      "utf8",
    )
    writeFileSync(
      join(baseDir, "1.2.0.md"),
      ["---", "version: 1.2.0", "released_at: 2026-05-07", "---", "", "v1.2"].join("\n"),
      "utf8",
    )

    const first = await app.inject({ method: "GET", url: "/whats-new?limit=2" })
    expect(first.statusCode).toBe(200)
    const firstBody = first.json()
    expect(firstBody.items.map((item: { version: string }) => item.version)).toEqual(["1.2.0", "1.1.0"])
    expect(firstBody.items[0].rawMarkdown).toContain("v1.2")
    expect(firstBody.nextCursor).toBe("2")

    const second = await app.inject({ method: "GET", url: "/whats-new?limit=2&cursor=2" })
    expect(second.statusCode).toBe(200)
    const secondBody = second.json()
    expect(secondBody.items.map((item: { version: string }) => item.version)).toEqual(["1.0.0"])
    expect(secondBody.nextCursor).toBeNull()
  })

  it("returns 304 when If-None-Match matches", async () => {
    writeFileSync(
      join(baseDir, "1.0.0.md"),
      ["---", "version: 1.0.0", "released_at: 2026-01-01", "---", "", "hello"].join("\n"),
      "utf8",
    )
    const first = await app.inject({ method: "GET", url: "/whats-new/1.0.0.md" })
    expect(first.statusCode).toBe(200)
    const etag = first.headers.etag
    expect(etag).toBeTruthy()

    const second = await app.inject({
      method: "GET",
      url: "/whats-new/1.0.0.md",
      headers: { "if-none-match": String(etag) },
    })
    expect(second.statusCode).toBe(304)
  })

  it("serves versioned assets with immutable caching", async () => {
    mkdirSync(join(baseDir, "assets", "1.0.0"), { recursive: true })
    const assetPath = join(baseDir, "assets", "1.0.0", "hero.webp")
    writeFileSync(assetPath, Buffer.from([0x52, 0x49, 0x46, 0x46]))

    const response = await app.inject({ method: "GET", url: "/whats-new/assets/1.0.0/hero.webp" })
    expect(response.statusCode).toBe(200)
    expect(response.headers["content-type"]).toBe("image/webp")
    expect(response.headers["cache-control"]).toContain("immutable")
  })
})
