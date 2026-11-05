import { absolutizeLocalUrls } from "../../infra/http/localAssetUrls.js"
import { LocalFileStorage } from "../../infra/storage/localFileStorage.js"

export const LOCAL_STICKER_ASSET_PREFIX = "/dev/sticker-assets/"

/**
 * Development-only sticker storage on the backend's own disk, so a local
 * catalog never uploads into the shared Supabase bucket. URLs are relative to
 * the backend and signed with a per-process key, like Supabase signed URLs.
 */
export class LocalStickerStorage extends LocalFileStorage {
  constructor(root: string, secret?: Buffer) {
    super(root, LOCAL_STICKER_ASSET_PREFIX, "sticker", secret)
  }
}

/** Local asset URLs are relative; clients receive them on the host they called. */
export function absolutizeLocalStickerUrls<T>(value: T, origin: string): T {
  return absolutizeLocalUrls(value, LOCAL_STICKER_ASSET_PREFIX, origin)
}
