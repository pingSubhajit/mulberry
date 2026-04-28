#!/usr/bin/env node

import { readFile } from "node:fs/promises"
import { basename } from "node:path"

const KEEP_FILE_URL = new URL("../all_wallpapers.json", import.meta.url)

function usage() {
  const script = basename(new URL(import.meta.url).pathname)
  return [
    `Usage: node scripts/${script} <hostname> <cursor> <batchSize>`,
    "",
    "Args:",
    "  hostname   Base URL for the Mulberry API (e.g. http://localhost:8080 or https://api.mulberry.my).",
    "  cursor     Zero-based index into all_wallpapers.json.",
    "  batchSize  Number of wallpapers to seed in this run.",
    "",
    "Env:",
    "  MULBERRY_WALLPAPER_ADMIN_PASSWORD  Required; sent as x-wallpaper-admin-password.",
  ].join("\n")
}

function normalizeBaseUrl(input) {
  const trimmed = String(input ?? "").trim()
  if (!trimmed) throw new Error("hostname is required")

  const hasScheme = /^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(trimmed)
  const needsHttp = !hasScheme
  const isLocal = /^localhost\b|^127\.0\.0\.1\b|^0\.0\.0\.0\b/i.test(trimmed)
  const base = needsHttp ? `${isLocal ? "http" : "https"}://${trimmed}` : trimmed

  const url = new URL(base)
  if (!url.pathname.endsWith("/")) {
    url.pathname = `${url.pathname}/`
  }
  return url
}

function parseNonNegativeInt(raw, label) {
  const parsed = Number(raw)
  if (!Number.isInteger(parsed) || parsed < 0) {
    throw new Error(`${label} must be a non-negative integer`)
  }
  return parsed
}

function parsePositiveInt(raw, label) {
  const parsed = Number(raw)
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`${label} must be a positive integer`)
  }
  return parsed
}

function filenameFromContentType(contentType) {
  if (contentType === "image/png") return "wallpaper.png"
  if (contentType === "image/webp") return "wallpaper.webp"
  return "wallpaper.jpg"
}

const [hostnameArg, cursorArg, batchArg] = process.argv.slice(2)
if (!hostnameArg || cursorArg == null || batchArg == null) {
  console.error(usage())
  process.exit(2)
}

const adminPassword = process.env.MULBERRY_WALLPAPER_ADMIN_PASSWORD?.trim()
if (!adminPassword) {
  console.error("Missing env var: MULBERRY_WALLPAPER_ADMIN_PASSWORD")
  process.exit(2)
}

const baseUrl = normalizeBaseUrl(hostnameArg)
const cursor = parseNonNegativeInt(cursorArg, "cursor")
const batchSize = parsePositiveInt(batchArg, "batchSize")
const endpoint = new URL("admin/wallpapers", baseUrl).toString()

const allWallpapersRaw = await readFile(KEEP_FILE_URL, "utf8")
/** @type {Array<{title:string, description:string, image_url?:string, optimized_image_url?:string}>} */
const allWallpapers = JSON.parse(allWallpapersRaw)

if (cursor >= allWallpapers.length) {
  console.log(JSON.stringify({ seeded: 0, cursor, nextCursor: cursor, total: allWallpapers.length }))
  process.exit(0)
}

const batch = allWallpapers.slice(cursor, cursor + batchSize)
let seeded = 0
let failed = 0

for (let index = 0; index < batch.length; index += 1) {
  const item = batch[index]
  const title = String(item.title ?? "").trim()
  const description = String(item.description ?? "").trim()
  const imageUrl = String(item.optimized_image_url || item.image_url || "").trim()

  if (!title || !imageUrl) {
    failed += 1
    console.error(`[${cursor + index}] Skipping: missing title or image URL`)
    continue
  }

  try {
    const imageResponse = await fetch(imageUrl)
    if (!imageResponse.ok) {
      failed += 1
      console.error(`[${cursor + index}] Download failed: ${imageResponse.status} ${imageResponse.statusText}`)
      continue
    }

    const contentType = imageResponse.headers.get("content-type")?.split(";")[0]?.trim() || "image/jpeg"
    const bytes = await imageResponse.arrayBuffer()

    const form = new FormData()
    form.set("title", title)
    form.set("description", description)
    form.set("published", "true")
    form.set("sortOrder", String(cursor + index))
    form.set("image", new Blob([bytes], { type: contentType }), filenameFromContentType(contentType))

    const createResponse = await fetch(endpoint, {
      method: "POST",
      headers: {
        "x-wallpaper-admin-password": adminPassword,
      },
      body: form,
    })

    if (!createResponse.ok) {
      failed += 1
      const body = await createResponse.json().catch(() => null)
      console.error(
        `[${cursor + index}] Upload failed: ${createResponse.status} ${createResponse.statusText}` +
          (body?.message ? ` (${body.message})` : ""),
      )
      continue
    }

    const created = await createResponse.json().catch(() => ({}))
    seeded += 1
    console.log(`[${cursor + index}] Seeded: ${created?.id ?? "unknown"} ${title}`)
  } catch (err) {
    failed += 1
    console.error(`[${cursor + index}] Error: ${err instanceof Error ? err.message : String(err)}`)
  }
}

const nextCursor = cursor + batch.length
console.log(JSON.stringify({ seeded, failed, cursor, nextCursor, total: allWallpapers.length }))
