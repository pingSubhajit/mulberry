/** Mirrors the backend's share frame contract; see docs/share-frames.md. */

export type Point = { x: number; y: number }

export type ShareFrameQuad = {
  topLeft: Point
  topRight: Point
  bottomRight: Point
  bottomLeft: Point
}

export type ShareFrameLayout = {
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
    anchor: "topLeft" | "topRight" | "bottomLeft" | "bottomRight"
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

export type ShareFrameItem = {
  key: string
  version: number
  title: string
  sortOrder: number
  availableFrom: string | null
  availableUntil: string | null
  frameUrl: string
  thumbnailUrl: string
  originalUrl: string
  width: number
  height: number
  layout: ShareFrameLayout
  published: boolean
  contentLocked: boolean
  publishedAt: string | null
  createdAt: string
  updatedAt: string
}
