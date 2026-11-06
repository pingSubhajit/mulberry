"use client"

import { useEffect, useRef, useState } from "react"

import { cn } from "@/lib/utils"

import { captionFont, lockScreenFont } from "./fonts"
import type { Point, ShareFrameLayout } from "./types"

const WIDTH = 1080
const HEIGHT = 1920
const CONTENT_WIDTH = 1179
const CONTENT_HEIGHT = 2556
/** Instagram covers these with its own controls. */
const UNSAFE_TOP = 250
const UNSAFE_BOTTOM = 340
/** The Mulberry mark's natural size, as in /brand/icon-white.svg. */
const MARK_WIDTH = 37
const MARK_HEIGHT = 61
const BRAND_GAP = 13
const STRIP_RADIUS = 7

export const SAMPLE_CAPTION = "Look what Aarav drew on my lock screen"

type Measure = (text: string) => number

type InkBox = { left: number; ascent: number; width: number; height: number }

function inkBox(ctx: CanvasRenderingContext2D, text: string): InkBox {
  const metrics = ctx.measureText(text)
  return {
    left: metrics.actualBoundingBoxLeft,
    ascent: metrics.actualBoundingBoxAscent,
    width: metrics.actualBoundingBoxLeft + metrics.actualBoundingBoxRight,
    height: metrics.actualBoundingBoxAscent + metrics.actualBoundingBoxDescent
  }
}

/**
 * Fills the first line as far as it goes, then splits the remaining words into the fewest lines
 * that fit, choosing the split with the smallest sum of squared unused widths.
 */
function balancedLines(words: string[], measure: Measure, maxWidth: number, maxLines: number): string[] | null {
  const join = (start: number, end: number) => words.slice(start, end).join(" ")
  if (measure(words[0]) > maxWidth) return null
  let firstEnd = 1
  while (firstEnd < words.length && measure(join(0, firstEnd + 1)) <= maxWidth) firstEnd += 1
  const first = join(0, firstEnd)
  if (firstEnd === words.length) return [first]

  const memo = new Map<string, { score: number; lines: string[] } | null>()
  const rest = (start: number, count: number): { score: number; lines: string[] } | null => {
    if (start === words.length) return count === 0 ? { score: 0, lines: [] } : null
    if (count === 0) return null
    const memoKey = `${start}:${count}`
    if (memo.has(memoKey)) return memo.get(memoKey) ?? null
    let best: { score: number; lines: string[] } | null = null
    for (let end = start + 1; end <= words.length; end += 1) {
      const line = join(start, end)
      const width = measure(line)
      if (width > maxWidth) break
      const tail = rest(end, count - 1)
      if (!tail) continue
      const score = (maxWidth - width) ** 2 + tail.score
      if (!best || score < best.score) best = { score, lines: [line, ...tail.lines] }
    }
    memo.set(memoKey, best)
    return best
  }
  for (let count = 1; count < maxLines; count += 1) {
    const solution = rest(firstEnd, count)
    if (solution) return [first, ...solution.lines]
  }
  return null
}

function greedyLines(words: string[], measure: Measure, maxWidth: number): string[] {
  const lines: string[] = []
  for (const word of words) {
    const last = lines[lines.length - 1]
    if (last !== undefined && measure(`${last} ${word}`) <= maxWidth) lines[lines.length - 1] = `${last} ${word}`
    else lines.push(word)
  }
  return lines
}

/** The largest size from maxFontSize down whose wrapped caption fits; else minFontSize, wrapped greedily. */
function fitCaption(ctx: CanvasRenderingContext2D, text: string, caption: ShareFrameLayout["caption"], family: string) {
  const words = text.split(/\s+/).filter(Boolean)
  const measure: Measure = (line) => ctx.measureText(line).width
  for (let size = caption.maxFontSize; size >= caption.minFontSize; size -= 1) {
    ctx.font = `${size}px ${family}`
    const lines = words.length ? balancedLines(words, measure, caption.maxWidth, caption.maxLines) : []
    if (lines) return { size, lines }
  }
  ctx.font = `${caption.minFontSize}px ${family}`
  return { size: caption.minFontSize, lines: greedyLines(words, measure, caption.maxWidth) }
}

