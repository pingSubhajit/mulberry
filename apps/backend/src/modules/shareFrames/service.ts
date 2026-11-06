import { randomUUID } from "node:crypto"
import type { Database } from "../../infra/db/database.js"
import { HttpError } from "../../infra/http/HttpError.js"
import { requireAdminPassword } from "../../infra/http/adminAuth.js"
import { catalogHistory, catalogMutation, restoreCatalogChange } from "../catalog/history.js"
import { checkAlphaInsideQuad, decodeShareFrameImage, renderShareFrameVariants } from "./image.js"
import { isValidFrameKey, validateShareFrameLayout, type ShareFrameLayout } from "./layout.js"
import { normalizeShareFrameRow, type ShareFrameRow } from "./rows.js"
import type { ShareFrameStorage } from "./storage.js"

/** One published frame as apps receive it from `GET /share-frames`. */
export interface ShareFrameCatalogItem {
  key: string
  version: number
  title: string
  sortOrder: number
  /** Inclusive local calendar dates, `YYYY-MM-DD`; null leaves that side open. */
  availableFrom: string | null
  availableUntil: string | null
  frameUrl: string
  thumbnailUrl: string
  width: number
  height: number
  layout: ShareFrameLayout
}

/** Every version, drafts included, as the admin sees it. */
export interface ShareFrameAdminItem extends ShareFrameCatalogItem {
  message?: string
  originalUrl: string
  published: boolean
  contentLocked: boolean
  publishedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface ShareFrameImageUpload {
  filename: string
  contentType: string
  data: Buffer
}

export interface CreateShareFrameInput {
  adminPassword?: string
  frameKey: string
  frameVersion: number
  title?: string
  sortOrder?: number
  availableFrom?: string | null
  availableUntil?: string | null
  published?: boolean
  /** The layout JSON, as sent. */
  layout?: string
  image?: ShareFrameImageUpload
}

export interface UpdateShareFrameInput {
  adminPassword?: string
  frameKey: string
  frameVersion: number
  title?: string
  sortOrder?: number
  availableFrom?: string | null
  availableUntil?: string | null
  published?: boolean
}

export interface ReplaceShareFrameContentInput {
  adminPassword?: string
  frameKey: string
  frameVersion: number
  layout?: string
  image?: ShareFrameImageUpload
}

const MAX_TITLE_LENGTH = 80

function invalid(message: string): HttpError {
  return new HttpError(400, message, { code: "INVALID_CATALOG_INPUT" })
}

/** Checks a `(frameKey, frameVersion)` identity from a route or form. */
export function requireFrameIdentity(frameKey: string, frameVersion: number): void {
  if (!isValidFrameKey(frameKey)) {
    throw invalid("frameKey must be lowercase letters and digits joined by single hyphens, as spring-kittens, at most 64 characters")
  }
  if (!Number.isSafeInteger(frameVersion) || frameVersion < 1 || frameVersion > 2147483647) {
    throw invalid("frameVersion must be a positive 32-bit integer")
  }
}

/** A real calendar date written `YYYY-MM-DD`, or null. Undefined means "not supplied". */
export function parseCalendarDate(value: string | null | undefined, field: string): string | null | undefined {
  if (value === undefined || value === null) return value
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (match) {
    const [year, month, day] = match.slice(1).map(Number)
    const date = new Date(Date.UTC(year, month - 1, day))
    if (date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day) return value
  }
  throw invalid(`${field} must be a real calendar date written YYYY-MM-DD, or null`)
}

function requireWindow(from: string | null, until: string | null): void {
  if (from && until && from > until) throw invalid(`availableFrom ${from} must not be after availableUntil ${until}`)
}

function requireTitle(raw: string | undefined): string {
  const title = (raw ?? "").trim()
  if (!title) throw invalid("title is required")
  if (title.length > MAX_TITLE_LENGTH) throw invalid(`title must be at most ${MAX_TITLE_LENGTH} characters`)
  return title
}

function timestamp(value: string | Date | null): string | null {
  if (value === null) return null
  return new Date(value).toISOString()
}

export class ShareFrameCatalogService {
  constructor(
    private readonly db: Database,
    private readonly storage: ShareFrameStorage | null,
    private readonly adminPassword?: string,
    private readonly inMutation = false,
  ) {}

  /** Every published frame, in or out of its window: apps filter by their own local date. */
  async listPublished(): Promise<{ items: ShareFrameCatalogItem[] }> {
    const rows = await this.db.query<ShareFrameRow>(`
      SELECT * FROM share_frames
      WHERE published_at IS NOT NULL
      ORDER BY sort_order ASC, frame_key ASC, frame_version DESC
    `)
    return { items: rows.rows.map((row) => this.toCatalogItem(normalizeShareFrameRow(row))) }
  }

