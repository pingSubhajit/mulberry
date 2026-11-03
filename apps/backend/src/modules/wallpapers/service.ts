import { randomUUID } from "node:crypto"
import { createClient, type SupabaseClient } from "@supabase/supabase-js"
import sharp from "sharp"
import type { Database } from "../../infra/db/database.js"
import { HttpError } from "../../infra/http/HttpError.js"
import { requireAdminPassword } from "../../infra/http/adminAuth.js"
import { catalogMutation, catalogHistory, restoreCatalogChange } from "../catalog/history.js"

export interface WallpaperCatalogConfig {
  supabaseUrl?: string
  supabaseServiceRoleKey?: string
  supabaseWallpaperBucket?: string
  adminPassword?: string
}

export interface WallpaperStorage {
  upload(path: string, data: Buffer, contentType: string): Promise<void>
  remove(paths: string[]): Promise<void>
  publicUrl(path: string): string
}

export interface ProcessedWallpaperImage {
  width: number
  height: number
  dominantColor: string
  thumbnail: Buffer
  preview: Buffer
  full: Buffer
  contentType: string
  extension: string
}

export interface WallpaperImageProcessor {
  process(input: Buffer): Promise<ProcessedWallpaperImage>
}

export interface WallpaperCatalogItem {
  message?: string
  id: string
  title: string
  description: string
  thumbnailUrl: string
  previewUrl: string
  fullImageUrl: string
  width: number
  height: number
  dominantColor: string
}

export interface WallpaperAdminItem extends WallpaperCatalogItem {
  sortOrder: number
  published: boolean
  createdAt: string
  updatedAt: string
}

export interface WallpaperCatalogPage {
  items: WallpaperCatalogItem[]
  nextCursor: string | null
}

interface WallpaperRow {
  id: string
  title: string
  description: string
  storage_path: string
  thumbnail_path: string
  preview_path: string
  full_path: string
  width: number
  height: number
  dominant_color: string
  sort_order: number
  published_at: string | null
  created_at: string
  updated_at: string
}

interface WallpaperCursor {
  sortOrder: number
  createdAt: string
  id: string
}

export class SupabaseWallpaperStorage implements WallpaperStorage {
  private readonly client: SupabaseClient

  constructor(
    private readonly supabaseUrl: string,
    serviceRoleKey: string,
    private readonly bucket: string,
  ) {
    this.client = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    })
  }

  async upload(path: string, data: Buffer, contentType: string): Promise<void> {
    const { error } = await this.client.storage
      .from(this.bucket)
      .upload(path, data, {
        cacheControl: "31536000",
        contentType,
        upsert: true,
      })

    if (error) {
      throw new HttpError(502, `Unable to upload wallpaper asset: ${error.message}`)
    }
  }

  async remove(paths: string[]): Promise<void> {
    if (paths.length === 0) return
    const { error } = await this.client.storage.from(this.bucket).remove(paths)
    if (error) {
      throw new HttpError(502, `Unable to delete wallpaper asset: ${error.message}`)
    }
  }

  publicUrl(path: string): string {
    const { data } = this.client.storage.from(this.bucket).getPublicUrl(path)
    return data.publicUrl
  }
}

export class SharpWallpaperImageProcessor implements WallpaperImageProcessor {
  async process(input: Buffer): Promise<ProcessedWallpaperImage> {
    try {
      const source = sharp(input).rotate()
      const metadata = await source.metadata()
      if (!metadata.format || !["jpeg", "png", "webp"].includes(metadata.format)) {
        throw new HttpError(400, "Wallpaper image must decode as JPEG, PNG, or WebP", { code: "INVALID_CATALOG_INPUT" })
      }
      if (!metadata.width || !metadata.height) {
        throw new HttpError(400, "Wallpaper image dimensions could not be read")
      }
      if (metadata.width < 800 || metadata.height < 800) {
        throw new HttpError(400, "Wallpaper image must be at least 800px on both sides")
      }

      const stats = await source.clone().resize(64, 64, { fit: "cover" }).stats()
      const dominant = stats.dominant
      const dominantColor = toHexColor(dominant.r, dominant.g, dominant.b)
      const contentType = "image/webp"
      const extension = "webp"

      const thumbnail = await source
        .clone()
        .resize(720, 560, { fit: "cover", withoutEnlargement: true })
        .webp({ quality: 78 })
        .toBuffer()
      const preview = await source
        .clone()
        .resize(900, 1650, { fit: "cover", withoutEnlargement: true })
        .webp({ quality: 82 })
        .toBuffer()
      const full = await source
        .clone()
        .resize(2160, 3840, { fit: "inside", withoutEnlargement: true })
        .webp({ quality: 88 })
        .toBuffer()

      return {
        width: metadata.width,
        height: metadata.height,
        dominantColor,
        thumbnail,
        preview,
        full,
        contentType,
        extension,
      }
    } catch (error) {
      if (error instanceof HttpError) throw error
      throw new HttpError(400, "Wallpaper image could not be decoded. Supply a valid JPEG, PNG, or WebP image.", { code: "INVALID_CATALOG_INPUT" })
    }
  }
}