/** The block's corners after rotating about `position` by `degrees` (clockwise), with `anchor` at `position`. */
function placedCorners(position: Point, anchor: Point, degrees: number, width: number, height: number): Point[] {
  const angle = (degrees * Math.PI) / 180
  const cos = Math.cos(angle)
  const sin = Math.sin(angle)
  const point = (u: number, v: number) => ({
    x: position.x + cos * (u - anchor.x) - sin * (v - anchor.y),
    y: position.y + sin * (u - anchor.x) + cos * (v - anchor.y)
  })
  return [point(0, 0), point(width, 0), point(width, height), point(0, height)]
}

function drawCaption(ctx: CanvasRenderingContext2D, text: string, layout: ShareFrameLayout, family: string): Point[] {
  const caption = layout.caption
  const { size, lines } = fitCaption(ctx, text, caption, family)
  ctx.font = `${size}px ${family}`
  const padX = caption.backing?.paddingX ?? 0
  const padY = caption.backing?.paddingY ?? 0
  const boxes = lines.map((line) => inkBox(ctx, line))
  const advance = size * caption.lineHeight
  const width = Math.ceil(Math.max(0, ...boxes.map((box) => box.width)) + 2 * padX)
  const height = Math.ceil((lines.length - 1) * advance + Math.max(0, ...boxes.map((box) => box.height)) + 2 * padY)
  const anchor = {
    x: caption.anchor.endsWith("Right") ? width : 0,
    y: caption.anchor.startsWith("bottom") ? height : 0
  }

  ctx.save()
  ctx.translate(caption.position.x, caption.position.y)
  ctx.rotate((caption.rotationDegrees * Math.PI) / 180)
  ctx.translate(-anchor.x, -anchor.y)
  ctx.textBaseline = "alphabetic"
  ctx.textAlign = "left"
  lines.forEach((line, index) => {
    const box = boxes[index]
    const x = caption.align === "left" ? padX : width - padX - box.width
    const y = padY + index * advance
    if (caption.backing) {
      ctx.fillStyle = caption.backing.color
      ctx.beginPath()
      ctx.roundRect(x - padX, y - padY, box.width + 2 * padX, box.height + 2 * padY, STRIP_RADIUS)
      ctx.fill()
    }
    ctx.fillStyle = caption.inkColor
    ctx.fillText(line, x + box.left, y + box.ascent)
  })
  ctx.restore()
  return placedCorners(caption.position, anchor, caption.rotationDegrees, width, height)
}

function drawBrand(ctx: CanvasRenderingContext2D, layout: ShareFrameLayout, mark: HTMLImageElement, family: string): Point[] {
  const brand = layout.brand
  ctx.font = `500 ${brand.fontSize}px ${family}`
  const text = "mulberry.my"
  const box = inkBox(ctx, text)
  const markHeight = Math.round(brand.markHeight)
  const markWidth = Math.round((MARK_WIDTH / MARK_HEIGHT) * markHeight)
  const padX = brand.backing?.paddingX ?? 0
  const padY = brand.backing?.paddingY ?? 0
  const width = markWidth + BRAND_GAP + box.width + 2 * padX
  const height = Math.max(markHeight, box.height) + 2 * padY
  const left = brand.position.x - width / 2
  const top = brand.position.y - height / 2

  if (brand.backing) {
    ctx.fillStyle = brand.backing.color
    ctx.beginPath()
    ctx.roundRect(left, top, width, height, brand.backing.cornerRadius)
    ctx.fill()
  }
  // Tint the white mark with the brand colour.
  const tinted = document.createElement("canvas")
  tinted.width = markWidth
  tinted.height = markHeight
  const tint = tinted.getContext("2d")
  if (tint) {
    tint.drawImage(mark, 0, 0, markWidth, markHeight)
    tint.globalCompositeOperation = "source-in"
    tint.fillStyle = brand.color
    tint.fillRect(0, 0, markWidth, markHeight)
    ctx.drawImage(tinted, left + padX, top + Math.floor((height - markHeight) / 2))
  }
  ctx.fillStyle = brand.color
  ctx.textBaseline = "alphabetic"
  ctx.textAlign = "left"
  ctx.fillText(text, left + padX + markWidth + BRAND_GAP + box.left, top + (height - box.height) / 2 + box.ascent)
  return placedCorners(brand.position, { x: width / 2, y: height / 2 }, 0, width, height)
}

