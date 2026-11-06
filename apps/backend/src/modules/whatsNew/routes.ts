import { existsSync, readdirSync, readFileSync, statSync } from "node:fs"
import { resolve, join, dirname, extname } from "node:path"
import { fileURLToPath } from "node:url"
import type { FastifyInstance, FastifyReply } from "fastify"

/** Each app ships on its own schedule with its own version numbers, so each has its own notes. */
export const WHATS_NEW_PLATFORMS = ["android", "ios", "macos"] as const
export type WhatsNewPlatform = (typeof WHATS_NEW_PLATFORMS)[number]

/** Android builds from before the per-platform folders call the routes without a platform. */
const LEGACY_PLATFORM: WhatsNewPlatform = "android"

type WhatsNewFrontmatter = {
  version?: string
  released_at?: string
}

type WhatsNewEntryFile = {
  version: string
  path: string
  frontmatter: WhatsNewFrontmatter
}

type ListQuery = { cursor?: string; limit?: string }
type PlatformParams = { platform: string }
type VersionParams = { version: string }
type AssetParams = { "*": string }
type IfNoneMatch = string | string[] | undefined

/**
 * Serves the "What's new" notes in `whats-new/<platform>/<version>.md`, with their images in
 * `whats-new/<platform>/assets/`. Every route is under `/whats-new/:platform`; the same routes
 * without a platform serve Android, for the builds already installed.
 */
export function registerWhatsNewRoutes(
  app: FastifyInstance,
  options: { baseDir?: string } = {},
): void {
  const baseDir = options.baseDir ?? join(resolveBackendRoot(), "whats-new")
  const platformDir = (platform: WhatsNewPlatform) => join(baseDir, platform)

  app.get<{ Params: PlatformParams; Querystring: ListQuery }>("/whats-new/:platform", async (request, reply) => {
    const platform = parsePlatform(request.params.platform)
    if (!platform) return replyUnknownPlatform(reply)
    return sendList(reply, platformDir(platform), request.query)
  })
  app.get<{ Params: PlatformParams }>("/whats-new/:platform/latest.md", async (request, reply) => {
    const platform = parsePlatform(request.params.platform)
    if (!platform) return replyUnknownPlatform(reply)
    return sendLatest(reply, platformDir(platform), request.headers["if-none-match"])
  })
  app.get<{ Params: PlatformParams & VersionParams }>("/whats-new/:platform/:version.md", async (request, reply) => {
    const platform = parsePlatform(request.params.platform)
    if (!platform) return replyUnknownPlatform(reply)
    return sendVersion(reply, platformDir(platform), request.params.version, request.headers["if-none-match"])
  })
  app.get<{ Params: PlatformParams & AssetParams }>("/whats-new/:platform/assets/*", async (request, reply) => {
    const platform = parsePlatform(request.params.platform)
    if (!platform) return replyUnknownPlatform(reply)
    return sendAsset(reply, join(platformDir(platform), "assets"), request.params["*"], request.headers["if-none-match"])
  })

  // The routes Android used before notes were split by platform.
  const legacyDir = platformDir(LEGACY_PLATFORM)
  app.get<{ Querystring: ListQuery }>("/whats-new", async (request, reply) => {
    return sendList(reply, legacyDir, request.query)
  })
  app.get("/whats-new/latest.md", async (request, reply) => {
    return sendLatest(reply, legacyDir, request.headers["if-none-match"])
  })
  app.get<{ Params: VersionParams }>("/whats-new/:version.md", async (request, reply) => {
    return sendVersion(reply, legacyDir, request.params.version, request.headers["if-none-match"])
  })
  app.get<{ Params: AssetParams }>("/whats-new/assets/*", async (request, reply) => {
    return sendAsset(reply, join(legacyDir, "assets"), request.params["*"], request.headers["if-none-match"])
  })
}

function sendList(reply: FastifyReply, dir: string, query: ListQuery): FastifyReply {
  const entries = listReleasedEntries(dir)
  const offset = parseCursor(query.cursor)
  const limit = parseLimit(query.limit)
  const page = entries.slice(offset, offset + limit)
  const nextOffset = offset + page.length

  return reply
    .header("Cache-Control", "public, max-age=3600")
    .send({
      items: page.map((entry) => ({
        version: entry.version,
        rawMarkdown: readFileSync(entry.path, "utf8"),
      })),
      nextCursor: nextOffset < entries.length ? String(nextOffset) : null,
    })
}

function sendLatest(reply: FastifyReply, dir: string, ifNoneMatch: IfNoneMatch): FastifyReply {
  const latest = listReleasedEntries(dir)[0]
  if (!latest) {
    return reply.code(404).send("No whats-new entries are configured.")
  }
  return serveMarkdownFile(reply, latest.path, ifNoneMatch)
}

