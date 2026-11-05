import { randomUUID } from "node:crypto"
import sharp from "sharp"
import type { Database } from "../../infra/db/database.js"
import { HttpError } from "../../infra/http/HttpError.js"
import { requireAdminPassword } from "../../infra/http/adminAuth.js"
import { catalogMutation, catalogHistory, restoreCatalogChange } from "../catalog/history.js"
import { SupabaseFileStorage, type FileStorage } from "../../infra/storage/fileStorage.js"
import { LocalStickerStorage } from "./localStorage.js"

export interface StickerCatalogConfig {
  supabaseUrl?: string
  supabaseServiceRoleKey?: string
  supabaseStickerBucket?: string
  /** Development only: keep sticker assets on local disk instead of Supabase. */
  stickerLocalStorageDir?: string
}

export type StickerStorage = FileStorage

/** Published pack versions never change, so their asset links can outlive a picker session. */
const SIGNED_URL_TTL_SECONDS = 24 * 60 * 60

export interface StickerPackSummary {
  message?: string
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
  contentLocked: boolean
}

export interface StickerSummary {
  message?: string
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
  content_locked: boolean
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

/** The sticker catalog's Supabase bucket. */
export class SupabaseStickerStorage extends SupabaseFileStorage {
  constructor(supabaseUrl: string, serviceRoleKey: string, bucket: string) {
    super(supabaseUrl, serviceRoleKey, bucket, "sticker asset")
  }
}

export function createStickerStorage(config: StickerCatalogConfig): StickerStorage | null {
  if (config.stickerLocalStorageDir) return new LocalStickerStorage(config.stickerLocalStorageDir)
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
    private readonly inMutation = false,
  ) {}

  async listPublishedStickerPacks(): Promise<{ items: StickerPackSummary[] }> {
    const rows = await this.db.query<StickerPackRow>(
      `
      SELECT pack_key, pack_version, title, description, cover_thumbnail_path, cover_full_path,
        sort_order, featured, published_at, content_locked, created_at, updated_at
      FROM sticker_packs
      WHERE published_at IS NOT NULL
      ORDER BY featured DESC, sort_order ASC, created_at DESC, pack_key ASC, pack_version DESC
      `,
    )
    const urls = await this.signAll(rows.rows.flatMap(packPaths))
    const items = await Promise.all(rows.rows.map((row) => this.packRowToSummary(row, urls)))
    return { items }
  }