function clockText(now: Date): { date: string; time: string } {
  const weekday = now.toLocaleDateString("en-GB", { weekday: "long" })
  const month = now.toLocaleDateString("en-GB", { month: "long" })
  const hourCycle = new Intl.DateTimeFormat(undefined, { hour: "numeric" }).resolvedOptions().hourCycle
  const twelveHour = hourCycle === "h11" || hourCycle === "h12"
  const hours = twelveHour ? now.getHours() % 12 || 12 : now.getHours()
  return { date: `${weekday}, ${now.getDate()} ${month}`, time: `${hours}:${String(now.getMinutes()).padStart(2, "0")}` }
}

/** Sample lock-screen content: a plain dark gradient with the date and clock. */
function sampleContent(family: string): HTMLCanvasElement {
  const content = document.createElement("canvas")
  content.width = CONTENT_WIDTH
  content.height = CONTENT_HEIGHT
  const ctx = content.getContext("2d")
  if (!ctx) return content
  const gradient = ctx.createLinearGradient(0, 0, 0, CONTENT_HEIGHT)
  gradient.addColorStop(0, "#2a2238")
  gradient.addColorStop(0.55, "#16131f")
  gradient.addColorStop(1, "#0a090e")
  ctx.fillStyle = gradient
  ctx.fillRect(0, 0, CONTENT_WIDTH, CONTENT_HEIGHT)

  const { date, time } = clockText(new Date())
  ctx.shadowColor = "rgba(0, 0, 0, 0.3)"
  ctx.shadowBlur = 8
  ctx.shadowOffsetY = 3
  ctx.fillStyle = "#ffffff"
  ctx.textAlign = "left"
  ctx.textBaseline = "alphabetic"

  ctx.font = `500 59px ${family}`
  const dateBox = inkBox(ctx, date)
  ctx.fillText(date, (CONTENT_WIDTH - dateBox.width) / 2 + dateBox.left, CONTENT_HEIGHT * 0.11)

  ctx.font = `600 320px ${family}`
  const reference = inkBox(ctx, "7:23")
  const size = Math.round((320 * 230) / Math.max(1, reference.height))
  ctx.font = `600 ${size}px ${family}`
  const timeBox = inkBox(ctx, time)
  ctx.fillText(time, (CONTENT_WIDTH - timeBox.width) / 2 + timeBox.left, CONTENT_HEIGHT * 0.13 + timeBox.ascent)
  return content
}

function outline(ctx: CanvasRenderingContext2D, points: Point[], color: string): void {
  ctx.strokeStyle = color
  ctx.lineWidth = 3
  ctx.beginPath()
  points.forEach((point, index) => (index === 0 ? ctx.moveTo(point.x, point.y) : ctx.lineTo(point.x, point.y)))
  ctx.closePath()
  ctx.stroke()
}

