import { randomUUID } from "node:crypto"
import { createClient, type SupabaseClient } from "@supabase/supabase-js"
import sharp from "sharp"
import type { Database } from "./db.js"
import { HttpError } from "./service.js"

export interface StickerCatalogConfig {
  supabaseUrl?: string
  supabaseServiceRoleKey?: string
  supabaseStickerBucket?: string
  stickerAdminPassword?: string
}

export interface StickerStorage {
  upload(path: string, data: Buffer, contentType: string): Promise<void>
  remove(paths: string[]): Promise<void>
  signedUrl(path: string, expiresInSeconds: number): Promise<string>
}

export interface StickerPackSummary {
  packKey: string
  packVersion: number
  title: string
  description: string
  coverThumbnailUrl: string
  coverFullUrl: string
  sortOrder: number
  featured: boolean
}

export interface StickerPackAdminSummary extends StickerPackSummary {
  publishedAt: string | null
  createdAt: string
  updatedAt: string
  published: boolean
  stickerCount: number
}

export interface StickerSummary {
  stickerId: string
  thumbnailUrl: string
  fullUrl: string
  width: number
  height: number
  sortOrder: number
}

export interface StickerPackDetail extends StickerPackSummary {
  stickers: StickerSummary[]
}

export interface StickerPackAdminDetail extends StickerPackAdminSummary {
  stickers: StickerSummary[]
}

interface StickerPackRow {
  pack_key: string
  pack_version: number
  title: string
  description: string
  cover_thumbnail_path: string
  cover_full_path: string
  sort_order: number
  featured: boolean
  published_at: string | null
  created_at: string
  updated_at: string
}

interface StickerRow {
  pack_key: string
  pack_version: number
  sticker_id: string
  sort_order: number
  thumbnail_path: string
  full_path: string
  width: number
  height: number
  created_at: string
  updated_at: string
}

export class SupabaseStickerStorage implements StickerStorage {
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
      throw new HttpError(502, `Unable to upload sticker asset: ${error.message}`)
    }
  }

  async remove(paths: string[]): Promise<void> {
    if (paths.length === 0) return
    const { error } = await this.client.storage.from(this.bucket).remove(paths)
    if (error) {
      throw new HttpError(502, `Unable to delete sticker asset: ${error.message}`)
    }
  }

  async signedUrl(path: string, expiresInSeconds: number): Promise<string> {
    const { data, error } = await this.client.storage
      .from(this.bucket)
      .createSignedUrl(path, expiresInSeconds)
    if (error || !data?.signedUrl) {
      throw new HttpError(502, `Unable to create signed sticker URL: ${error?.message ?? "unknown error"}`)
    }
    return data.signedUrl
  }
}

export function createStickerStorage(config: StickerCatalogConfig): StickerStorage | null {
  if (!config.supabaseUrl || !config.supabaseServiceRoleKey || !config.supabaseStickerBucket) return null
  return new SupabaseStickerStorage(
    config.supabaseUrl,
    config.supabaseServiceRoleKey,
    config.supabaseStickerBucket,
  )
}

export class StickerCatalogService {
  constructor(
    private readonly db: Database,
    private readonly storage: StickerStorage | null,
    private readonly adminPassword?: string,
  ) {}

  async listPublishedStickerPacks(): Promise<{ items: StickerPackSummary[] }> {
    const rows = await this.db.query<StickerPackRow>(
      `
      SELECT pack_key, pack_version, title, description, cover_thumbnail_path, cover_full_path,
        sort_order, featured, published_at, created_at, updated_at
      FROM sticker_packs
      WHERE published_at IS NOT NULL
      ORDER BY featured DESC, sort_order ASC, created_at DESC, pack_key ASC, pack_version DESC
      `,
    )
    const items = await Promise.all(rows.rows.map((row) => this.packRowToSummary(row)))
    return { items }
  }

