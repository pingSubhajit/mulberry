import { randomUUID } from "node:crypto"
import sharp from "sharp"
import type { Database } from "../../infra/db/database.js"
import { HttpError } from "../../infra/http/HttpError.js"
import { SupabaseFileStorage, type ReadableFileStorage } from "../../infra/storage/fileStorage.js"
import { LocalFileStorage } from "../../infra/storage/localFileStorage.js"
import type { AppConfig } from "../../infra/config/config.js"
import type { CanvasPhotoContentType, CanvasPhotoUploadResponse } from "../../contracts/canvas.js"
import { requireSessionContext } from "../_shared/session.js"
import { getPairSession } from "../_shared/pairs.js"
import { requirePairedCanvasContext } from "./service.js"

/** The largest photo `POST /canvas/photos` takes, before it is normalised. */
export const CANVAS_PHOTO_MAX_BYTES = 12 * 1024 * 1024

/** Photos are stored fitted inside this square, never enlarged. */
const CANVAS_PHOTO_MAX_EDGE = 2048

const CANVAS_PHOTO_JPEG_QUALITY = 85

/** A stored photo, as `GET /canvas/photos/:photoId` serves it. */
export interface CanvasPhoto {
  contentType: CanvasPhotoContentType
  bytes: Buffer
}

export interface NormalizedCanvasPhoto extends CanvasPhoto {
  width: number
  height: number
}

/** `GET /canvas/photos/:photoId/url`, shaped like `GET /stickers/assets/url`. */
export interface CanvasPhotoUrlResponse {
  url: string
  expiresInSeconds: number
}

/** Where photo files live: a private Supabase bucket, or the backend's disk in development. */
export type CanvasPhotoStorage = ReadableFileStorage

/** How long a photo's signed URL works. */
export const CANVAS_PHOTO_URL_TTL_SECONDS = 60 * 60

/** Local photo URLs, served by the backend in development. */
export const LOCAL_CANVAS_PHOTO_PREFIX = "/dev/canvas-photos/"

/** The most paths one storage delete is sent. */
const CANVAS_PHOTO_REMOVE_CHUNK = 100

interface CanvasPhotoRow {
  id: string
  content_type: CanvasPhotoContentType
  storage_key: string | null
}

/** Development-only canvas photos on the backend's own disk, never in the shared Supabase project. */
export class LocalCanvasPhotoStorage extends LocalFileStorage {
  constructor(root: string, secret?: Buffer) {
    super(root, LOCAL_CANVAS_PHOTO_PREFIX, "canvas photo", secret)
  }
}

export function createCanvasPhotoStorage(
  config: Pick<AppConfig, "canvasPhotoLocalStorageDir" | "supabaseUrl" | "supabaseServiceRoleKey" | "supabaseCanvasPhotoBucket">,
): CanvasPhotoStorage | null {
  if (config.canvasPhotoLocalStorageDir) return new LocalCanvasPhotoStorage(config.canvasPhotoLocalStorageDir)
  if (!config.supabaseUrl || !config.supabaseServiceRoleKey || !config.supabaseCanvasPhotoBucket) return null
  return new SupabaseFileStorage(
    config.supabaseUrl,
    config.supabaseServiceRoleKey,
    config.supabaseCanvasPhotoBucket,
    "canvas photo",
  )
}

/** Creates the Supabase bucket, private, the first time the server starts with it. Never throws. */
export async function ensureCanvasPhotoBucket(storage: CanvasPhotoStorage | null): Promise<void> {
  if (!(storage instanceof SupabaseFileStorage)) return
  try {
    if ((await storage.ensurePrivateBucket()) === "created") {
      console.info("[canvas-photos] created the private storage bucket")
    }
  } catch (error) {
    console.error("[canvas-photos] could not check or create the storage bucket", { error: errorMessage(error) })
  }
}

/** `pairs/<pairSessionId>/<photoId>.<jpg|png>` */
export function canvasPhotoStorageKey(pairSessionId: string, photoId: string, contentType: CanvasPhotoContentType): string {
  return `pairs/${pairSessionId}/${photoId}.${contentType === "image/png" ? "png" : "jpg"}`
}

/** Which photos to collect the storage keys of: a pair's, an uploader's, or either. */
export interface CanvasPhotoScope {
  pairSessionId?: string | null
  uploaderUserId?: string | null
}

/**
 * The storage keys of the photos in `scope`. Read inside the transaction that deletes their rows,
 * so their files can be removed once it commits.
 */
