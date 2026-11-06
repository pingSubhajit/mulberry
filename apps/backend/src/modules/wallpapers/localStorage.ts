import { absolutizeLocalUrls } from "../../infra/http/localAssetUrls.js"
import { LocalPublicFileStorage, type LocalPublicAsset } from "../../infra/storage/localPublicFileStorage.js"
import type { WallpaperStorage } from "./service.js"

export const LOCAL_WALLPAPER_ASSET_PREFIX = "/dev/wallpaper-assets/"

export type LocalWallpaperAsset = LocalPublicAsset

/**
 * Development-only wallpaper storage on the backend's own disk, so a local
 * catalog never uploads into the shared Supabase bucket. URLs are relative to
 * the backend and unsigned, like the public Supabase bucket they stand in for.
 */
export class LocalWallpaperStorage extends LocalPublicFileStorage implements WallpaperStorage {
  constructor(root: string) {
    super(root, LOCAL_WALLPAPER_ASSET_PREFIX, "wallpaper")
  }
}

/** Local asset URLs are relative; clients receive them on the host they called. */
export function absolutizeLocalWallpaperUrls<T>(value: T, origin: string): T {
  return absolutizeLocalUrls(value, LOCAL_WALLPAPER_ASSET_PREFIX, origin)
}