  async getPublishedStickerPackDetail(packKey: string, packVersion?: number): Promise<StickerPackDetail> {
    const key = packKey.trim()
    if (!key) throw new HttpError(400, "packKey is required")

    const packRows = await this.db.query<StickerPackRow>(
      `
      SELECT pack_key, pack_version, title, description, cover_thumbnail_path, cover_full_path,
        sort_order, featured, published_at, created_at, updated_at
      FROM sticker_packs
      WHERE pack_key = $1
        AND published_at IS NOT NULL
        ${Number.isFinite(packVersion) ? "AND pack_version = $2" : ""}
      ORDER BY pack_version DESC
      LIMIT 1
      `,
      Number.isFinite(packVersion) ? [key, packVersion] : [key],
    )
    const pack = packRows.rows[0]
    if (!pack) throw new HttpError(404, "Sticker pack not found")

    const stickerRows = await this.db.query<StickerRow>(
      `
      SELECT pack_key, pack_version, sticker_id, sort_order, thumbnail_path, full_path, width, height, created_at, updated_at
      FROM stickers
      WHERE pack_key = $1 AND pack_version = $2
      ORDER BY sort_order ASC, created_at DESC, sticker_id ASC
      `,
      [pack.pack_key, pack.pack_version],
    )

    const base = await this.packRowToSummary(pack)
    const stickers = await Promise.all(stickerRows.rows.map((row) => this.stickerRowToSummary(row)))
    return { ...base, stickers }
  }

  async getStickerAssetSignedUrl(options: {
    packKey: string
    packVersion: number
    stickerId: string
    variant: "thumbnail" | "full"
  }): Promise<{ url: string; expiresInSeconds: number }> {
    if (!this.storage) throw new HttpError(503, "Sticker storage is not configured")
    const packKey = options.packKey.trim()
    const stickerId = options.stickerId.trim()
    if (!packKey) throw new HttpError(400, "packKey is required")
    if (!stickerId) throw new HttpError(400, "stickerId is required")
    if (!Number.isFinite(options.packVersion) || options.packVersion <= 0) {
      throw new HttpError(400, "packVersion is required")
    }

    const rows = await this.db.query<Pick<StickerRow, "thumbnail_path" | "full_path">>(
      `
      SELECT thumbnail_path, full_path
      FROM stickers
      WHERE pack_key = $1 AND pack_version = $2 AND sticker_id = $3
      LIMIT 1
      `,
      [packKey, options.packVersion, stickerId],
    )
    const row = rows.rows[0]
    if (!row) throw new HttpError(404, "Sticker not found")
    const path = options.variant === "thumbnail" ? row.thumbnail_path : row.full_path
    const expiresInSeconds = 10 * 60
    return { url: await this.storage.signedUrl(path, expiresInSeconds), expiresInSeconds }
  }

  async listStickerPacksForAdmin(
    options: { adminPassword: string },
  ): Promise<{ items: StickerPackAdminSummary[] }> {
    this.requireAdmin(options.adminPassword)
    const rows = await this.db.query<
      StickerPackRow & {
        sticker_count: number
      }
    >(
      `
      SELECT pack_key, pack_version, title, description, cover_thumbnail_path, cover_full_path,
        sort_order, featured, published_at, created_at, updated_at,
        (SELECT COUNT(*)::int FROM stickers WHERE stickers.pack_key = sticker_packs.pack_key AND stickers.pack_version = sticker_packs.pack_version) as sticker_count
      FROM sticker_packs
      ORDER BY pack_key ASC, pack_version DESC
      `,
    )
    const items = await Promise.all(rows.rows.map((row) => this.packRowToAdminSummary(row)))
    return { items }
  }

  async getStickerPackDetailForAdmin(options: {
    adminPassword: string
    packKey: string
    packVersion: number
  }): Promise<StickerPackAdminDetail> {
    this.requireAdmin(options.adminPassword)
    if (!this.storage) throw new HttpError(503, "Sticker storage is not configured")

    const packKey = normalizePackKey(options.packKey)
    if (!packKey) throw new HttpError(400, "packKey is required")
    const packVersion = Number(options.packVersion)
    if (!Number.isFinite(packVersion) || packVersion <= 0) throw new HttpError(400, "packVersion must be >= 1")

    const packRows = await this.db.query<
      StickerPackRow & {
        sticker_count: number
      }
    >(
      `
      SELECT pack_key, pack_version, title, description, cover_thumbnail_path, cover_full_path,
        sort_order, featured, published_at, created_at, updated_at,
        (SELECT COUNT(*)::int FROM stickers WHERE stickers.pack_key = sticker_packs.pack_key AND stickers.pack_version = sticker_packs.pack_version) as sticker_count
      FROM sticker_packs
      WHERE pack_key = $1 AND pack_version = $2
      LIMIT 1
      `,
      [packKey, packVersion],
    )
    const pack = packRows.rows[0]
    if (!pack) throw new HttpError(404, "Sticker pack version not found")

    const stickerRows = await this.db.query<StickerRow>(
      `
      SELECT pack_key, pack_version, sticker_id, sort_order, thumbnail_path, full_path, width, height, created_at, updated_at
      FROM stickers
      WHERE pack_key = $1 AND pack_version = $2
      ORDER BY sort_order ASC, created_at DESC, sticker_id ASC
      `,
      [pack.pack_key, pack.pack_version],
    )

    const base = await this.packRowToAdminSummary(pack)
    const stickers = await Promise.all(stickerRows.rows.map((row) => this.stickerRowToSummary(row)))
    return { ...base, stickers }
  }

