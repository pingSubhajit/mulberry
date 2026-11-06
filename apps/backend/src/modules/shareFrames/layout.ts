import { HttpError } from "../../infra/http/HttpError.js"

/** The only layout version this backend accepts and clients render. */
export const SHARE_FRAME_LAYOUT_VERSION = 1
export const SHARE_FRAME_WIDTH = 1080
export const SHARE_FRAME_HEIGHT = 1920
/** The content image is always 1179×2556, the shape of an iPhone lock screen. */
export const SHARE_FRAME_CONTENT_ASPECT_RATIO = 1179 / 2556

/** How far the quad's side ratio may stray from `aspectRatio`. */
const QUAD_RATIO_TOLERANCE = 0.01
/** How far `bottomRight` may sit from the parallelogram the other three corners define, in pixels. */
const PARALLELOGRAM_TOLERANCE_PX = 1

export interface Point {
  x: number
  y: number
}

export interface ShareFrameQuad {
  topLeft: Point
  topRight: Point
  bottomRight: Point
  bottomLeft: Point
}

export type CaptionAnchor = "topLeft" | "topRight" | "bottomLeft" | "bottomRight"

export interface ShareFrameLayout {
  layoutVersion: 1
  key: string
  canvas: { width: number; height: number }
  content: {
    kind: "phone" | "polaroid"
    aspectRatio: number
    quad: ShareFrameQuad
  }
  caption: {
    position: Point
    anchor: CaptionAnchor
    rotationDegrees: number
    maxWidth: number
    maxLines: number
    maxFontSize: number
    minFontSize: number
    lineHeight: number
    align: "left" | "right"
    inkColor: string
    backing: { color: string; paddingX: number; paddingY: number } | null
  }
  brand: {
    position: Point
    anchor: "center"
    markHeight: number
    fontSize: number
    color: string
    backing: { color: string; paddingX: number; paddingY: number; cornerRadius: number } | null
  }
}

/** Frame keys are lowercase words joined by hyphens, as `valentines` or `spring-kittens`. */
export const FRAME_KEY_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/

export function isValidFrameKey(key: string): boolean {
  return key.length <= 64 && FRAME_KEY_PATTERN.test(key) && key !== "history"
}

class LayoutError extends Error {}

type JsonObject = Record<string, unknown>

function fail(path: string, expected: string): never {
  throw new LayoutError(`${path} must be ${expected}`)
}

function object(value: unknown, path: string, keys: readonly string[]): JsonObject {
  if (!value || typeof value !== "object" || Array.isArray(value)) fail(path, "an object")
  const record = value as JsonObject
  for (const key of Object.keys(record)) {
    if (!keys.includes(key)) throw new LayoutError(`${path}.${key} is not a layout field`)
  }
  for (const key of keys) {
    if (!(key in record)) throw new LayoutError(`${path}.${key} is required`)
  }
  return record
}

function number(value: unknown, path: string, { min, integer = false, exclusiveMin = false }: { min?: number; integer?: boolean; exclusiveMin?: boolean } = {}): number {
  const description = `${integer ? "an integer" : "a number"}${min === undefined ? "" : ` ${exclusiveMin ? "above" : "of at least"} ${min}`}`
  if (typeof value !== "number" || !Number.isFinite(value)) fail(path, description)
  if (integer && !Number.isInteger(value)) fail(path, description)
  if (min !== undefined && (exclusiveMin ? value <= min : value < min)) fail(path, description)
  return value
}

function oneOf<T extends string>(value: unknown, path: string, options: readonly T[]): T {
  if (typeof value !== "string" || !options.includes(value as T)) fail(path, `one of ${options.join(", ")}`)
  return value as T
}

