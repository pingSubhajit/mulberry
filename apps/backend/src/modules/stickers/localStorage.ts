import { createHmac, randomBytes, timingSafeEqual } from "node:crypto"
import { mkdir, readFile, rm, writeFile } from "node:fs/promises"
import path from "node:path"
import { HttpError } from "../../infra/http/HttpError.js"
import type { StickerStorage } from "./service.js"

export const LOCAL_STICKER_ASSET_PREFIX = "/dev/sticker-assets/"

/**
 * Development-only sticker storage on the backend's own disk, so a local
 * catalog never uploads into the shared Supabase bucket. URLs are relative to
 * the backend and signed with a per-process key, like Supabase signed URLs.
 */
export class LocalStickerStorage implements StickerStorage {
  private readonly root: string

  constructor(root: string, private readonly secret: Buffer = randomBytes(32)) {
    this.root = path.resolve(root)
  }

  async upload(assetPath: string, data: Buffer): Promise<void> {
    const file = this.resolve(assetPath)
    await mkdir(path.dirname(file), { recursive: true })
    await writeFile(file, data)
  }

  async remove(paths: string[]): Promise<void> {
    await Promise.all(paths.map((assetPath) => rm(this.resolve(assetPath), { force: true })))
  }

  async signedUrl(assetPath: string, expiresInSeconds: number): Promise<string> {
    this.resolve(assetPath)
    const expires = Math.floor(Date.now() / 1000) + expiresInSeconds
    const encoded = assetPath.split("/").map(encodeURIComponent).join("/")
    return `${LOCAL_STICKER_ASSET_PREFIX}${encoded}?expires=${expires}&sig=${this.sign(assetPath, expires)}`
  }

  async read(assetPath: string, expires: number, signature: string): Promise<Buffer> {
    if (!Number.isFinite(expires) || expires < Date.now() / 1000) throw new HttpError(403, "Sticker URL expired")
    const expected = Buffer.from(this.sign(assetPath, expires))
    const actual = Buffer.from(signature)
    if (expected.length !== actual.length || !timingSafeEqual(expected, actual)) {
      throw new HttpError(403, "Invalid sticker URL signature")
    }
    try {
      return await readFile(this.resolve(assetPath))
    } catch {
      throw new HttpError(404, "Sticker asset not found")
    }
  }

  private sign(assetPath: string, expires: number): string {
    return createHmac("sha256", this.secret).update(`${assetPath}\n${expires}`).digest("base64url")
  }

  private resolve(assetPath: string): string {
    const file = path.resolve(this.root, assetPath)
    if (!file.startsWith(this.root + path.sep)) throw new HttpError(400, "Invalid sticker asset path")
    return file
  }
}

/** Local asset URLs are relative; clients receive them on the host they called. */
export function absolutizeLocalStickerUrls<T>(value: T, origin: string): T {
  if (typeof value === "string") {
    return (value.startsWith(LOCAL_STICKER_ASSET_PREFIX) ? `${origin}${value}` : value) as T
  }
  if (Array.isArray(value)) return value.map((item) => absolutizeLocalStickerUrls(item, origin)) as T
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [key, absolutizeLocalStickerUrls(item, origin)]),
    ) as T
  }
  return value
}