  async getPublishedStickerPackDetail(packKey: string, packVersion?: number): Promise<StickerPackDetail> {
    const key = packKey.trim()
    if (!key) throw new HttpError(400, "packKey is required")

    const packRows = await this.db.query<StickerPackRow>(
      `
      SELECT pack_key, pack_version, title, description, cover_thumbnail_path, cover_full_path,
        sort_order, featured, published_at, content_locked, created_at, updated_at
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

    const urls = await this.signAll([...packPaths(pack), ...stickerRows.rows.flatMap(stickerPaths)])
    const base = await this.packRowToSummary(pack, urls)
    const stickers = await Promise.all(stickerRows.rows.map((row) => this.stickerRowToSummary(row, urls)))
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
    return { url: await this.storage.signedUrl(path, SIGNED_URL_TTL_SECONDS), expiresInSeconds: SIGNED_URL_TTL_SECONDS }
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
        sort_order, featured, published_at, content_locked, created_at, updated_at
      FROM sticker_packs
      ORDER BY pack_key ASC, pack_version DESC
      `,
    )
    const counts = await this.db.query<{ pack_key: string; pack_version: number; sticker_count: number }>(
      "SELECT pack_key, pack_version, COUNT(*)::int AS sticker_count FROM stickers GROUP BY pack_key, pack_version")
    const countByPack = new Map(counts.rows.map((row) => [`${row.pack_key}:${row.pack_version}`, row.sticker_count]))
    const urls = await this.signAll(rows.rows.flatMap(packPaths))
    const items = await Promise.all(rows.rows.map((row) => this.packRowToAdminSummary({ ...row, sticker_count: countByPack.get(`${row.pack_key}:${row.pack_version}`) ?? 0 }, urls)))
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
    if (!Number.isInteger(packVersion) || packVersion <= 0) throw new HttpError(400, "packVersion must be >= 1")

    const packRows = await this.db.query<
      StickerPackRow & {
        sticker_count: number
      }
    >(
      `
      SELECT pack_key, pack_version, title, description, cover_thumbnail_path, cover_full_path,
        sort_order, featured, published_at, content_locked, created_at, updated_at
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

    const urls = await this.signAll([...packPaths(pack), ...stickerRows.rows.flatMap(stickerPaths)])
    const base = await this.packRowToAdminSummary({ ...pack, sticker_count: stickerRows.rows.length }, urls)
    const stickers = await Promise.all(stickerRows.rows.map((row) => this.stickerRowToSummary(row, urls)))
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
    if (!this.inMutation) {
      return catalogMutation(this.db, "stickers", normalizePackKey(options.packKey), options.packVersion, "createStickerPackVersion", (db) => new StickerCatalogService(db, this.storage, this.adminPassword, true).createStickerPackVersion(options))
    }
    if (!this.storage) throw new HttpError(503, "Sticker storage is not configured")

    const packKey = normalizePackKey(options.packKey)
    if (!packKey) throw new HttpError(400, "packKey is required")
    const packVersion = Number(options.packVersion)
    if (!Number.isInteger(packVersion) || packVersion <= 0) throw new HttpError(400, "packVersion must be >= 1")
    const title = (options.title ?? "").trim()
    if (!title) throw new HttpError(400, "title is required")

    const existing = await this.db.query("SELECT pack_key FROM sticker_packs WHERE pack_key = $1 AND pack_version = $2", [packKey, packVersion])
    if (existing.rows.length) throw new HttpError(409, "Pack version exists. Use PATCH for metadata or copy to a new version.", { code: "PACK_VERSION_EXISTS" })

    await this.requireUnusedVersion(packKey, packVersion)

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
        content_locked,
        created_at,
        updated_at
      ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, NOW(), NOW())
      RETURNING pack_key, pack_version, title, description, cover_thumbnail_path, cover_full_path,
        sort_order, featured, published_at, content_locked, created_at, updated_at
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
        Boolean(options.published),
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
    if (!this.inMutation) {
      return catalogMutation(this.db, "stickers", normalizePackKey(options.packKey), options.packVersion, "uploadSticker", (db) => new StickerCatalogService(db, this.storage, this.adminPassword, true).uploadSticker(options))
    }
    if (!this.storage) throw new HttpError(503, "Sticker storage is not configured")
    const packKey = normalizePackKey(options.packKey)
    if (!packKey) throw new HttpError(400, "packKey is required")
    const packVersion = Number(options.packVersion)
    if (!Number.isInteger(packVersion) || packVersion <= 0) throw new HttpError(400, "packVersion must be >= 1")
    const stickerId = options.stickerId.trim()
    if (!stickerId) throw new HttpError(400, "stickerId is required")

    await this.requireDraft(packKey, packVersion)

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
    confirm?: boolean
  }): Promise<void> {
    this.requireAdmin(options.adminPassword)
    if (!this.inMutation) {
      return catalogMutation(this.db, "stickers", normalizePackKey(options.packKey), options.packVersion, "deleteStickerPackVersionForAdmin", (db) => new StickerCatalogService(db, this.storage, this.adminPassword, true).deleteStickerPackVersionForAdmin(options))
    }
    if (!this.storage) throw new HttpError(503, "Sticker storage is not configured")

    const packKey = normalizePackKey(options.packKey)
    if (!packKey) throw new HttpError(400, "packKey is required")
    const packVersion = Number(options.packVersion)
    if (!Number.isInteger(packVersion) || packVersion <= 0) throw new HttpError(400, "packVersion must be >= 1")

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

    if (options.confirm !== true) throw new HttpError(409,
      "Deleting this version can break saved canvases. Hide it with PATCH published:false instead. To delete, resend with confirm:true after explicit approval.",
      { code: "CONFIRMATION_REQUIRED", current: { packKey, packVersion }, proposed: null })

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
    if (!this.inMutation) {
      return catalogMutation(this.db, "stickers", normalizePackKey(options.packKey), options.packVersion, "updateStickerPackVersionMetadata", (db) => new StickerCatalogService(db, this.storage, this.adminPassword, true).updateStickerPackVersionMetadata(options))
    }
    if (!this.storage) throw new HttpError(503, "Sticker storage is not configured")

    const packKey = normalizePackKey(options.packKey)
    if (!packKey) throw new HttpError(400, "packKey is required")
    const packVersion = Number(options.packVersion)
    if (!Number.isInteger(packVersion) || packVersion <= 0) throw new HttpError(400, "packVersion must be >= 1")

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
        content_locked = content_locked OR COALESCE($7, FALSE),
        published_at = CASE
          WHEN $7::boolean IS NULL THEN published_at
          WHEN $7 THEN NOW()
          ELSE NULL
        END,
        updated_at = NOW()
      WHERE pack_key = $1 AND pack_version = $2
      RETURNING pack_key, pack_version, title, description, cover_thumbnail_path, cover_full_path,
        sort_order, featured, published_at, content_locked, created_at, updated_at
      `,
      [packKey, packVersion, title, description, sortOrder, featured, publishedFlag],
    )
    const row = rows.rows[0]
    if (!row) throw new HttpError(404, "Sticker pack version not found")
    return this.packRowToSummary(row)
  }

