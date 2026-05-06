import { existsSync, readdirSync, readFileSync, statSync } from "node:fs"
import { resolve, join, dirname, extname } from "node:path"
import { fileURLToPath } from "node:url"
import type { FastifyInstance } from "fastify"

type WhatsNewFrontmatter = {
  version?: string
  released_at?: string
}

export function registerWhatsNewRoutes(
  app: FastifyInstance,
  options: { baseDir?: string } = {},
): void {
  const backendRoot = resolveBackendRoot()
  const baseDir = options.baseDir ?? join(backendRoot, "whats-new")
  const assetsDir = join(baseDir, "assets")

  app.get("/whats-new/latest.md", async (request, reply) => {
    const latest = findLatestWhatsNewVersion(baseDir)
    if (!latest) {
      reply.code(404).send("No whats-new entries are configured.")
      return
    }
    return serveMarkdownFile(reply, join(baseDir, `${latest}.md`), request.headers["if-none-match"])
  })

  app.get("/whats-new/:version.md", async (request, reply) => {
    const rawVersion = (request.params as { version?: string }).version ?? ""
    const version = sanitizeVersionKey(rawVersion)
    if (!version) {
      reply.code(400).send("Invalid version key.")
      return
    }
    const path = join(baseDir, `${version}.md`)
    if (!existsSync(path)) {
      reply.code(404).send("Not found.")
      return
    }
    return serveMarkdownFile(reply, path, request.headers["if-none-match"])
  })

  app.get("/whats-new/assets/*", async (request, reply) => {
    const assetPath = (request.params as Record<string, string | undefined>)["*"] ?? ""
    const normalized = sanitizeAssetPath(assetPath)
    if (!normalized) {
      reply.code(400).send("Invalid asset path.")
      return
    }
    const candidate = resolve(assetsDir, normalized)
    if (!isWithinDir(candidate, assetsDir) || !existsSync(candidate)) {
      reply.code(404).send("Not found.")
      return
    }
    const contentType = inferContentType(candidate)
    if (!contentType) {
      reply.code(415).send("Unsupported asset type.")
      return
    }
    return serveBinaryFile(
      reply,
      candidate,
      contentType,
      request.headers["if-none-match"],
      "public, max-age=31536000, immutable",
    )
  })
}

function resolveBackendRoot(): string {
  let dir = dirname(fileURLToPath(import.meta.url))
  for (let i = 0; i < 12; i += 1) {
    if (existsSync(join(dir, "package.json"))) return dir
    dir = dirname(dir)
  }
  throw new Error("Unable to resolve backend package root directory.")
}

function sanitizeVersionKey(raw: string): string | null {
  const trimmed = raw.trim()
  if (!trimmed) return null
  if (!/^[0-9][0-9A-Za-z.+-]*$/.test(trimmed)) return null
  if (trimmed.includes("..") || trimmed.includes("/") || trimmed.includes("\\")) return null
  return trimmed
}

function sanitizeAssetPath(raw: string): string | null {
  const trimmed = raw.trim().replace(/^\/+/, "")
  if (!trimmed) return null
  if (trimmed.includes("\0") || trimmed.includes("..") || trimmed.includes("\\")) return null
  return trimmed
}

function isWithinDir(candidateFilePath: string, baseDir: string): boolean {
  const base = resolve(baseDir) + "/"
  const file = resolve(candidateFilePath)
  return file.startsWith(base)
}

function inferContentType(path: string): string | null {
  const ext = extname(path).toLowerCase()
  if (ext === ".webp") return "image/webp"
  if (ext === ".png") return "image/png"
  if (ext === ".jpg" || ext === ".jpeg") return "image/jpeg"
  if (ext === ".gif") return "image/gif"
  if (ext === ".svg") return "image/svg+xml"
  return null
}

function computeWeakEtag(path: string): string {
  const stat = statSync(path)
  return `W/\"${stat.size}-${Math.floor(stat.mtimeMs)}\"`
}

