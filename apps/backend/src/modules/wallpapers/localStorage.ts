import { mkdir, readFile, rm, writeFile } from "node:fs/promises"
import path from "node:path"
import { HttpError } from "../../infra/http/HttpError.js"
import { absolutizeLocalUrls } from "../../infra/http/localAssetUrls.js"
import type { WallpaperStorage } from "./service.js"

export const LOCAL_WALLPAPER_ASSET_PREFIX = "/dev/wallpaper-assets/"

const CONTENT_TYPES: Record<string, string> = {
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".png": "image/png",
  ".webp": "image/webp",
}

export interface LocalWallpaperAsset {
  data: Buffer
  contentType: string
}

/**
 * Development-only wallpaper storage on the backend's own disk, so a local
 * catalog never uploads into the shared Supabase bucket. URLs are relative to
 * the backend and unsigned, like the public Supabase bucket they stand in for.
 */
export class LocalWallpaperStorage implements WallpaperStorage {
  private readonly root: string

  constructor(root: string) {
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

  publicUrl(assetPath: string): string {
    this.resolve(assetPath)
    return `${LOCAL_WALLPAPER_ASSET_PREFIX}${assetPath.split("/").map(encodeURIComponent).join("/")}`
  }

  async read(assetPath: string): Promise<LocalWallpaperAsset> {
    const file = this.resolve(assetPath)
    const contentType = CONTENT_TYPES[path.extname(file).toLowerCase()]
    if (!contentType) throw new HttpError(404, "Wallpaper asset not found")
    try {
      return { data: await readFile(file), contentType }
    } catch {
      throw new HttpError(404, "Wallpaper asset not found")
    }
  }

  private resolve(assetPath: string): string {
    const file = path.resolve(this.root, assetPath)
    if (!file.startsWith(this.root + path.sep)) throw new HttpError(400, "Invalid wallpaper asset path")
    return file
  }
}

/** Local asset URLs are relative; clients receive them on the host they called. */
export function absolutizeLocalWallpaperUrls<T>(value: T, origin: string): T {
  return absolutizeLocalUrls(value, LOCAL_WALLPAPER_ASSET_PREFIX, origin)
}