export async function canvasPhotoStorageKeys(db: Pick<Database, "query">, scope: CanvasPhotoScope): Promise<string[]> {
  const conditions: string[] = []
  const params: string[] = []
  if (scope.pairSessionId) {
    params.push(scope.pairSessionId)
    conditions.push(`pair_session_id = $${params.length}`)
  }
  if (scope.uploaderUserId) {
    params.push(scope.uploaderUserId)
    conditions.push(`uploader_user_id = $${params.length}`)
  }
  if (conditions.length === 0) return []
  const rows = await db.query<{ storage_key: string }>(
    `SELECT storage_key FROM canvas_photos WHERE storage_key IS NOT NULL AND (${conditions.join(" OR ")})`,
    params,
  )
  return [...new Set(rows.rows.map((row) => row.storage_key))]
}

/** Removes photo files whose rows are gone. Failures are logged, never thrown: the rows are gone either way. */
export interface CanvasPhotoFileRemover {
  removeStoredPhotos(keys: string[]): Promise<void>
}

export class CanvasPhotoService implements CanvasPhotoFileRemover {
  constructor(
    private readonly db: Database,
    private readonly storage: CanvasPhotoStorage | null = null,
  ) {}

  /**
   * Stores a photo for the signed-in person's pair. `readImage` is only called once they are known
   * to be paired, so nobody else's upload is read.
   */
  async uploadPhoto(accessToken: string, readImage: () => Promise<Buffer>): Promise<CanvasPhotoUploadResponse> {
    const context = await requirePairedCanvasContext(this.db, accessToken)
    const storage = this.requireStorage()
    const input = await readImage()
    if (input.length === 0) {
      throw new HttpError(400, "image is required")
    }
    if (input.length > CANVAS_PHOTO_MAX_BYTES) {
      throw canvasPhotoTooLarge()
    }

    const photo = await normalizeCanvasPhoto(input)
    const photoId = randomUUID()
    const storageKey = canvasPhotoStorageKey(context.pairSession.id, photoId, photo.contentType)
    await storage.upload(storageKey, photo.bytes, photo.contentType)
    try {
      await this.db.query(
        `
        INSERT INTO canvas_photos (id, pair_session_id, uploader_user_id, content_type, width, height, storage_key)
        VALUES ($1, $2, $3, $4, $5, $6, $7)
        `,
        [photoId, context.pairSession.id, context.user.id, photo.contentType, photo.width, photo.height, storageKey],
      )
    } catch (error) {
      // The pair ended mid-upload, most likely; the file has no row to go with.
      await this.removeStoredPhotos([storageKey])
      throw error
    }
    return { photoId, width: photo.width, height: photo.height, contentType: photo.contentType }
  }

  /** A short-lived URL to the photo's file. 404 unless it belongs to the signed-in person's current pair. */
  async getPhotoUrl(accessToken: string, photoId: string): Promise<CanvasPhotoUrlResponse> {
    const row = await this.findPhoto(accessToken, photoId)
    const storage = this.requireStorage()
    const storageKey = row.storage_key ?? (await this.moveToStorage(storage, row)).storageKey
    return {
      url: await storage.signedUrl(storageKey, CANVAS_PHOTO_URL_TTL_SECONDS),
      expiresInSeconds: CANVAS_PHOTO_URL_TTL_SECONDS,
    }
  }

  /** The photo's bytes, for builds that fetch them from the backend. Same 404 rules as the URL. */
  async getPhoto(accessToken: string, photoId: string): Promise<CanvasPhoto> {
    const row = await this.findPhoto(accessToken, photoId)
    if (row.storage_key) {
      return { contentType: row.content_type, bytes: await this.requireStorage().download(row.storage_key) }
    }
    if (!this.storage) {
      return { contentType: row.content_type, bytes: await this.legacyBytes(row.id) }
    }
    return { contentType: row.content_type, bytes: (await this.moveToStorage(this.storage, row)).bytes }
  }

  async removeStoredPhotos(keys: string[]): Promise<void> {
    if (!this.storage || keys.length === 0) return
    for (let start = 0; start < keys.length; start += CANVAS_PHOTO_REMOVE_CHUNK) {
      const chunk = keys.slice(start, start + CANVAS_PHOTO_REMOVE_CHUNK)
      try {
        await this.storage.remove(chunk)
      } catch (error) {
        console.error("[canvas-photos] could not remove photo files", { keys: chunk, error: errorMessage(error) })
      }
    }
  }

  private async findPhoto(accessToken: string, photoId: string): Promise<CanvasPhotoRow> {
    const context = await requireSessionContext(this.db, accessToken)
    const pairSession = await getPairSession(this.db, context.user.id)
    if (!pairSession || !photoId.trim()) {
      throw canvasPhotoNotFound()
    }
    const rows = await this.db.query<CanvasPhotoRow>(
      `SELECT id, content_type, storage_key FROM canvas_photos WHERE id = $1 AND pair_session_id = $2`,
      [photoId, pairSession.id],
    )
    const row = rows.rows[0]
    if (!row) {
      throw canvasPhotoNotFound()
    }
    return row
  }