  async listForAdmin(adminPassword?: string): Promise<{ items: ShareFrameAdminItem[] }> {
    this.requireAdmin(adminPassword)
    const rows = await this.db.query<ShareFrameRow>(`
      SELECT * FROM share_frames
      ORDER BY sort_order ASC, frame_key ASC, frame_version DESC
    `)
    return { items: rows.rows.map((row) => this.toAdminItem(normalizeShareFrameRow(row))) }
  }

  async getForAdmin(adminPassword: string | undefined, frameKey: string, frameVersion: number): Promise<ShareFrameAdminItem> {
    this.requireAdmin(adminPassword)
    requireFrameIdentity(frameKey, frameVersion)
    return this.toAdminItem(await this.requireRow(frameKey, frameVersion))
  }

  async create(input: CreateShareFrameInput): Promise<ShareFrameAdminItem> {
    this.requireAdmin(input.adminPassword)
    requireFrameIdentity(input.frameKey, input.frameVersion)
    if (!this.inMutation) {
      return catalogMutation(this.db, "share-frames", input.frameKey, input.frameVersion, "createShareFrame",
        (db) => this.scoped(db).create(input))
    }
    const storage = this.requireStorage()
    const title = requireTitle(input.title)
    const availableFrom = parseCalendarDate(input.availableFrom, "availableFrom") ?? null
    const availableUntil = parseCalendarDate(input.availableUntil, "availableUntil") ?? null
    requireWindow(availableFrom, availableUntil)
    if (input.layout === undefined) throw invalid("layout is required: send the layout JSON as a form field")
    if (!input.image) throw invalid("image is required: send the frame PNG as the image file")

    const existing = await this.db.query("SELECT frame_key FROM share_frames WHERE frame_key = $1 AND frame_version = $2", [input.frameKey, input.frameVersion])
    if (existing.rows.length) {
      throw new HttpError(409, "This frame version exists. Use PATCH for metadata, or copy it to a new version.", { code: "FRAME_VERSION_EXISTS" })
    }
    await this.requireUnusedVersion(input.frameKey, input.frameVersion)

    const layout = validateShareFrameLayout(input.layout, input.frameKey)
    const decoded = await decodeShareFrameImage(input.image.data)
    checkAlphaInsideQuad(decoded, layout.content.quad)
    const paths = await this.storeImage(storage, input.frameKey, input.frameVersion, input.image.data)

    const published = input.published === true
    const rows = await this.db.query<ShareFrameRow>(`
      INSERT INTO share_frames (
        frame_key, frame_version, title, sort_order, available_from, available_until,
        original_path, full_path, thumbnail_path, width, height, layout,
        published_at, content_locked, created_at, updated_at
      ) VALUES ($1, $2, $3, $4, $5::date, $6::date, $7, $8, $9, $10, $11, $12::jsonb, $13::timestamptz, $14, NOW(), NOW())
      RETURNING *
    `, [
      input.frameKey, input.frameVersion, title, input.sortOrder ?? 0, availableFrom, availableUntil,
      paths.original, paths.full, paths.thumbnail, decoded.width, decoded.height, JSON.stringify(layout),
      published ? new Date().toISOString() : null, published,
    ])
    return this.toAdminItem(normalizeShareFrameRow(rows.rows[0]))
  }

  async update(input: UpdateShareFrameInput): Promise<ShareFrameAdminItem> {
    this.requireAdmin(input.adminPassword)
    requireFrameIdentity(input.frameKey, input.frameVersion)
    if (!this.inMutation) {
      return catalogMutation(this.db, "share-frames", input.frameKey, input.frameVersion, "updateShareFrame",
        (db) => this.scoped(db).update(input))
    }
    const current = await this.requireRow(input.frameKey, input.frameVersion)
    const title = input.title === undefined ? current.title : requireTitle(input.title)
    const from = parseCalendarDate(input.availableFrom, "availableFrom")
    const until = parseCalendarDate(input.availableUntil, "availableUntil")
    const availableFrom = from === undefined ? current.available_from : from
    const availableUntil = until === undefined ? current.available_until : until
    requireWindow(availableFrom, availableUntil)
    if (input.sortOrder !== undefined && !Number.isSafeInteger(input.sortOrder)) throw invalid("sortOrder must be an integer")

    const rows = await this.db.query<ShareFrameRow>(`
      UPDATE share_frames
      SET title = $3,
        sort_order = $4,
        available_from = $5::date,
        available_until = $6::date,
        content_locked = content_locked OR COALESCE($7::boolean, FALSE),
        published_at = CASE
          WHEN $7::boolean IS NULL THEN published_at
          WHEN $7::boolean THEN COALESCE(published_at, NOW())
          ELSE NULL
        END,
        updated_at = NOW()
      WHERE frame_key = $1 AND frame_version = $2
      RETURNING *
    `, [
      input.frameKey, input.frameVersion, title, input.sortOrder ?? current.sort_order,
      availableFrom, availableUntil, input.published ?? null,
    ])
    return this.toAdminItem(normalizeShareFrameRow(rows.rows[0]))
  }