  async updateStickerPackVersionCoverForAdmin(options: {
    adminPassword: string
    packKey: string
    packVersion: number
    filename: string
    contentType: string
    data: Buffer
  }): Promise<StickerPackSummary> {
    this.requireAdmin(options.adminPassword)
    if (!this.inMutation) {
      return catalogMutation(this.db, "stickers", normalizePackKey(options.packKey), options.packVersion, "updateStickerPackVersionCoverForAdmin", (db) => new StickerCatalogService(db, this.storage, this.adminPassword, true).updateStickerPackVersionCoverForAdmin(options))
    }
    if (!this.storage) throw new HttpError(503, "Sticker storage is not configured")

    const packKey = normalizePackKey(options.packKey)
    if (!packKey) throw new HttpError(400, "packKey is required")
    const packVersion = Number(options.packVersion)
    if (!Number.isInteger(packVersion) || packVersion <= 0) throw new HttpError(400, "packVersion must be >= 1")

    await this.requireDraft(packKey, packVersion)

    const existingRows = await this.db.query<Pick<StickerPackRow, "cover_thumbnail_path" | "cover_full_path">>(
      `
      SELECT cover_thumbnail_path, cover_full_path
      FROM sticker_packs
      WHERE pack_key = $1 AND pack_version = $2
      LIMIT 1
      `,
      [packKey, packVersion],
    )
    const existing = existingRows.rows[0]
    if (!existing) throw new HttpError(404, "Sticker pack version not found")

    const { thumbnail, full, contentType, extension } = await processStickerImage(options.data)
    const coverId = randomUUID()
    const coverThumbPath = `sticker-packs/${packKey}/${packVersion}/cover-${coverId}-thumb.${extension}`
    const coverFullPath = `sticker-packs/${packKey}/${packVersion}/cover-${coverId}-full.${extension}`
    await this.storage.upload(coverThumbPath, thumbnail, contentType)
    await this.storage.upload(coverFullPath, full, contentType)

    const rows = await this.db.query<StickerPackRow>(
      `
      UPDATE sticker_packs
      SET cover_thumbnail_path = $3,
        cover_full_path = $4,
        updated_at = NOW()
      WHERE pack_key = $1 AND pack_version = $2
      RETURNING pack_key, pack_version, title, description, cover_thumbnail_path, cover_full_path,
        sort_order, featured, published_at, content_locked, created_at, updated_at
      `,
      [packKey, packVersion, coverThumbPath, coverFullPath],
    )
    const row = rows.rows[0]
    if (!row) throw new HttpError(404, "Sticker pack version not found")

    return this.packRowToSummary(row)
  }