  async createStickerPackVersion(options: {
    adminPassword: string
    packKey: string
    packVersion: number
    title: string
    description?: string
    sortOrder?: number
    featured?: boolean
    published?: boolean
    coverFilename: string
    coverContentType: string
    coverData: Buffer
  }): Promise<StickerPackSummary> {
    this.requireAdmin(options.adminPassword)
    if (!this.storage) throw new HttpError(503, "Sticker storage is not configured")

    const packKey = normalizePackKey(options.packKey)
    if (!packKey) throw new HttpError(400, "packKey is required")
    const packVersion = Number(options.packVersion)
    if (!Number.isFinite(packVersion) || packVersion <= 0) throw new HttpError(400, "packVersion must be >= 1")
    const title = (options.title ?? "").trim()
    if (!title) throw new HttpError(400, "title is required")

    const { thumbnail, full, width, height, contentType, extension } = await processStickerImage(options.coverData)
    const coverId = randomUUID()
    const coverThumbPath = `sticker-packs/${packKey}/${packVersion}/cover-${coverId}-thumb.${extension}`
    const coverFullPath = `sticker-packs/${packKey}/${packVersion}/cover-${coverId}-full.${extension}`
    await this.storage.upload(coverThumbPath, thumbnail, contentType)
    await this.storage.upload(coverFullPath, full, contentType)

    const publishedAt = options.published ? new Date().toISOString() : null
    const rows = await this.db.query<StickerPackRow>(
      `
      INSERT INTO sticker_packs (
        pack_key,
        pack_version,
        title,
        description,
        cover_thumbnail_path,
        cover_full_path,
        sort_order,
        featured,
        published_at,
        created_at,
        updated_at
      ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, NOW(), NOW())
      ON CONFLICT (pack_key, pack_version) DO UPDATE SET
        title = EXCLUDED.title,
        description = EXCLUDED.description,
        cover_thumbnail_path = EXCLUDED.cover_thumbnail_path,
        cover_full_path = EXCLUDED.cover_full_path,
        sort_order = EXCLUDED.sort_order,
        featured = EXCLUDED.featured,
        published_at = EXCLUDED.published_at,
        updated_at = NOW()
      RETURNING pack_key, pack_version, title, description, cover_thumbnail_path, cover_full_path,
        sort_order, featured, published_at, created_at, updated_at
      `,
      [
        packKey,
        packVersion,
        title,
        (options.description ?? "").trim(),
        coverThumbPath,
        coverFullPath,
        Number(options.sortOrder ?? 0),
        Boolean(options.featured),
        publishedAt,
      ],
    )
    const row = rows.rows[0]
    return this.packRowToSummary(row)
  }

