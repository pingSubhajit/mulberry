import { randomUUID } from "node:crypto"
import sharp from "sharp"
import type { Database } from "../../infra/db/database.js"
import { HttpError } from "../../infra/http/HttpError.js"
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

interface CanvasPhotoRow {
  content_type: CanvasPhotoContentType
  bytes: Buffer
}

export class CanvasPhotoService {
  constructor(private readonly db: Database) {}

  /**
   * Stores a photo for the signed-in person's pair. `readImage` is only called once they are known
   * to be paired, so nobody else's upload is read.
   */
  async uploadPhoto(accessToken: string, readImage: () => Promise<Buffer>): Promise<CanvasPhotoUploadResponse> {
    const context = await requirePairedCanvasContext(this.db, accessToken)
    const input = await readImage()
    if (input.length === 0) {
      throw new HttpError(400, "image is required")
    }
    if (input.length > CANVAS_PHOTO_MAX_BYTES) {
      throw canvasPhotoTooLarge()
    }

    const photo = await normalizeCanvasPhoto(input)
    const photoId = randomUUID()
    // Sent as base64 and decoded by Postgres, which keeps the bytes intact on every driver.
    await this.db.query(
      `
      INSERT INTO canvas_photos (id, pair_session_id, uploader_user_id, content_type, width, height, bytes)
      VALUES ($1, $2, $3, $4, $5, $6, decode($7, 'base64'))
      `,
      [
        photoId,
        context.pairSession.id,
        context.user.id,
        photo.contentType,
        photo.width,
        photo.height,
        photo.bytes.toString("base64"),
      ],
    )
    return { photoId, width: photo.width, height: photo.height, contentType: photo.contentType }
  }

  /** A photo of the signed-in person's current pair; 404 for anything else, so ids give nothing away. */
  async getPhoto(accessToken: string, photoId: string): Promise<CanvasPhoto> {
    const context = await requireSessionContext(this.db, accessToken)
    const pairSession = await getPairSession(this.db, context.user.id)
    if (!pairSession || !photoId.trim()) {
      throw canvasPhotoNotFound()
    }
    const rows = await this.db.query<CanvasPhotoRow>(
      `SELECT content_type, bytes FROM canvas_photos WHERE id = $1 AND pair_session_id = $2`,
      [photoId, pairSession.id],
    )
    const row = rows.rows[0]
    if (!row) {
      throw canvasPhotoNotFound()
    }
    return { contentType: row.content_type, bytes: Buffer.from(row.bytes) }
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
