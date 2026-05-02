import { readFile, readdir } from "node:fs/promises"
import { existsSync } from "node:fs"
import path from "node:path"
import { loadConfig } from "../src/config.js"
import { createDatabase } from "../src/db.js"
import { createStickerStorage, StickerCatalogService } from "../src/stickers.js"

async function main() {
  await loadDockerComposeEnvIfPresent()
  const args = parseArgs(process.argv.slice(2))
  const config = loadConfig()
  const adminPassword = args.adminPassword ?? config.stickerAdminPassword ?? config.wallpaperAdminPassword
  if (!adminPassword) {
    throw new Error("STICKER_ADMIN_PASSWORD (or WALLPAPER_ADMIN_PASSWORD) is required (or pass --admin-password)")
  }
  const packKey = args.packKey
  const title = args.title
  const packVersion = args.version
  const dir = args.dir
  const published = args.published
  const featured = args.featured
  const sortOrder = args.sortOrder

  const db = await createDatabase(config.databaseUrl)
  try {
    const storage = createStickerStorage({
      supabaseUrl: config.supabaseUrl,
      supabaseServiceRoleKey: config.supabaseServiceRoleKey,
      supabaseStickerBucket: config.supabaseStickerBucket,
      stickerAdminPassword: config.stickerAdminPassword,
    })
    const service = new StickerCatalogService(db, storage, config.stickerAdminPassword)
    const entries = (await readdir(dir))
      .filter((name) => name.toLowerCase().endsWith(".png"))
      .sort((a, b) => {
        const na = Number.parseInt(a.replace(/\.png$/i, ""), 10)
        const nb = Number.parseInt(b.replace(/\.png$/i, ""), 10)
        if (Number.isFinite(na) && Number.isFinite(nb)) return na - nb
        return a.localeCompare(b)
      })

    if (entries.length === 0) {
      throw new Error(`No .png files found in ${dir}`)
    }

    const coverPath = path.join(dir, entries[0])
    const coverData = await readFile(coverPath)
    await service.createStickerPackVersion({
      adminPassword,
      packKey,
      packVersion,
      title,
      description: args.description ?? "",
      sortOrder,
      featured,
      published,
      coverFilename: path.basename(coverPath),
      coverContentType: "image/png",
      coverData,
    })

    for (let i = 0; i < entries.length; i += 1) {
      const filename = entries[i]
      const stickerId = filename.replace(/\.png$/i, "")
      const fullPath = path.join(dir, filename)
      const data = await readFile(fullPath)
      await service.uploadSticker({
        adminPassword,
        packKey,
        packVersion,
        stickerId,
        sortOrder: i,
        filename,
        contentType: "image/png",
        data,
      })
      process.stdout.write(`uploaded ${stickerId}\n`)
    }
  } finally {
    await db.end()
  }
}

async function loadDockerComposeEnvIfPresent() {
  const root = await findProjectRoot(process.cwd())
  if (!root) return

  // Mirror docker-compose behavior: apply repo-root `.env` defaults into process.env.
  const dotenvPath = path.join(root, ".env")
  if (existsSync(dotenvPath)) {
    const raw = await readFile(dotenvPath, "utf8")
    const parsed = parseDotEnv(raw)
    for (const [key, value] of Object.entries(parsed)) {
      if (!process.env[key] && value !== undefined) {
        process.env[key] = value
      }
    }
  }

  // If DATABASE_URL is still missing, try to infer it from docker-compose.yml (and rewrite
  // the container hostname to localhost for host-side scripts).
  if (!process.env.DATABASE_URL) {
    const composePath = path.join(root, "docker-compose.yml")
    if (existsSync(composePath)) {
      const compose = await readFile(composePath, "utf8")
      const match = compose.match(/^\s*DATABASE_URL:\s*([^\n#]+)\s*$/m)
      if (match) {
        const value = match[1].trim().replace(/^["']|["']$/g, "")
        process.env.DATABASE_URL = value.replace("@postgres:", "@localhost:")
      }
    }
  }
}

async function findProjectRoot(start: string): Promise<string | null> {
  let current = start
  for (let i = 0; i < 20; i += 1) {
    const candidate = path.join(current, "docker-compose.yml")
    if (existsSync(candidate)) return current
    const next = path.dirname(current)
    if (next === current) break
    current = next
  }
  return null
}

function parseDotEnv(raw: string): Record<string, string> {
  const out: Record<string, string> = {}
  for (const line of raw.split(/\r?\n/)) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith("#")) continue
    const match = trimmed.match(/^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/)
    if (!match) continue
    const key = match[1]
    let value = match[2] ?? ""
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1)
    }
    out[key] = value
  }
  return out
}

function parseArgs(argv: string[]) {
  const out: {
    adminPassword?: string
    packKey: string
    title: string
    description?: string
    version: number
    dir: string
    published: boolean
    featured: boolean
    sortOrder: number
  } = {
    packKey: "",
    title: "",
    version: 1,
    dir: "",
    published: true,
    featured: true,
    sortOrder: 0,
  }

  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i]
    if (token === "--admin-password") out.adminPassword = argv[++i]
    else if (token === "--pack-key") out.packKey = argv[++i] ?? ""
    else if (token === "--title") out.title = argv[++i] ?? ""
    else if (token === "--description") out.description = argv[++i] ?? ""
    else if (token === "--version") out.version = Number(argv[++i] ?? "1")
    else if (token === "--dir") out.dir = argv[++i] ?? ""
    else if (token === "--published") out.published = (argv[++i] ?? "true") === "true"
    else if (token === "--featured") out.featured = (argv[++i] ?? "true") === "true"
    else if (token === "--sort-order") out.sortOrder = Number(argv[++i] ?? "0")
    else throw new Error(`Unknown arg: ${token}`)
  }

  if (!out.packKey.trim()) throw new Error("--pack-key is required")
  if (!out.title.trim()) throw new Error("--title is required")
  if (!Number.isFinite(out.version) || out.version <= 0) throw new Error("--version must be >= 1")
  if (!out.dir.trim()) throw new Error("--dir is required")
  return out
}

void main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