  async uploadSticker(options: {
    adminPassword: string
    packKey: string
    packVersion: number
    stickerId: string
    sortOrder?: number
    filename: string
    contentType: string
    data: Buffer
  }): Promise<StickerSummary> {
    this.requireAdmin(options.adminPassword)
    if (!this.storage) throw new HttpError(503, "Sticker storage is not configured")
    const packKey = normalizePackKey(options.packKey)
    if (!packKey) throw new HttpError(400, "packKey is required")
    const packVersion = Number(options.packVersion)
    if (!Number.isFinite(packVersion) || packVersion <= 0) throw new HttpError(400, "packVersion must be >= 1")
    const stickerId = options.stickerId.trim()
    if (!stickerId) throw new HttpError(400, "stickerId is required")

    const packExists = await this.db.query<{ ok: boolean }>(
      `
      SELECT TRUE as ok
      FROM sticker_packs
      WHERE pack_key = $1 AND pack_version = $2
      LIMIT 1
      `,
      [packKey, packVersion],
    )
    if (!packExists.rows[0]) throw new HttpError(404, "Sticker pack version not found")

    const { thumbnail, full, width, height, contentType, extension } = await processStickerImage(options.data)
    const assetId = randomUUID()
    const thumbPath = `stickers/${packKey}/${packVersion}/${stickerId}-${assetId}-thumb.${extension}`
    const fullPath = `stickers/${packKey}/${packVersion}/${stickerId}-${assetId}-full.${extension}`
    await this.storage.upload(thumbPath, thumbnail, contentType)
    await this.storage.upload(fullPath, full, contentType)

    const rows = await this.db.query<StickerRow>(
      `
      INSERT INTO stickers (
        pack_key,
        pack_version,
        sticker_id,
        sort_order,
        thumbnail_path,
        full_path,
        width,
        height,
        created_at,
        updated_at
      ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, NOW(), NOW())
      ON CONFLICT (pack_key, pack_version, sticker_id) DO UPDATE SET
        sort_order = EXCLUDED.sort_order,
        thumbnail_path = EXCLUDED.thumbnail_path,
        full_path = EXCLUDED.full_path,
        width = EXCLUDED.width,
        height = EXCLUDED.height,
        updated_at = NOW()
      RETURNING pack_key, pack_version, sticker_id, sort_order, thumbnail_path, full_path, width, height, created_at, updated_at
      `,
      [
        packKey,
        packVersion,
        stickerId,
        Number(options.sortOrder ?? 0),
        thumbPath,
        fullPath,
        width,
        height,
      ],
    )
    return this.stickerRowToSummary(rows.rows[0])
  }

  async deleteStickerPackVersionForAdmin(options: {
    adminPassword: string
    packKey: string
    packVersion: number
  }): Promise<void> {
    this.requireAdmin(options.adminPassword)
    if (!this.storage) throw new HttpError(503, "Sticker storage is not configured")

    const packKey = normalizePackKey(options.packKey)
    if (!packKey) throw new HttpError(400, "packKey is required")
    const packVersion = Number(options.packVersion)
    if (!Number.isFinite(packVersion) || packVersion <= 0) throw new HttpError(400, "packVersion must be >= 1")

    const packRows = await this.db.query<Pick<StickerPackRow, "cover_thumbnail_path" | "cover_full_path">>(
      `
      SELECT cover_thumbnail_path, cover_full_path
      FROM sticker_packs
      WHERE pack_key = $1 AND pack_version = $2
      LIMIT 1
      `,
      [packKey, packVersion],
    )
    const pack = packRows.rows[0]
    if (!pack) throw new HttpError(404, "Sticker pack version not found")

    const stickerRows = await this.db.query<Pick<StickerRow, "thumbnail_path" | "full_path">>(
      `
      SELECT thumbnail_path, full_path
      FROM stickers
      WHERE pack_key = $1 AND pack_version = $2
      `,
      [packKey, packVersion],
    )

    const paths: string[] = [
      pack.cover_thumbnail_path,
      pack.cover_full_path,
      ...stickerRows.rows.flatMap((row) => [row.thumbnail_path, row.full_path]),
    ].filter(Boolean)

    await this.storage.remove(Array.from(new Set(paths)))
    await this.db.query("DELETE FROM sticker_packs WHERE pack_key = $1 AND pack_version = $2", [packKey, packVersion])
  }