function maybeReplyNotModified(reply: any, etag: string, ifNoneMatch: string | string[] | undefined): boolean {
  if (typeof ifNoneMatch === "string" && ifNoneMatch === etag) {
    reply.code(304).header("ETag", etag).send()
    return true
  }
  return false
}

function serveMarkdownFile(
  reply: any,
  path: string,
  ifNoneMatch: string | string[] | undefined,
): void {
  const etag = computeWeakEtag(path)
  if (maybeReplyNotModified(reply, etag, ifNoneMatch)) return
  const body = readFileSync(path, "utf8")
  reply
    .header("Content-Type", "text/markdown; charset=utf-8")
    .header("Cache-Control", "public, max-age=3600")
    .header("ETag", etag)
    .send(body)
}

function serveBinaryFile(
  reply: any,
  path: string,
  contentType: string,
  ifNoneMatch: string | string[] | undefined,
  cacheControl: string,
): void {
  const etag = computeWeakEtag(path)
  if (maybeReplyNotModified(reply, etag, ifNoneMatch)) return
  const body = readFileSync(path)
  reply
    .header("Content-Type", contentType)
    .header("Cache-Control", cacheControl)
    .header("ETag", etag)
    .send(body)
}

function findLatestWhatsNewVersion(baseDir: string): string | null {
  if (!existsSync(baseDir)) return null
  const entries = readdirSync(baseDir, { withFileTypes: true })
    .filter((dirent) => dirent.isFile() && dirent.name.toLowerCase().endsWith(".md"))
    .map((dirent) => dirent.name)
    .map((name) => ({ name, version: name.slice(0, -3) }))
    .filter(({ version }) => sanitizeVersionKey(version) !== null)
    .map(({ name, version }) => ({
      name,
      version,
      frontmatter: readFrontmatter(join(baseDir, name)),
    }))

  if (entries.length === 0) return null

  entries.sort((a, b) => {
    const dateA = parseDateMs(a.frontmatter.released_at)
    const dateB = parseDateMs(b.frontmatter.released_at)
    if (dateA !== dateB) return dateB - dateA
    return compareSemverish(b.version, a.version)
  })

  return entries[0]?.version ?? null
}

function readFrontmatter(path: string): WhatsNewFrontmatter {
  const content = readFileSync(path, "utf8")
  const parsed = parseFrontmatter(content)
  return parsed
}

function parseFrontmatter(raw: string): WhatsNewFrontmatter {
  const lines = raw.split(/\r?\n/)
  if (lines[0]?.trim() !== "---") return {}
  const result: WhatsNewFrontmatter = {}
  for (let i = 1; i < lines.length; i += 1) {
    const line = lines[i] ?? ""
    if (line.trim() === "---") break
    const idx = line.indexOf(":")
    if (idx <= 0) continue
    const key = line.slice(0, idx).trim()
    const value = line.slice(idx + 1).trim().replace(/^"(.*)"$/, "$1")
    if (key === "version") result.version = value
    if (key === "released_at") result.released_at = value
  }
  return result
}

function parseDateMs(value?: string): number {
  if (!value) return 0
  const ms = Date.parse(value)
  return Number.isFinite(ms) ? ms : 0
}

function compareSemverish(a: string, b: string): number {
  const pa = parseSemverish(a)
  const pb = parseSemverish(b)
  for (let i = 0; i < 3; i += 1) {
    const da = pa.parts[i] - pb.parts[i]
    if (da !== 0) return da
  }
  if (pa.prerelease !== pb.prerelease) return pa.prerelease ? -1 : 1
  return a.localeCompare(b)
}

function parseSemverish(version: string): { parts: [number, number, number]; prerelease: boolean } {
  const main = version.split("-", 2)[0] ?? version
  const parts = main.split(".").map((p) => Number.parseInt(p, 10))
  const major = Number.isFinite(parts[0]) ? parts[0] : 0
  const minor = Number.isFinite(parts[1]) ? parts[1] : 0
  const patch = Number.isFinite(parts[2]) ? parts[2] : 0
  return { parts: [major, minor, patch], prerelease: version.includes("-") }
}