function sendVersion(reply: FastifyReply, dir: string, rawVersion: string, ifNoneMatch: IfNoneMatch): FastifyReply {
  const version = sanitizeVersionKey(rawVersion)
  if (!version) {
    return reply.code(400).send("Invalid version key.")
  }
  const path = join(dir, `${version}.md`)
  if (!existsSync(path)) {
    return reply.code(404).send("Not found.")
  }
  return serveMarkdownFile(reply, path, ifNoneMatch)
}

function sendAsset(reply: FastifyReply, assetsDir: string, rawPath: string, ifNoneMatch: IfNoneMatch): FastifyReply {
  const normalized = sanitizeAssetPath(rawPath)
  if (!normalized) {
    return reply.code(400).send("Invalid asset path.")
  }
  const candidate = resolve(assetsDir, normalized)
  if (!isWithinDir(candidate, assetsDir) || !existsSync(candidate)) {
    return reply.code(404).send("Not found.")
  }
  const contentType = inferContentType(candidate)
  if (!contentType) {
    return reply.code(415).send("Unsupported asset type.")
  }
  return serveBinaryFile(reply, candidate, contentType, ifNoneMatch, "public, max-age=31536000, immutable")
}

function parsePlatform(value: string): WhatsNewPlatform | null {
  return WHATS_NEW_PLATFORMS.find((platform) => platform === value) ?? null
}

function replyUnknownPlatform(reply: FastifyReply): FastifyReply {
  return reply.code(404).send(`Unknown platform. Use one of: ${WHATS_NEW_PLATFORMS.join(", ")}.`)
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

function maybeReplyNotModified(reply: FastifyReply, etag: string, ifNoneMatch: IfNoneMatch): boolean {
  if (typeof ifNoneMatch === "string" && ifNoneMatch === etag) {
    reply.code(304).header("ETag", etag).send()
    return true
  }
  return false
}

function serveMarkdownFile(reply: FastifyReply, path: string, ifNoneMatch: IfNoneMatch): FastifyReply {
  const etag = computeWeakEtag(path)
  if (maybeReplyNotModified(reply, etag, ifNoneMatch)) return reply
  const body = readFileSync(path, "utf8")
  return reply
    .header("Content-Type", "text/markdown; charset=utf-8")
    .header("Cache-Control", "public, max-age=3600")
    .header("ETag", etag)
    .send(body)
}

function serveBinaryFile(
  reply: FastifyReply,
  path: string,
  contentType: string,
  ifNoneMatch: IfNoneMatch,
  cacheControl: string,
): FastifyReply {
  const etag = computeWeakEtag(path)
  if (maybeReplyNotModified(reply, etag, ifNoneMatch)) return reply
  const body = readFileSync(path)
  return reply
    .header("Content-Type", contentType)
    .header("Cache-Control", cacheControl)
    .header("ETag", etag)
    .send(body)
}

/**
 * The notes people can browse: those with a release day that has come. A note without
 * `released_at` belongs to a version that isn't public yet; the release step adds the date. An
 * app on that version still gets its own note by version, so testers see it before release.
 */
function listReleasedEntries(baseDir: string, now: number = Date.now()): WhatsNewEntryFile[] {
  return listWhatsNewEntries(baseDir).filter((entry) => {
    const releasedAt = parseDateMs(entry.frontmatter.released_at)
    return releasedAt > 0 && releasedAt <= now
  })
}

function listWhatsNewEntries(baseDir: string): WhatsNewEntryFile[] {
  if (!existsSync(baseDir)) return []
  const entries = readdirSync(baseDir, { withFileTypes: true })
    .filter((dirent) => dirent.isFile() && dirent.name.toLowerCase().endsWith(".md"))
    .map((dirent) => dirent.name)
    .map((name) => ({ name, version: name.slice(0, -3) }))
    .filter(({ version }) => sanitizeVersionKey(version) !== null)
    .map(({ name, version }) => ({
      version,
      path: join(baseDir, name),
      frontmatter: readFrontmatter(join(baseDir, name)),
    }))

  entries.sort((a, b) => {
    const dateA = parseDateMs(a.frontmatter.released_at)
    const dateB = parseDateMs(b.frontmatter.released_at)
    if (dateA !== dateB) return dateB - dateA
    return compareSemverish(b.version, a.version)
  })

  return entries
}

function readFrontmatter(path: string): WhatsNewFrontmatter {
  return parseFrontmatter(readFileSync(path, "utf8"))
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
    const value = line.slice(idx + 1).trim().replace(/^\"(.*)\"$/, "$1")
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

function parseCursor(raw: string | undefined): number {
  if (!raw) return 0
  const value = Number.parseInt(raw, 10)
  if (!Number.isFinite(value) || value < 0) return 0
  return value
}

function parseLimit(raw: string | undefined): number {
  if (!raw) return 5
  const value = Number.parseInt(raw, 10)
  if (!Number.isFinite(value)) return 5
  return Math.min(Math.max(value, 1), 20)
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