  async updateStickerPackVersionMetadata(options: {
    adminPassword: string
    packKey: string
    packVersion: number
    title?: string
    description?: string
    sortOrder?: number
    featured?: boolean
    published?: boolean
  }): Promise<StickerPackSummary> {
    this.requireAdmin(options.adminPassword)
    if (!this.storage) throw new HttpError(503, "Sticker storage is not configured")

    const packKey = normalizePackKey(options.packKey)
    if (!packKey) throw new HttpError(400, "packKey is required")
    const packVersion = Number(options.packVersion)
    if (!Number.isFinite(packVersion) || packVersion <= 0) throw new HttpError(400, "packVersion must be >= 1")

    const title = options.title !== undefined ? options.title.trim() : null
    if (title !== null && title.length === 0) throw new HttpError(400, "title must be non-empty when provided")
    const description = options.description !== undefined ? options.description.trim() : null
    const sortOrder = options.sortOrder !== undefined ? Number(options.sortOrder) : null
    const featured = options.featured !== undefined ? Boolean(options.featured) : null
    const publishedFlag = options.published !== undefined ? Boolean(options.published) : null

    const rows = await this.db.query<StickerPackRow>(
      `
      UPDATE sticker_packs
      SET title = COALESCE($3, title),
        description = COALESCE($4, description),
        sort_order = COALESCE($5, sort_order),
        featured = COALESCE($6, featured),
        published_at = CASE
          WHEN $7::boolean IS NULL THEN published_at
          WHEN $7 THEN NOW()
          ELSE NULL
        END,
        updated_at = NOW()
      WHERE pack_key = $1 AND pack_version = $2
      RETURNING pack_key, pack_version, title, description, cover_thumbnail_path, cover_full_path,
        sort_order, featured, published_at, created_at, updated_at
      `,
      [packKey, packVersion, title, description, sortOrder, featured, publishedFlag],
    )
    const row = rows.rows[0]
    if (!row) throw new HttpError(404, "Sticker pack version not found")
    return this.packRowToSummary(row)
  }

  private requireAdmin(candidate: string) {
    if (!this.adminPassword || candidate !== this.adminPassword) {
      throw new HttpError(401, "Invalid admin password")
    }
  }

  private async packRowToSummary(row: StickerPackRow): Promise<StickerPackSummary> {
    if (!this.storage) throw new HttpError(503, "Sticker storage is not configured")
    const expiresInSeconds = 10 * 60
    return {
      packKey: row.pack_key,
      packVersion: Number(row.pack_version),
      title: row.title,
      description: row.description,
      coverThumbnailUrl: await this.storage.signedUrl(row.cover_thumbnail_path, expiresInSeconds),
      coverFullUrl: await this.storage.signedUrl(row.cover_full_path, expiresInSeconds),
      sortOrder: Number(row.sort_order),
      featured: Boolean(row.featured),
    }
  }

  private async packRowToAdminSummary(
    row: StickerPackRow & { sticker_count?: number },
  ): Promise<StickerPackAdminSummary> {
    const base = await this.packRowToSummary(row)
    return {
      ...base,
      publishedAt: row.published_at,
      createdAt: row.created_at,
      updatedAt: row.updated_at,
      published: row.published_at != null,
      stickerCount: Number(row.sticker_count ?? 0),
    }
  }

  private async stickerRowToSummary(row: StickerRow): Promise<StickerSummary> {
    if (!this.storage) throw new HttpError(503, "Sticker storage is not configured")
    const expiresInSeconds = 10 * 60
    return {
      stickerId: row.sticker_id,
      thumbnailUrl: await this.storage.signedUrl(row.thumbnail_path, expiresInSeconds),
      fullUrl: await this.storage.signedUrl(row.full_path, expiresInSeconds),
      width: Number(row.width),
      height: Number(row.height),
      sortOrder: Number(row.sort_order),
    }
  }
}

function normalizePackKey(raw: string): string {
  return raw
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
}

async function processStickerImage(input: Buffer): Promise<{
  thumbnail: Buffer
  full: Buffer
  width: number
  height: number
  contentType: string
  extension: string
}> {
  const decoded = sharp(input).ensureAlpha()
  const meta = await decoded.metadata()
  const width = meta.width ?? 0
  const height = meta.height ?? 0
  if (width <= 0 || height <= 0) {
    throw new HttpError(400, "Invalid sticker image")
  }

  const full = await decoded.png({ compressionLevel: 9 }).toBuffer()
  const thumbnail = await decoded
    .resize({ width: 256, height: 256, fit: "contain", background: { r: 0, g: 0, b: 0, alpha: 0 } })
    .png({ compressionLevel: 9 })
    .toBuffer()

  return {
    thumbnail,
    full,
    width,
    height,
    contentType: "image/png",
    extension: "png",
  }
}