export class WallpaperCatalogService {
  constructor(
    private readonly db: Database,
    private readonly storage: WallpaperStorage | null,
    private readonly imageProcessor: WallpaperImageProcessor,
    private readonly config: WallpaperCatalogConfig,
    private readonly inMutation = false,
  ) {}

  async listPublishedWallpapers(input: {
    cursor?: string
    limit?: number
  }): Promise<WallpaperCatalogPage> {
    const limit = Math.min(Math.max(input.limit ?? 24, 1), 50)
    const cursor = decodeCursor(input.cursor)
    const params: unknown[] = [limit + 1]
    let cursorWhere = ""

    if (cursor) {
      params.push(cursor.sortOrder, cursor.createdAt, cursor.id)
      cursorWhere = `
        AND (
          sort_order > $2
          OR (sort_order = $2 AND created_at < $3::timestamptz)
          OR (sort_order = $2 AND created_at = $3::timestamptz AND id > $4)
        )
      `
    }

    const rows = await this.db.query<WallpaperRow>(
      `
        SELECT *
        FROM wallpapers
        WHERE published_at IS NOT NULL
        ${cursorWhere}
        ORDER BY sort_order ASC, created_at DESC, id ASC
        LIMIT $1
      `,
      params,
    )

    const pageRows = rows.rows.slice(0, limit)
    const nextRow = rows.rows[limit]
    const lastPageRow = pageRows[pageRows.length - 1]
    return {
      items: pageRows.map((row) => this.toCatalogItem(row)),
      nextCursor: nextRow && lastPageRow
        ? encodeCursor({
            sortOrder: lastPageRow.sort_order,
            createdAt: lastPageRow.created_at,
            id: lastPageRow.id,
          })
        : null,
    }
  }

  async createWallpaper(input: {
    adminPassword?: string
    title?: string
    description?: string
    sortOrder?: number
    published?: boolean
    filename?: string
    contentType?: string
    id?: string
    data?: Buffer
  }): Promise<WallpaperCatalogItem> {
    this.requireAdmin(input.adminPassword)
    input = { ...input, id: input.id ?? randomUUID() }
    if (!this.inMutation) {
      return catalogMutation(this.db, "wallpapers", input.id!, undefined, "createWallpaper", (db) => new WallpaperCatalogService(db, this.storage, this.imageProcessor, this.config, true).createWallpaper(input))
    }
    if (!this.storage) {
      throw new HttpError(503, "Wallpaper storage is not configured")
    }

    const title = (input.title ?? "").trim()
    if (!title) {
      throw new HttpError(400, "title is required")
    }
    const data = input.data
    if (!data || data.length === 0) {
      throw new HttpError(400, "image is required")
    }
    if (data.length > 20 * 1024 * 1024) {
      throw new HttpError(400, "Wallpaper image must be 20 MB or smaller")
    }
    if (!isSupportedImageType(input.contentType)) {
      throw new HttpError(400, "Wallpaper image must be JPEG, PNG, or WebP")
    }

    const processed = await this.imageProcessor.process(data)
    const id = input.id ?? randomUUID()
    const storageBase = `wallpapers/${id}`
    const originalExt = inferExtension(input.filename, input.contentType)
    const originalPath = `${storageBase}/original.${originalExt}`
    const thumbnailPath = `${storageBase}/thumbnail.${processed.extension}`
    const previewPath = `${storageBase}/preview.${processed.extension}`
    const fullPath = `${storageBase}/full.${processed.extension}`

    await this.storage.upload(originalPath, data, input.contentType ?? "application/octet-stream")
    await this.storage.upload(thumbnailPath, processed.thumbnail, processed.contentType)
    await this.storage.upload(previewPath, processed.preview, processed.contentType)
    await this.storage.upload(fullPath, processed.full, processed.contentType)

    const rows = await this.db.query<WallpaperRow>(
      `
        INSERT INTO wallpapers (
          id,
          title,
          description,
          storage_path,
          thumbnail_path,
          preview_path,
          full_path,
          width,
          height,
          dominant_color,
          sort_order,
          published_at
        )
        VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, CASE WHEN $12 THEN NOW() ELSE NULL END)
        RETURNING *
      `,
      [
        id,
        title,
        (input.description ?? "").trim(),
        originalPath,
        thumbnailPath,
        previewPath,
        fullPath,
        processed.width,
        processed.height,
        processed.dominantColor,
        input.sortOrder ?? 0,
        input.published ?? false,
      ],
    )

    return this.toCatalogItem(rows.rows[0])
  }