  /**
   * Moves a photo stored before storage existed into it: uploads its bytes, records the key and
   * drops the bytes. Two reads racing both upload the same file to the same key, and one records it.
   */
  private async moveToStorage(
    storage: CanvasPhotoStorage,
    row: CanvasPhotoRow,
  ): Promise<{ storageKey: string; bytes: Buffer }> {
    const legacy = await this.db.query<{ pair_session_id: string; bytes: Buffer | null; storage_key: string | null }>(
      `SELECT pair_session_id, bytes, storage_key FROM canvas_photos WHERE id = $1`,
      [row.id],
    )
    const stored = legacy.rows[0]
    if (!stored) throw canvasPhotoNotFound()
    if (stored.storage_key) {
      // Another read moved it first.
      return { storageKey: stored.storage_key, bytes: await storage.download(stored.storage_key) }
    }
    if (!stored.bytes) throw canvasPhotoNotFound()
    const bytes = Buffer.from(stored.bytes)
    const storageKey = canvasPhotoStorageKey(stored.pair_session_id, row.id, row.content_type)
    await storage.upload(storageKey, bytes, row.content_type)
    await this.db.query(
      `UPDATE canvas_photos SET storage_key = $2, bytes = NULL WHERE id = $1 AND storage_key IS NULL`,
      [row.id, storageKey],
    )
    return { storageKey, bytes }
  }

  private async legacyBytes(photoId: string): Promise<Buffer> {
    const rows = await this.db.query<{ bytes: Buffer | null }>(`SELECT bytes FROM canvas_photos WHERE id = $1`, [photoId])
    const bytes = rows.rows[0]?.bytes
    if (!bytes) throw canvasPhotoStorageUnavailable()
    return Buffer.from(bytes)
  }

  private requireStorage(): CanvasPhotoStorage {
    if (!this.storage) throw canvasPhotoStorageUnavailable()
    return this.storage
  }
}

/**
 * Turns an upload into what the canvas stores: oriented by its EXIF, fitted inside 2048×2048, then
 * a PNG when it has transparency (cut-out stickers) or a JPEG otherwise. Metadata such as location
 * is dropped.
 */
export async function normalizeCanvasPhoto(input: Buffer): Promise<NormalizedCanvasPhoto> {
  let metadata: sharp.Metadata
  try {
    metadata = await sharp(input).metadata()
  } catch {
    throw canvasPhotoUnsupported()
  }
  if (!isAcceptedCanvasPhotoFormat(metadata)) {
    throw canvasPhotoUnsupported()
  }

  const fitted = (): sharp.Sharp =>
    sharp(input, { failOn: "none" })
      .rotate()
      .resize(CANVAS_PHOTO_MAX_EDGE, CANVAS_PHOTO_MAX_EDGE, { fit: "inside", withoutEnlargement: true })

  try {
    const transparent = metadata.hasAlpha === true && !(await fitted().stats()).isOpaque
    if (transparent) {
      const { data, info } = await fitted().png({ compressionLevel: 9 }).toBuffer({ resolveWithObject: true })
      return { contentType: "image/png", bytes: data, width: info.width, height: info.height }
    }
    const { data, info } = await fitted()
      .flatten({ background: "#ffffff" })
      .jpeg({ quality: CANVAS_PHOTO_JPEG_QUALITY })
      .toBuffer({ resolveWithObject: true })
    return { contentType: "image/jpeg", bytes: data, width: info.width, height: info.height }
  } catch {
    throw new HttpError(400, "Photo could not be read", { code: "CANVAS_PHOTO_UNREADABLE" })
  }
}

/** JPEG, PNG, WebP and HEIC. Sharp calls HEIC and AVIF both `heif`; only HEVC-coded HEIC is taken. */
function isAcceptedCanvasPhotoFormat(metadata: sharp.Metadata): boolean {
  switch (metadata.format) {
    case "jpeg":
    case "png":
    case "webp":
      return true
    case "heif":
      return metadata.compression === "hevc"
    default:
      return false
  }
}

export function canvasPhotoTooLarge(): HttpError {
  return new HttpError(413, "Photo must be 12 MB or smaller", { code: "CANVAS_PHOTO_TOO_LARGE" })
}

function canvasPhotoUnsupported(): HttpError {
  return new HttpError(400, "Photo must be JPEG, PNG, WebP or HEIC", { code: "CANVAS_PHOTO_UNSUPPORTED" })
}

function canvasPhotoNotFound(): HttpError {
  return new HttpError(404, "Photo not found")
}

function canvasPhotoStorageUnavailable(): HttpError {
  return new HttpError(503, "Photo storage is not configured", { code: "CANVAS_PHOTO_STORAGE_UNAVAILABLE" })
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