  async copyStickerPackVersion(options: { adminPassword: string; packKey: string; packVersion: number; newVersion: number }): Promise<StickerPackAdminDetail> {
    this.requireAdmin(options.adminPassword)
    const packKey = normalizePackKey(options.packKey)
    if (!Number.isInteger(options.newVersion) || options.newVersion <= options.packVersion) {
      throw new HttpError(400, "newVersion must be an integer greater than the source version", { code: "INVALID_CATALOG_INPUT" })
    }
    if (!this.inMutation) return catalogMutation(this.db, "stickers", packKey, options.newVersion, "copyStickerPackVersion",
      (db) => new StickerCatalogService(db, this.storage, this.adminPassword, true).copyStickerPackVersion(options))
    if (!packKey || !Number.isSafeInteger(options.packVersion) || options.packVersion < 1) throw new HttpError(400, "Supply a pack key and positive source version", { code: "INVALID_CATALOG_INPUT" })
    await this.requireUnusedVersion(packKey, options.newVersion)
    const source = await this.db.query<StickerPackRow>("SELECT * FROM sticker_packs WHERE pack_key = $1 AND pack_version = $2 FOR UPDATE", [packKey, options.packVersion])
    if (!source.rows[0]) throw new HttpError(404, "Source pack version not found", { code: "PACK_NOT_FOUND" })
    await this.db.query(`INSERT INTO sticker_packs
      (pack_key, pack_version, title, description, cover_thumbnail_path, cover_full_path, sort_order, featured)
      SELECT pack_key, $3::integer, title, description, cover_thumbnail_path, cover_full_path, sort_order, featured
      FROM sticker_packs WHERE pack_key = $1 AND pack_version = $2`, [packKey, options.packVersion, options.newVersion])
    await this.db.query(`INSERT INTO stickers
      (pack_key, pack_version, sticker_id, sort_order, thumbnail_path, full_path, width, height)
      SELECT pack_key, $3::integer, sticker_id, sort_order, thumbnail_path, full_path, width, height
      FROM stickers WHERE pack_key = $1 AND pack_version = $2`, [packKey, options.packVersion, options.newVersion])
    return this.getStickerPackDetailForAdmin({ ...options, packVersion: options.newVersion })
  }

  async editSticker(options: { adminPassword: string; packKey: string; packVersion: number; stickerId: string; sortOrder?: number; delete?: boolean }) {
    this.requireAdmin(options.adminPassword)
    const packKey = normalizePackKey(options.packKey)
    if (!this.inMutation) return catalogMutation(this.db, "stickers", packKey, options.packVersion, options.delete ? "deleteSticker" : "reorderSticker",
      (db): Promise<{ message: string }> => new StickerCatalogService(db, this.storage, this.adminPassword, true).editSticker(options))
    await this.requireDraft(packKey, options.packVersion)
    if (!options.delete && !Number.isInteger(options.sortOrder)) throw new HttpError(400, "sortOrder must be an integer", { code: "INVALID_CATALOG_INPUT" })
    const result = options.delete
      ? await this.db.query("DELETE FROM stickers WHERE pack_key = $1 AND pack_version = $2 AND sticker_id = $3 RETURNING sticker_id", [packKey, options.packVersion, options.stickerId])
      : await this.db.query("UPDATE stickers SET sort_order = $4, updated_at = NOW() WHERE pack_key = $1 AND pack_version = $2 AND sticker_id = $3 RETURNING sticker_id", [packKey, options.packVersion, options.stickerId, options.sortOrder])
    if (!result.rows.length) throw new HttpError(404, "Sticker not found", { code: "STICKER_NOT_FOUND" })
    return { message: options.delete ? "Sticker removed from draft. Assets retained for recovery." : "Sticker order updated." }
  }

  private async requireUnusedVersion(packKey: string, packVersion: number): Promise<void> {
    const used = await this.db.query("SELECT id FROM catalog_admin.admin_catalog_changes WHERE content_type = 'stickers' AND content_key = $1 LIMIT 1", [`${packKey}:${packVersion}`])
    if (used.rows.length) throw new HttpError(409, "This version identity was used before. Choose a new version, or restore its deletion from history.", { code: "PACK_VERSION_EXISTS" })
  }