function color(value: unknown, path: string): string {
  if (typeof value !== "string" || !/^#[0-9A-Fa-f]{6}$/.test(value)) fail(path, "a #RRGGBB colour")
  return value
}

function point(value: unknown, path: string): Point {
  const record = object(value, path, ["x", "y"])
  return { x: number(record.x, `${path}.x`), y: number(record.y, `${path}.y`) }
}

function parseQuad(value: unknown, path: string): ShareFrameQuad {
  const record = object(value, path, ["topLeft", "topRight", "bottomRight", "bottomLeft"])
  return {
    topLeft: point(record.topLeft, `${path}.topLeft`),
    topRight: point(record.topRight, `${path}.topRight`),
    bottomRight: point(record.bottomRight, `${path}.bottomRight`),
    bottomLeft: point(record.bottomLeft, `${path}.bottomLeft`),
  }
}

function parseLayout(raw: unknown): ShareFrameLayout {
  const root = object(raw, "layout", ["layoutVersion", "key", "canvas", "content", "caption", "brand"])
  if (root.layoutVersion !== SHARE_FRAME_LAYOUT_VERSION) fail("layout.layoutVersion", String(SHARE_FRAME_LAYOUT_VERSION))
  if (typeof root.key !== "string") fail("layout.key", "a string")

  const canvas = object(root.canvas, "layout.canvas", ["width", "height"])
  if (canvas.width !== SHARE_FRAME_WIDTH || canvas.height !== SHARE_FRAME_HEIGHT) {
    throw new LayoutError(`layout.canvas must be ${SHARE_FRAME_WIDTH}×${SHARE_FRAME_HEIGHT}`)
  }

  const content = object(root.content, "layout.content", ["kind", "aspectRatio", "quad"])
  const aspectRatio = number(content.aspectRatio, "layout.content.aspectRatio", { min: 0, exclusiveMin: true })
  if (Math.abs(aspectRatio - SHARE_FRAME_CONTENT_ASPECT_RATIO) > 1e-6) {
    fail("layout.content.aspectRatio", `1179/2556 (${SHARE_FRAME_CONTENT_ASPECT_RATIO})`)
  }

  const caption = object(root.caption, "layout.caption", [
    "position", "anchor", "rotationDegrees", "maxWidth", "maxLines", "maxFontSize", "minFontSize",
    "lineHeight", "align", "inkColor", "backing",
  ])
  const captionBacking = caption.backing === null ? null : object(caption.backing, "layout.caption.backing", ["color", "paddingX", "paddingY"])
  const maxFontSize = number(caption.maxFontSize, "layout.caption.maxFontSize", { min: 1, integer: true })
  const minFontSize = number(caption.minFontSize, "layout.caption.minFontSize", { min: 1, integer: true })
  if (minFontSize > maxFontSize) throw new LayoutError("layout.caption.minFontSize must not be above maxFontSize")

  const brand = object(root.brand, "layout.brand", ["position", "anchor", "markHeight", "fontSize", "color", "backing"])
  const brandBacking = brand.backing === null ? null : object(brand.backing, "layout.brand.backing", ["color", "paddingX", "paddingY", "cornerRadius"])

  return {
    layoutVersion: SHARE_FRAME_LAYOUT_VERSION,
    key: root.key,
    canvas: { width: SHARE_FRAME_WIDTH, height: SHARE_FRAME_HEIGHT },
    content: {
      kind: oneOf(content.kind, "layout.content.kind", ["phone", "polaroid"] as const),
      aspectRatio,
      quad: parseQuad(content.quad, "layout.content.quad"),
    },
    caption: {
      position: point(caption.position, "layout.caption.position"),
      anchor: oneOf(caption.anchor, "layout.caption.anchor", ["topLeft", "topRight", "bottomLeft", "bottomRight"] as const),
      rotationDegrees: number(caption.rotationDegrees, "layout.caption.rotationDegrees"),
      maxWidth: number(caption.maxWidth, "layout.caption.maxWidth", { min: 0, exclusiveMin: true }),
      maxLines: number(caption.maxLines, "layout.caption.maxLines", { min: 1, integer: true }),
      maxFontSize,
      minFontSize,
      lineHeight: number(caption.lineHeight, "layout.caption.lineHeight", { min: 0, exclusiveMin: true }),
      align: oneOf(caption.align, "layout.caption.align", ["left", "right"] as const),
      inkColor: color(caption.inkColor, "layout.caption.inkColor"),
      backing: captionBacking && {
        color: color(captionBacking.color, "layout.caption.backing.color"),
        paddingX: number(captionBacking.paddingX, "layout.caption.backing.paddingX", { min: 0 }),
        paddingY: number(captionBacking.paddingY, "layout.caption.backing.paddingY", { min: 0 }),
      },
    },
    brand: {
      position: point(brand.position, "layout.brand.position"),
      anchor: oneOf(brand.anchor, "layout.brand.anchor", ["center"] as const),
      markHeight: number(brand.markHeight, "layout.brand.markHeight", { min: 0, exclusiveMin: true }),
      fontSize: number(brand.fontSize, "layout.brand.fontSize", { min: 0, exclusiveMin: true }),
      color: color(brand.color, "layout.brand.color"),
      backing: brandBacking && {
        color: color(brandBacking.color, "layout.brand.backing.color"),
        paddingX: number(brandBacking.paddingX, "layout.brand.backing.paddingX", { min: 0 }),
        paddingY: number(brandBacking.paddingY, "layout.brand.backing.paddingY", { min: 0 }),
        cornerRadius: number(brandBacking.cornerRadius, "layout.brand.backing.cornerRadius", { min: 0 }),
      },
    },
  }
}

/** The quad's u axis (topLeft→topRight), v axis (topLeft→bottomLeft) and their cross product. */
export function quadAxes(quad: ShareFrameQuad): { ux: number; uy: number; vx: number; vy: number; det: number } {
  const ux = quad.topRight.x - quad.topLeft.x
  const uy = quad.topRight.y - quad.topLeft.y
  const vx = quad.bottomLeft.x - quad.topLeft.x
  const vy = quad.bottomLeft.y - quad.topLeft.y
  return { ux, uy, vx, vy, det: ux * vy - uy * vx }
}

function checkQuadGeometry(layout: ShareFrameLayout): void {
  const { quad, aspectRatio } = layout.content
  const { ux, uy, vx, vy, det } = quadAxes(quad)
  if (det <= 0) {
    throw new LayoutError("layout.content.quad must run clockwise from topLeft to topRight to bottomRight, with a non-zero area")
  }
  const expectedX = quad.topRight.x + vx
  const expectedY = quad.topRight.y + vy
  const drift = Math.hypot(quad.bottomRight.x - expectedX, quad.bottomRight.y - expectedY)
  if (drift > PARALLELOGRAM_TOLERANCE_PX) {
    throw new LayoutError(`layout.content.quad must be a parallelogram: bottomRight is ${drift.toFixed(2)}px from topRight + bottomLeft − topLeft`)
  }
  const ratio = Math.hypot(ux, uy) / Math.hypot(vx, vy)
  const error = Math.abs(ratio / aspectRatio - 1)
  if (error > QUAD_RATIO_TOLERANCE) {
    throw new LayoutError(`layout.content.quad's width/height ratio is ${ratio.toFixed(5)}, ${(error * 100).toFixed(2)}% from aspectRatio ${aspectRatio.toFixed(5)}; it must be within 1%`)
  }
}

/**
 * Parses and checks a layout for `frameKey`: a JSON string or an already parsed value. Throws a
 * 400 `INVALID_FRAME_LAYOUT` naming the first problem.
 */
export function validateShareFrameLayout(raw: unknown, frameKey: string): ShareFrameLayout {
  try {
    let value = raw
    if (typeof raw === "string") {
      try {
        value = JSON.parse(raw)
      } catch {
        throw new LayoutError("layout is not valid JSON")
      }
    }
    const layout = parseLayout(value)
    if (layout.key !== frameKey) throw new LayoutError(`layout.key "${layout.key}" must equal frameKey "${frameKey}"`)
    checkQuadGeometry(layout)
    return layout
  } catch (error) {
    if (error instanceof LayoutError) throw new HttpError(400, error.message, { code: "INVALID_FRAME_LAYOUT" })
    throw error
  }
}
