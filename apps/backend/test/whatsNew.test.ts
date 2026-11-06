import Fastify, { type FastifyReply, type FastifyRequest } from "fastify"
import { describe, expect, it, beforeEach, afterEach } from "vitest"
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs"
import { join } from "node:path"
import { tmpdir } from "node:os"
import { registerWhatsNewRoutes, type WhatsNewPlatform } from "../src/modules/whatsNew/routes.js"

type ListBody = { items: { version: string; rawMarkdown: string }[]; nextCursor: string | null }

const listBody = (body: string): ListBody => JSON.parse(body) as ListBody

describe("whats-new routes", () => {
  let baseDir: string
  let app: ReturnType<typeof Fastify>

  function writeEntry(platform: WhatsNewPlatform, version: string, releasedAt: string, body: string): void {
    mkdirSync(join(baseDir, platform), { recursive: true })
    writeFileSync(
      join(baseDir, platform, `${version}.md`),
      ["---", `version: ${version}`, `released_at: ${releasedAt}`, "---", "", body].join("\n"),
      "utf8",
    )
  }

  function writeAsset(platform: WhatsNewPlatform, path: string): void {
    const dir = join(baseDir, platform, "assets", ...path.split("/").slice(0, -1))
    mkdirSync(dir, { recursive: true })
    writeFileSync(join(baseDir, platform, "assets", path), Buffer.from([0x52, 0x49, 0x46, 0x46]))
  }

  beforeEach(async () => {
    baseDir = mkdtempSync(join(tmpdir(), "mulberry-whats-new-"))
    app = Fastify({ logger: false })
    // The app's own hooks are asynchronous; a handler that sends without returning its reply
    // would race them and send twice.
    app.addHook("preSerialization", async (_request: FastifyRequest, _reply: FastifyReply, payload: unknown) => {
      await new Promise((resolve) => setTimeout(resolve, 5))
      return payload
    })
    registerWhatsNewRoutes(app, { baseDir })
    await app.ready()
  })

  afterEach(async () => {
    await app.close()
    rmSync(baseDir, { recursive: true, force: true })
  })

  it("keeps each platform's notes apart, even for the same version", async () => {
    writeEntry("android", "1.1.0", "2026-05-01", "android note")
    writeEntry("ios", "1.1.0", "2026-11-06", "ios note")

    const ios = await app.inject({ method: "GET", url: "/whats-new/ios/1.1.0.md" })
    expect(ios.statusCode).toBe(200)
    expect(ios.headers["content-type"]).toContain("text/markdown")
    expect(ios.body).toContain("ios note")

    const android = await app.inject({ method: "GET", url: "/whats-new/android/1.1.0.md" })
    expect(android.body).toContain("android note")

    const macos = await app.inject({ method: "GET", url: "/whats-new/macos/1.1.0.md" })
    expect(macos.statusCode).toBe(404)
  })

  it("serves the latest entry of a platform by released_at and ignores non-version markdown", async () => {
    writeFileSync(join(baseDir, "README.md"), "# docs", "utf8")
    mkdirSync(join(baseDir, "ios"), { recursive: true })
    writeFileSync(join(baseDir, "ios", "README.md"), "# docs", "utf8")
    writeEntry("ios", "1.0.0", "2026-01-01", "v1")
    writeEntry("ios", "1.1.0", "2026-05-06", "v1.1")
    writeEntry("android", "1.2.0", "2026-06-01", "android")

    const response = await app.inject({ method: "GET", url: "/whats-new/ios/latest.md" })
    expect(response.statusCode).toBe(200)
    expect(response.body).toContain("version: 1.1.0")

    const empty = await app.inject({ method: "GET", url: "/whats-new/macos/latest.md" })
    expect(empty.statusCode).toBe(404)
  })

  it("lists a platform's entries in pages from newest to oldest", async () => {
    writeEntry("ios", "1.0.0", "2026-01-01", "v1")
    writeEntry("ios", "1.1.0", "2026-05-06", "v1.1")
    writeEntry("ios", "1.2.0", "2026-05-07", "v1.2")
    writeEntry("android", "9.9.9", "2026-12-01", "android")

    const first = await app.inject({ method: "GET", url: "/whats-new/ios?limit=2" })
    expect(first.statusCode).toBe(200)
    const firstBody = listBody(first.body)
    expect(firstBody.items.map((item) => item.version)).toEqual(["1.2.0", "1.1.0"])
    expect(firstBody.items[0]?.rawMarkdown).toContain("v1.2")
    expect(firstBody.nextCursor).toBe("2")

    const second = await app.inject({ method: "GET", url: "/whats-new/ios?limit=2&cursor=2" })
    const secondBody = listBody(second.body)
    expect(secondBody.items.map((item) => item.version)).toEqual(["1.0.0"])
    expect(secondBody.nextCursor).toBeNull()

    const macos = await app.inject({ method: "GET", url: "/whats-new/macos" })
    expect(listBody(macos.body)).toEqual({ items: [], nextCursor: null })
  })

  it("keeps unreleased notes out of history and latest, but serves them to their own version", async () => {
    writeEntry("ios", "1.0.0", "2026-01-01", "public")
    mkdirSync(join(baseDir, "ios"), { recursive: true })
    writeFileSync(join(baseDir, "ios", "1.1.0.md"), ["---", "version: 1.1.0", "---", "", "not public yet"].join("\n"), "utf8")
    writeEntry("ios", "1.2.0", "2999-01-01", "later")

    const list = await app.inject({ method: "GET", url: "/whats-new/ios" })
    expect(listBody(list.body).items.map((item) => item.version)).toEqual(["1.0.0"])
    const latest = await app.inject({ method: "GET", url: "/whats-new/ios/latest.md" })
    expect(latest.body).toContain("public")

    const own = await app.inject({ method: "GET", url: "/whats-new/ios/1.1.0.md" })
    expect(own.statusCode).toBe(200)
    expect(own.body).toContain("not public yet")
    expect((await app.inject({ method: "GET", url: "/whats-new/ios/1.2.0.md" })).statusCode).toBe(200)
  })

  it("rejects platforms it doesn't know", async () => {
    for (const url of ["/whats-new/windows", "/whats-new/windows/latest.md", "/whats-new/windows/1.0.0.md", "/whats-new/windows/assets/a.png"]) {
      const response = await app.inject({ method: "GET", url })
      expect(response.statusCode).toBe(404)
    }
  })

  it("serves Android's notes on the routes without a platform, for builds already installed", async () => {
    writeEntry("android", "1.1.7", "2026-05-08", "android 1.1.7")
    writeEntry("ios", "1.1.8", "2026-11-06", "ios 1.1.8")
    writeAsset("android", "1.1.7/hero.jpg")

    const version = await app.inject({ method: "GET", url: "/whats-new/1.1.7.md" })
    expect(version.statusCode).toBe(200)
    expect(version.body).toContain("android 1.1.7")
    expect((await app.inject({ method: "GET", url: "/whats-new/1.1.8.md" })).statusCode).toBe(404)

    const latest = await app.inject({ method: "GET", url: "/whats-new/latest.md" })
    expect(latest.body).toContain("android 1.1.7")

    const list = await app.inject({ method: "GET", url: "/whats-new?limit=5" })
    expect(listBody(list.body).items.map((item) => item.version)).toEqual(["1.1.7"])

    const asset = await app.inject({ method: "GET", url: "/whats-new/assets/1.1.7/hero.jpg" })
    expect(asset.statusCode).toBe(200)
    expect(asset.headers["content-type"]).toBe("image/jpeg")
  })

  it("returns 304 when If-None-Match matches", async () => {
    writeEntry("ios", "1.0.0", "2026-01-01", "hello")
    const first = await app.inject({ method: "GET", url: "/whats-new/ios/1.0.0.md" })
    expect(first.statusCode).toBe(200)
    const etag = first.headers.etag
    expect(etag).toBeTruthy()

    const second = await app.inject({
      method: "GET",
      url: "/whats-new/ios/1.0.0.md",
      headers: { "if-none-match": String(etag) },
    })
    expect(second.statusCode).toBe(304)
  })

  it("serves a platform's versioned assets with immutable caching, and only its own", async () => {
    writeAsset("ios", "1.0.0/hero.webp")

    const response = await app.inject({ method: "GET", url: "/whats-new/ios/assets/1.0.0/hero.webp" })
    expect(response.statusCode).toBe(200)
    expect(response.headers["content-type"]).toBe("image/webp")
    expect(response.headers["cache-control"]).toContain("immutable")

    const otherPlatform = await app.inject({ method: "GET", url: "/whats-new/android/assets/1.0.0/hero.webp" })
    expect(otherPlatform.statusCode).toBe(404)
    const escape = await app.inject({ method: "GET", url: "/whats-new/android/assets/..%2F..%2Fios%2Fassets%2F1.0.0%2Fhero.webp" })
    expect(escape.statusCode).toBe(400)
  })

  it("serves the notes checked into the repository", async () => {
    const shipped = Fastify({ logger: false })
    registerWhatsNewRoutes(shipped)
    await shipped.ready()
    const latest = await shipped.inject({ method: "GET", url: "/whats-new/android/latest.md" })
    expect(latest.statusCode).toBe(200)
    const hero = /^hero_image: (.+)$/m.exec(latest.body)?.[1]
    expect(hero).toMatch(/^\/whats-new\/android\/assets\//)
    const image = await shipped.inject({ method: "GET", url: hero ?? "" })
    expect(image.statusCode).toBe(200)
    await shipped.close()
  })
})