function render(
  canvas: HTMLCanvasElement,
  frame: HTMLImageElement,
  mark: HTMLImageElement,
  layout: ShareFrameLayout,
  caption: string,
  showSafeZones: boolean
): void {
  const ctx = canvas.getContext("2d")
  if (!ctx) return
  const poppins = lockScreenFont.style.fontFamily
  const marker = captionFont.style.fontFamily
  ctx.setTransform(1, 0, 0, 1, 0, 0)
  ctx.clearRect(0, 0, WIDTH, HEIGHT)

  // The content goes through the quad's affine map: topLeft ← (0,0), topRight ← (1179,0), bottomLeft ← (0,2556).
  const { topLeft, topRight, bottomLeft } = layout.content.quad
  ctx.save()
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = "high"
  ctx.setTransform(
    (topRight.x - topLeft.x) / CONTENT_WIDTH,
    (topRight.y - topLeft.y) / CONTENT_WIDTH,
    (bottomLeft.x - topLeft.x) / CONTENT_HEIGHT,
    (bottomLeft.y - topLeft.y) / CONTENT_HEIGHT,
    topLeft.x,
    topLeft.y
  )
  ctx.drawImage(sampleContent(poppins), 0, 0)
  ctx.restore()

  ctx.drawImage(frame, 0, 0, WIDTH, HEIGHT)
  const captionCorners = drawCaption(ctx, caption, layout, marker)
  const brandCorners = drawBrand(ctx, layout, mark, poppins)

  if (showSafeZones) {
    ctx.fillStyle = "rgba(255, 0, 0, 0.25)"
    ctx.fillRect(0, 0, WIDTH, UNSAFE_TOP)
    ctx.fillRect(0, HEIGHT - UNSAFE_BOTTOM, WIDTH, UNSAFE_BOTTOM)
    const quad = layout.content.quad
    outline(ctx, [quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft], "#00e5ff")
    outline(ctx, captionCorners, "#00e060")
    outline(ctx, brandCorners, "#ff3df2")
  }
}

function loadImage(src: string): Promise<HTMLImageElement> {
  const image = new Image()
  image.decoding = "async"
  image.src = src
  return image.decode().then(() => image)
}

/**
 * Draws a frame as the apps compose it: sample lock-screen content through the quad, the frame on
 * top, then the caption and brand line. Caption fitting approximates the reference renderer.
 */
export function FramePreview({
  frameSrc,
  layout,
  caption = SAMPLE_CAPTION,
  showSafeZones = false,
  className
}: Readonly<{ frameSrc: string; layout: ShareFrameLayout; caption?: string; showSafeZones?: boolean; className?: string }>) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setError(null)
    const poppins = lockScreenFont.style.fontFamily
    const marker = captionFont.style.fontFamily
    void Promise.all([
      loadImage(frameSrc),
      loadImage("/brand/icon-white.svg"),
      document.fonts.load(`64px ${marker}`),
      document.fonts.load(`500 59px ${poppins}`),
      document.fonts.load(`600 59px ${poppins}`)
    ])
      .then(([frame, mark]) => {
        if (cancelled || !canvasRef.current) return
        render(canvasRef.current, frame, mark, layout, caption, showSafeZones)
      })
      .catch(() => {
        if (!cancelled) setError("Unable to load the frame image")
      })
    return () => {
      cancelled = true
    }
  }, [frameSrc, layout, caption, showSafeZones])

  return (
    <div className={cn("relative aspect-[9/16] overflow-hidden rounded-2xl bg-admin-fill", className)}>
      <canvas ref={canvasRef} width={WIDTH} height={HEIGHT} className="h-full w-full" />
      {error ? (
        <p className="absolute inset-0 flex items-center justify-center p-4 text-center text-[13px] text-admin-negative">{error}</p>
      ) : null}
      {/* Keeps both share fonts in the page so the canvas can use them. */}
      <span aria-hidden className={cn(captionFont.className, "pointer-events-none absolute opacity-0")}>.</span>
      <span aria-hidden className={cn(lockScreenFont.className, "pointer-events-none absolute opacity-0")}>.</span>
    </div>
  )
}