  private async requireDraft(packKey: string, packVersion: number): Promise<void> {
    const result = await this.db.query<StickerPackRow>("SELECT * FROM sticker_packs WHERE pack_key = $1 AND pack_version = $2", [packKey, packVersion])
    const pack = result.rows[0]
    if (!pack) throw new HttpError(404, "Sticker pack version not found", { code: "PACK_NOT_FOUND" })
    if (pack.content_locked || pack.published_at) throw new HttpError(409,
      "This version has been published. Copy it to a new draft version before changing its art or stickers.",
      { code: "PACK_VERSION_IMMUTABLE", current: { packKey, packVersion }, proposed: "Copy to a new version" })
  }

  async restore(adminPassword: string, changeId: string, confirm?: boolean) {
    this.requireAdmin(adminPassword)
    return restoreCatalogChange(this.db, "stickers", changeId, confirm)
  }

  async history(adminPassword: string, limit?: number) {
    this.requireAdmin(adminPassword)
    return catalogHistory(this.db, "stickers", limit)
  }

  private requireAdmin(candidate: string) {
    requireAdminPassword(this.adminPassword, candidate)
  }

  /** Signs every asset path in one storage call, keyed by path. */
  private async signAll(paths: string[]): Promise<Map<string, string>> {
    if (!this.storage) throw new HttpError(503, "Sticker storage is not configured")
    const unique = [...new Set(paths)]
    const urls = await this.storage.signedUrls(unique, SIGNED_URL_TTL_SECONDS)
    return new Map(unique.map((path, index) => [path, urls[index]]))
  }

  private async packRowToSummary(row: StickerPackRow, signed?: Map<string, string>): Promise<StickerPackSummary> {
    const urls = signed ?? await this.signAll(packPaths(row))
    return {
      packKey: row.pack_key,
      packVersion: Number(row.pack_version),
      title: row.title,
      description: row.description,
      coverThumbnailUrl: signedUrlFor(urls, row.cover_thumbnail_path),
      coverFullUrl: signedUrlFor(urls, row.cover_full_path),
      sortOrder: Number(row.sort_order),
      featured: Boolean(row.featured),
    }
  }

  private async packRowToAdminSummary(
    row: StickerPackRow & { sticker_count?: number },
    signed?: Map<string, string>,
  ): Promise<StickerPackAdminSummary> {
    const base = await this.packRowToSummary(row, signed)
    return {
      ...base,
      publishedAt: row.published_at,
      createdAt: row.created_at,
      updatedAt: row.updated_at,
      published: row.published_at != null,
      contentLocked: Boolean(row.content_locked || row.published_at),
      stickerCount: Number(row.sticker_count ?? 0),
    }
  }

  private async stickerRowToSummary(row: StickerRow, signed?: Map<string, string>): Promise<StickerSummary> {
    const urls = signed ?? await this.signAll(stickerPaths(row))
    return {
      stickerId: row.sticker_id,
      thumbnailUrl: signedUrlFor(urls, row.thumbnail_path),
      fullUrl: signedUrlFor(urls, row.full_path),
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
  if (!input.length || input.length > 20 * 1024 * 1024) throw new HttpError(400, "Sticker image must be between 1 byte and 20 MB", { code: "INVALID_CATALOG_INPUT" })
  try {
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
  } catch (error) {
    if (error instanceof HttpError) throw error
    throw new HttpError(400, "Sticker image could not be decoded. Supply a valid image.", { code: "INVALID_CATALOG_INPUT" })
  }
}

function packPaths(row: Pick<StickerPackRow, "cover_thumbnail_path" | "cover_full_path">): string[] {
  return [row.cover_thumbnail_path, row.cover_full_path]
}

function stickerPaths(row: Pick<StickerRow, "thumbnail_path" | "full_path">): string[] {
  return [row.thumbnail_path, row.full_path]
}

function signedUrlFor(urls: Map<string, string>, path: string): string {
  const url = urls.get(path)
  if (!url) throw new HttpError(502, `Unable to create signed sticker URL for ${path}`)
  return url
}
