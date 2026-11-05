import { createClient, type SupabaseClient } from "@supabase/supabase-js"
import { HttpError } from "../http/HttpError.js"

/** Files kept in a bucket and handed out through signed, expiring URLs. */
export interface FileStorage {
  upload(path: string, data: Buffer, contentType: string): Promise<void>
  remove(paths: string[]): Promise<void>
  signedUrl(path: string, expiresInSeconds: number): Promise<string>
  /** Signs every path in one round trip, in the order given. */
  signedUrls(paths: string[], expiresInSeconds: number): Promise<string[]>
}

/** Storage the backend also reads back itself, as when it serves a file's bytes. */
export interface ReadableFileStorage extends FileStorage {
  download(path: string): Promise<Buffer>
}

/** A Supabase Storage bucket, reached with the service-role key. `label` names its files in errors. */
export class SupabaseFileStorage implements ReadableFileStorage {
  private readonly client: SupabaseClient

  constructor(
    supabaseUrl: string,
    serviceRoleKey: string,
    private readonly bucket: string,
    private readonly label: string,
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
      throw new HttpError(502, `Unable to upload ${this.label}: ${error.message}`)
    }
  }

  async remove(paths: string[]): Promise<void> {
    if (paths.length === 0) return
    const { error } = await this.client.storage.from(this.bucket).remove(paths)
    if (error) {
      throw new HttpError(502, `Unable to delete ${this.label}: ${error.message}`)
    }
  }

  async signedUrl(path: string, expiresInSeconds: number): Promise<string> {
    const { data, error } = await this.client.storage
      .from(this.bucket)
      .createSignedUrl(path, expiresInSeconds)
    if (error || !data?.signedUrl) {
      throw new HttpError(502, `Unable to create signed ${this.label} URL: ${error?.message ?? "unknown error"}`)
    }
    return data.signedUrl
  }

  async signedUrls(paths: string[], expiresInSeconds: number): Promise<string[]> {
    if (paths.length === 0) return []
    const { data, error } = await this.client.storage
      .from(this.bucket)
      .createSignedUrls(paths, expiresInSeconds)
    if (error || !data) {
      throw new HttpError(502, `Unable to create signed ${this.label} URLs: ${error?.message ?? "unknown error"}`)
    }
    const byPath = new Map(data.map((item) => [item.path, item.signedUrl]))
    return paths.map((path, index) => {
      const url = byPath.get(path) ?? data[index]?.signedUrl
      if (!url) throw new HttpError(502, `Unable to create signed ${this.label} URL for ${path}`)
      return url
    })
  }

  async download(path: string): Promise<Buffer> {
    const { data, error } = await this.client.storage.from(this.bucket).download(path)
    if (error || !data) {
      throw new HttpError(502, `Unable to read ${this.label}: ${error?.message ?? "unknown error"}`)
    }
    return Buffer.from(await data.arrayBuffer())
  }

  /**
   * Creates the bucket as private when it doesn't exist yet. Safe to run on every start, and from
   * two servers at once: a bucket that already exists counts as done.
   */
  async ensurePrivateBucket(): Promise<"exists" | "created"> {
    const existing = await this.client.storage.getBucket(this.bucket)
    if (existing.data) {
      if (existing.data.public) {
        console.warn(`[storage] bucket ${this.bucket} is public; ${this.label} files are meant to be private`)
      }
      return "exists"
    }
    const created = await this.client.storage.createBucket(this.bucket, { public: false })
    if (created.error && !isAlreadyExists(created.error)) {
      throw new Error(`Unable to create bucket ${this.bucket}: ${created.error.message}`)
    }
    return created.error ? "exists" : "created"
  }
}

/** Storage answers a duplicate bucket with 409, or with a 400 whose code is 409, depending on version. */
function isAlreadyExists(error: { message: string; statusCode?: string; status?: number }): boolean {
  return error.statusCode === "409" || error.status === 409 || /already exists/i.test(error.message)
}