  async listWallpapersForAdmin(input: { adminPassword?: string }): Promise<{ items: WallpaperAdminItem[] }> {
    this.requireAdmin(input.adminPassword)
    const rows = await this.db.query<WallpaperRow>(
      `
        SELECT *
        FROM wallpapers
        ORDER BY sort_order ASC, created_at DESC, id ASC
      `,
    )
    return {
      items: rows.rows.map((row) => this.toAdminItem(row)),
    }
  }

  async updateWallpaper(input: {
    adminPassword?: string
    id: string
    title?: string
    description?: string
    sortOrder?: number
    published?: boolean
  }): Promise<WallpaperCatalogItem> {
    this.requireAdmin(input.adminPassword)
    if (!this.inMutation) {
      return catalogMutation(this.db, "wallpapers", input.id, undefined, "updateWallpaper", (db) => new WallpaperCatalogService(db, this.storage, this.imageProcessor, this.config, true).updateWallpaper(input))
    }
    const current = await this.getWallpaperRow(input.id)
    const rows = await this.db.query<WallpaperRow>(
      `
        UPDATE wallpapers
        SET title = $2,
            description = $3,
            sort_order = $4,
            published_at = CASE
              WHEN $5::boolean IS TRUE AND published_at IS NULL THEN NOW()
              WHEN $5::boolean IS FALSE THEN NULL
              ELSE published_at
            END,
            updated_at = NOW()
        WHERE id = $1
        RETURNING *
      `,
      [
        input.id,
        input.title?.trim() || current.title,
        input.description?.trim() ?? current.description,
        input.sortOrder ?? current.sort_order,
        input.published,
      ],
    )
    return this.toCatalogItem(rows.rows[0])
  }

  async deleteWallpaper(input: { adminPassword?: string; id: string; confirm?: boolean }): Promise<void> {
    this.requireAdmin(input.adminPassword)
    if (!this.inMutation) {
      return catalogMutation(this.db, "wallpapers", input.id, undefined, "deleteWallpaper", (db) => new WallpaperCatalogService(db, this.storage, this.imageProcessor, this.config, true).deleteWallpaper(input))
    }
    const row = await this.getWallpaperRow(input.id)
    if (input.confirm !== true) throw new HttpError(409,
      "Deleting this wallpaper can break saved links. Hide it with PATCH published:false instead. To delete, resend with confirm:true after explicit approval.",
      { code: "CONFIRMATION_REQUIRED", current: { id: row.id, title: row.title }, proposed: null })
    await this.db.query("DELETE FROM wallpapers WHERE id = $1", [input.id])
  }

  private async getWallpaperRow(id: string): Promise<WallpaperRow> {
    const rows = await this.db.query<WallpaperRow>("SELECT * FROM wallpapers WHERE id = $1", [id])
    const row = rows.rows[0]
    if (!row) {
      throw new HttpError(404, "Wallpaper not found")
    }
    return row
  }

