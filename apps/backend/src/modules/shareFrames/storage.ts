import { createClient, type SupabaseClient } from "@supabase/supabase-js"
import { HttpError } from "../../infra/http/HttpError.js"
import { absolutizeLocalUrls } from "../../infra/http/localAssetUrls.js"
import { LocalPublicFileStorage } from "../../infra/storage/localPublicFileStorage.js"

/** Where frame files live. Paths never change once written, so their public URLs are cacheable forever. */
export interface ShareFrameStorage {
  upload(path: string, data: Buffer, contentType: string): Promise<void>
  publicUrl(path: string): string
  /** Reads a stored original back, to check a new layout against the image it already has. */
  download(path: string): Promise<Buffer>
}

export const LOCAL_SHARE_FRAME_ASSET_PREFIX = "/dev/share-frame-assets/"

/**
 * Frames live in the public wallpaper bucket, under `share-frames/`, since apps fetch them by
 * plain URL like wallpapers.
 */
export class SupabaseShareFrameStorage implements ShareFrameStorage {
  private readonly client: SupabaseClient

  constructor(supabaseUrl: string, serviceRoleKey: string, private readonly bucket: string) {
    this.client = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    })
  }

  async upload(path: string, data: Buffer, contentType: string): Promise<void> {
    const { error } = await this.client.storage.from(this.bucket).upload(path, data, {
      cacheControl: "31536000",
      contentType,
      upsert: false,
    })
    if (error) throw new HttpError(502, `Unable to upload share frame asset: ${error.message}`, { code: "CATALOG_FAILURE" })
  }

  publicUrl(path: string): string {
    return this.client.storage.from(this.bucket).getPublicUrl(path).data.publicUrl
  }

  async download(path: string): Promise<Buffer> {
    const { data, error } = await this.client.storage.from(this.bucket).download(path)
    if (error || !data) {
      throw new HttpError(502, `Unable to read share frame asset: ${error?.message ?? "unknown error"}`, { code: "CATALOG_FAILURE" })
    }
    return Buffer.from(await data.arrayBuffer())
  }
}

/** Development only: frame files on the backend's disk, served under `/dev/share-frame-assets/`. */
export class LocalShareFrameStorage extends LocalPublicFileStorage implements ShareFrameStorage {
  constructor(root: string) {
    super(root, LOCAL_SHARE_FRAME_ASSET_PREFIX, "share frame")
  }
}

/** Local asset URLs are relative; clients receive them on the host they called. */
export function absolutizeLocalShareFrameUrls<T>(value: T, origin: string): T {
  return absolutizeLocalUrls(value, LOCAL_SHARE_FRAME_ASSET_PREFIX, origin)
}

export function createShareFrameStorage(config: {
  shareFrameLocalStorageDir?: string
  supabaseUrl?: string
  supabaseServiceRoleKey?: string
  supabaseWallpaperBucket?: string
}): ShareFrameStorage | null {
  if (config.shareFrameLocalStorageDir) return new LocalShareFrameStorage(config.shareFrameLocalStorageDir)
  const url = config.supabaseUrl?.trim()
  const key = config.supabaseServiceRoleKey?.trim()
  const bucket = config.supabaseWallpaperBucket?.trim()
  if (!url || !key || !bucket) return null
  return new SupabaseShareFrameStorage(url, key, bucket)
}