  /** Replaces a draft's image, layout or both. Published (or once-published) versions refuse. */
  async replaceContent(input: ReplaceShareFrameContentInput): Promise<ShareFrameAdminItem> {
    this.requireAdmin(input.adminPassword)
    requireFrameIdentity(input.frameKey, input.frameVersion)
    if (!this.inMutation) {
      return catalogMutation(this.db, "share-frames", input.frameKey, input.frameVersion, "replaceShareFrameContent",
        (db) => this.scoped(db).replaceContent(input))
    }
    const storage = this.requireStorage()
    if (input.layout === undefined && !input.image) throw invalid("Send a new image, a new layout, or both")
    const current = await this.requireRow(input.frameKey, input.frameVersion)
    if (current.content_locked || current.published_at) {
      throw new HttpError(409,
        "This frame version has been published, so its image and layout are fixed. Copy it to a new version and edit that draft.",
        { code: "FRAME_VERSION_IMMUTABLE", current: { key: current.frame_key, version: current.frame_version }, proposed: "Copy to a new version" })
    }

    const layout = input.layout === undefined ? current.layout : validateShareFrameLayout(input.layout, input.frameKey)
    const imageData = input.image ? input.image.data : await storage.download(current.original_path)
    const decoded = await decodeShareFrameImage(imageData)
    checkAlphaInsideQuad(decoded, layout.content.quad)
    const paths = input.image
      ? await this.storeImage(storage, input.frameKey, input.frameVersion, input.image.data)
      : { original: current.original_path, full: current.full_path, thumbnail: current.thumbnail_path }

    const rows = await this.db.query<ShareFrameRow>(`
      UPDATE share_frames
      SET original_path = $3, full_path = $4, thumbnail_path = $5, width = $6, height = $7,
        layout = $8::jsonb, updated_at = NOW()
      WHERE frame_key = $1 AND frame_version = $2
      RETURNING *
    `, [input.frameKey, input.frameVersion, paths.original, paths.full, paths.thumbnail, decoded.width, decoded.height, JSON.stringify(layout)])
    return this.toAdminItem(normalizeShareFrameRow(rows.rows[0]))
  }

  /** Copies a version, sharing its immutable files, into an unpublished, unlocked draft. */
  async copy(input: { adminPassword?: string; frameKey: string; frameVersion: number; newVersion?: number }): Promise<ShareFrameAdminItem> {
    this.requireAdmin(input.adminPassword)
    requireFrameIdentity(input.frameKey, input.frameVersion)
    const newVersion = input.newVersion
    if (newVersion === undefined || !Number.isSafeInteger(newVersion) || newVersion <= input.frameVersion || newVersion > 2147483647) {
      throw invalid("newVersion must be an integer greater than the source version")
    }
    if (!this.inMutation) {
      return catalogMutation(this.db, "share-frames", input.frameKey, newVersion, "copyShareFrame",
        (db) => this.scoped(db).copy(input))
    }
    const existing = await this.db.query("SELECT frame_key FROM share_frames WHERE frame_key = $1 AND frame_version = $2", [input.frameKey, newVersion])
    if (existing.rows.length) {
      throw new HttpError(409, `Version ${newVersion} exists. Choose a version above every known version of ${input.frameKey}.`, { code: "FRAME_VERSION_EXISTS" })
    }
    await this.requireUnusedVersion(input.frameKey, newVersion)
    const source = await this.db.query<ShareFrameRow>("SELECT * FROM share_frames WHERE frame_key = $1 AND frame_version = $2 FOR UPDATE", [input.frameKey, input.frameVersion])
    if (!source.rows[0]) throw new HttpError(404, "Source frame version not found", { code: "FRAME_NOT_FOUND" })
    await this.db.query(`
      INSERT INTO share_frames (
        frame_key, frame_version, title, sort_order, available_from, available_until,
        original_path, full_path, thumbnail_path, width, height, layout, published_at, content_locked
      )
      SELECT frame_key, $3::integer, title, sort_order, available_from, available_until,
        original_path, full_path, thumbnail_path, width, height, layout, NULL::timestamptz, FALSE
      FROM share_frames WHERE frame_key = $1 AND frame_version = $2
    `, [input.frameKey, input.frameVersion, newVersion])
    return this.toAdminItem(await this.requireRow(input.frameKey, newVersion))
  }

