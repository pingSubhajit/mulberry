import sharp from "sharp"
import { HttpError } from "../../infra/http/HttpError.js"
import { quadAxes, SHARE_FRAME_HEIGHT, SHARE_FRAME_WIDTH, type ShareFrameQuad } from "./layout.js"

export const MAX_SHARE_FRAME_BYTES = 20 * 1024 * 1024
export const SHARE_FRAME_THUMBNAIL_WIDTH = 270
export const SHARE_FRAME_THUMBNAIL_HEIGHT = 480

/** A decoded frame image: its straight alpha channel, one byte per pixel, row by row. */
export interface DecodedShareFrame {
  width: number
  height: number
  alpha: Buffer
}

export interface ShareFrameVariants {
  /** WebP with alpha, quality 90, at full size. */
  full: Buffer
  /** WebP with alpha, 270×480. */
  thumbnail: Buffer
}

function invalidImage(message: string): HttpError {
  return new HttpError(400, message, { code: "INVALID_FRAME_IMAGE" })
}

/** Decodes a frame PNG and checks it is 1080×1920 with an alpha channel. */
export async function decodeShareFrameImage(data: Buffer): Promise<DecodedShareFrame> {
  if (!data.length) throw invalidImage("image is required")
  if (data.length > MAX_SHARE_FRAME_BYTES) throw invalidImage("Frame image must be 20 MB or smaller")
  let metadata: sharp.Metadata
  try {
    metadata = await sharp(data).metadata()
  } catch {
    throw invalidImage("Frame image could not be decoded. Supply a valid PNG.")
  }
  if (metadata.format !== "png") throw invalidImage(`Frame image must be a PNG, not ${metadata.format ?? "an unknown format"}`)
  if (metadata.width !== SHARE_FRAME_WIDTH || metadata.height !== SHARE_FRAME_HEIGHT) {
    throw invalidImage(`Frame image must be exactly ${SHARE_FRAME_WIDTH}×${SHARE_FRAME_HEIGHT}, not ${metadata.width ?? "?"}×${metadata.height ?? "?"}`)
  }
  if (!metadata.hasAlpha) throw invalidImage("Frame image must have an alpha channel: its screen has to be transparent")
  try {
    const { data: alpha, info } = await sharp(data).extractChannel("alpha").raw({ depth: "uchar" }).toBuffer({ resolveWithObject: true })
    if (info.width !== SHARE_FRAME_WIDTH || info.height !== SHARE_FRAME_HEIGHT || alpha.length !== SHARE_FRAME_WIDTH * SHARE_FRAME_HEIGHT) {
      throw invalidImage("Frame image's alpha channel could not be read")
    }
    return { width: info.width, height: info.height, alpha }
  } catch (error) {
    if (error instanceof HttpError) throw error
    throw invalidImage("Frame image could not be decoded. Supply a valid PNG.")
  }
}

/**
 * Every pixel that isn't fully opaque must lie inside the content quad, or the share image would
 * show through to nothing there. Pixel centres are mapped into the quad's own coordinates with
 * the inverse affine map (topLeft the origin, topRight − topLeft the u axis, bottomLeft − topLeft
 * the v axis); inside means 0 ≤ u ≤ 1 and 0 ≤ v ≤ 1.
 */
export function checkAlphaInsideQuad(image: DecodedShareFrame, quad: ShareFrameQuad): void {
  const { ux, uy, vx, vy, det } = quadAxes(quad)
  const { x: ox, y: oy } = quad.topLeft
  const epsilon = 1e-9
  let outside = 0
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  const { width, height, alpha } = image
  for (let y = 0; y < height; y += 1) {
    const dy = y + 0.5 - oy
    const row = y * width
    for (let x = 0; x < width; x += 1) {
      if (alpha[row + x] === 255) continue
      const dx = x + 0.5 - ox
      const u = (vy * dx - vx * dy) / det
      const v = (-uy * dx + ux * dy) / det
      if (u >= -epsilon && u <= 1 + epsilon && v >= -epsilon && v <= 1 + epsilon) continue
      outside += 1
      if (x < minX) minX = x
      if (y < minY) minY = y
      if (x > maxX) maxX = x
      if (y > maxY) maxY = y
    }
  }
  if (outside > 0) {
    throw new HttpError(400,
      `${outside} pixel${outside === 1 ? "" : "s"} with alpha below 255 lie outside layout.content.quad, within x ${minX}–${maxX}, y ${minY}–${maxY}. Make them opaque or move the quad.`,
      { code: "INVALID_FRAME_LAYOUT", outsidePixels: outside, outsideBounds: { minX, minY, maxX, maxY } })
  }
}

/** The WebP variants apps download. Alpha stays lossless so the screen edge matches the PNG. */
export async function renderShareFrameVariants(data: Buffer): Promise<ShareFrameVariants> {
  const full = await sharp(data).webp({ quality: 90, alphaQuality: 100 }).toBuffer()
  const thumbnail = await sharp(data)
    .resize(SHARE_FRAME_THUMBNAIL_WIDTH, SHARE_FRAME_THUMBNAIL_HEIGHT, { fit: "fill" })
    .webp({ quality: 80, alphaQuality: 100 })
    .toBuffer()
  return { full, thumbnail }
}