  async replaceWallpaperImage(input: { adminPassword?: string; id: string; filename: string; contentType: string; data: Buffer }): Promise<WallpaperCatalogItem> {
    this.requireAdmin(input.adminPassword)
    if (!this.inMutation) return catalogMutation(this.db, "wallpapers", input.id, undefined, "replaceWallpaperImage",
      (db) => new WallpaperCatalogService(db, this.storage, this.imageProcessor, this.config, true).replaceWallpaperImage(input))
    await this.getWallpaperRow(input.id)
    if (!this.storage) throw new HttpError(503, "Wallpaper storage is not configured")
    if (!input.data.length || input.data.length > 20 * 1024 * 1024 || !isSupportedImageType(input.contentType)) {
      throw new HttpError(400, "Supply a JPEG, PNG, or WebP image of at most 20 MB", { code: "INVALID_CATALOG_INPUT" })
    }
    const image = await this.imageProcessor.process(input.data)
    const base = `wallpapers/${input.id}/${randomUUID()}`
    const paths = [`${base}/original.${inferExtension(input.filename, input.contentType)}`, `${base}/thumbnail.${image.extension}`, `${base}/preview.${image.extension}`, `${base}/full.${image.extension}`]
    for (const [index, data] of [input.data, image.thumbnail, image.preview, image.full].entries()) {
      await this.storage.upload(paths[index], data, index === 0 ? input.contentType : image.contentType)
    }
    const result = await this.db.query<WallpaperRow>(`UPDATE wallpapers SET storage_path = $2, thumbnail_path = $3,
      preview_path = $4, full_path = $5, width = $6, height = $7, dominant_color = $8, updated_at = NOW()
      WHERE id = $1 RETURNING *`, [input.id, ...paths, image.width, image.height, image.dominantColor])
    return this.toCatalogItem(result.rows[0])
  }

  async restore(adminPassword: string, changeId: string, confirm?: boolean) {
    this.requireAdmin(adminPassword)
    return restoreCatalogChange(this.db, "wallpapers", changeId, confirm)
  }

  async history(adminPassword: string, limit?: number) {
    this.requireAdmin(adminPassword)
    return catalogHistory(this.db, "wallpapers", limit)
  }

  private requireAdmin(adminPassword?: string): void {
    requireAdminPassword(this.config.adminPassword, adminPassword)
  }

  private toCatalogItem(row: WallpaperRow): WallpaperCatalogItem {
    if (!this.storage) {
      throw new HttpError(503, "Wallpaper storage is not configured")
    }
    return {
      id: row.id,
      title: row.title,
      description: row.description,
      thumbnailUrl: this.storage.publicUrl(row.thumbnail_path),
      previewUrl: this.storage.publicUrl(row.preview_path),
      fullImageUrl: this.storage.publicUrl(row.full_path),
      width: Number(row.width),
      height: Number(row.height),
      dominantColor: row.dominant_color,
    }
  }

  private toAdminItem(row: WallpaperRow): WallpaperAdminItem {
    return {
      ...this.toCatalogItem(row),
      sortOrder: Number(row.sort_order),
      published: row.published_at != null,
      createdAt: row.created_at,
      updatedAt: row.updated_at,
    }
  }
}

export function createWallpaperStorage(config: WallpaperCatalogConfig): WallpaperStorage | null {
  if (
    !config.supabaseUrl?.trim() ||
    !config.supabaseServiceRoleKey?.trim() ||
    !config.supabaseWallpaperBucket?.trim()
  ) {
    return null
  }
  return new SupabaseWallpaperStorage(
    config.supabaseUrl,
    config.supabaseServiceRoleKey,
    config.supabaseWallpaperBucket,
  )
}

function isSupportedImageType(contentType?: string): boolean {
  return ["image/jpeg", "image/png", "image/webp"].includes(contentType ?? "")
}

function inferExtension(filename?: string, contentType?: string): string {
  const clean = filename?.split(".").pop()?.toLowerCase()
  if (clean && ["jpg", "jpeg", "png", "webp"].includes(clean)) {
    return clean === "jpeg" ? "jpg" : clean
  }
  if (contentType === "image/png") return "png"
  if (contentType === "image/webp") return "webp"
  return "jpg"
}

function toHexColor(red: number, green: number, blue: number): string {
  return `#${[red, green, blue]
    .map((value) => Math.round(value).toString(16).padStart(2, "0"))
    .join("")
    .toUpperCase()}`
}

function encodeCursor(cursor: WallpaperCursor): string {
  return Buffer.from(JSON.stringify(cursor), "utf8").toString("base64url")
}

function decodeCursor(rawCursor?: string): WallpaperCursor | null {
  if (!rawCursor) return null
  try {
    const parsed = JSON.parse(Buffer.from(rawCursor, "base64url").toString("utf8")) as WallpaperCursor
    if (
      typeof parsed.sortOrder !== "number" ||
      typeof parsed.createdAt !== "string" ||
      typeof parsed.id !== "string"
    ) {
      return null
    }
    return parsed
  } catch {
    return null
  }
}