  async delete(input: { adminPassword?: string; frameKey: string; frameVersion: number; confirm?: boolean }): Promise<{ message: string }> {
    this.requireAdmin(input.adminPassword)
    requireFrameIdentity(input.frameKey, input.frameVersion)
    if (!this.inMutation) {
      return catalogMutation(this.db, "share-frames", input.frameKey, input.frameVersion, "deleteShareFrame",
        (db) => this.scoped(db).delete(input))
    }
    const row = await this.requireRow(input.frameKey, input.frameVersion)
    if (input.confirm !== true) {
      throw new HttpError(409,
        "Deleting this frame version removes it from every app's catalog. Hide it with PATCH published:false instead. To delete, resend with confirm:true after explicit approval.",
        { code: "CONFIRMATION_REQUIRED", current: { key: row.frame_key, version: row.frame_version, title: row.title, published: row.published_at !== null }, proposed: null })
    }
    await this.db.query("DELETE FROM share_frames WHERE frame_key = $1 AND frame_version = $2", [input.frameKey, input.frameVersion])
    return { message: "Frame version deleted. Assets and history retained for recovery." }
  }

  async history(adminPassword: string | undefined, limit?: number) {
    this.requireAdmin(adminPassword)
    return catalogHistory(this.db, "share-frames", limit)
  }

  async restore(adminPassword: string | undefined, changeId: string, confirm?: boolean) {
    this.requireAdmin(adminPassword)
    return restoreCatalogChange(this.db, "share-frames", changeId, confirm)
  }

  private scoped(db: Database): ShareFrameCatalogService {
    return new ShareFrameCatalogService(db, this.storage, this.adminPassword, true)
  }

  private requireAdmin(candidate: string | undefined): void {
    requireAdminPassword(this.adminPassword, candidate)
  }

  private requireStorage(): ShareFrameStorage {
    if (!this.storage) throw new HttpError(503, "Share frame storage is not configured", { code: "CATALOG_UNAVAILABLE" })
    return this.storage
  }

  private async requireRow(frameKey: string, frameVersion: number): Promise<ShareFrameRow> {
    const rows = await this.db.query<ShareFrameRow>("SELECT * FROM share_frames WHERE frame_key = $1 AND frame_version = $2", [frameKey, frameVersion])
    const row = rows.rows[0]
    if (!row) throw new HttpError(404, `Frame ${frameKey} version ${frameVersion} not found`, { code: "FRAME_NOT_FOUND" })
    return normalizeShareFrameRow(row)
  }

  /** History keeps a used identity reserved after deletion; restoring the deletion is the way back. */
  private async requireUnusedVersion(frameKey: string, frameVersion: number): Promise<void> {
    const used = await this.db.query(
      "SELECT id FROM catalog_admin.admin_catalog_changes WHERE content_type = 'share-frames' AND content_key = $1 LIMIT 1",
      [`${frameKey}:${frameVersion}`])
    if (used.rows.length) {
      throw new HttpError(409, "This frame version was used before. Choose a new version, or restore its deletion from history.", { code: "FRAME_VERSION_EXISTS" })
    }
  }

  /** Writes the original and both WebP variants under a fresh UUID, so no URL ever changes content. */
  private async storeImage(storage: ShareFrameStorage, frameKey: string, frameVersion: number, data: Buffer) {
    const variants = await renderShareFrameVariants(data)
    const base = `share-frames/${frameKey}/${frameVersion}/${randomUUID()}`
    const paths = { original: `${base}/original.png`, full: `${base}/full.webp`, thumbnail: `${base}/thumb.webp` }
    await storage.upload(paths.original, data, "image/png")
    await storage.upload(paths.full, variants.full, "image/webp")
    await storage.upload(paths.thumbnail, variants.thumbnail, "image/webp")
    return paths
  }

  private toCatalogItem(row: ShareFrameRow): ShareFrameCatalogItem {
    const storage = this.requireStorage()
    return {
      key: row.frame_key,
      version: row.frame_version,
      title: row.title,
      sortOrder: row.sort_order,
      availableFrom: row.available_from,
      availableUntil: row.available_until,
      frameUrl: storage.publicUrl(row.full_path),
      thumbnailUrl: storage.publicUrl(row.thumbnail_path),
      width: row.width,
      height: row.height,
      layout: row.layout,
    }
  }

  private toAdminItem(row: ShareFrameRow): ShareFrameAdminItem {
    return {
      ...this.toCatalogItem(row),
      originalUrl: this.requireStorage().publicUrl(row.original_path),
      published: row.published_at !== null,
      contentLocked: row.content_locked || row.published_at !== null,
      publishedAt: timestamp(row.published_at),
      createdAt: timestamp(row.created_at) ?? "",
      updatedAt: timestamp(row.updated_at) ?? "",
    }
  }
}
