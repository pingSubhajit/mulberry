import { createHmac, randomBytes, timingSafeEqual } from "node:crypto"
import { mkdir, readFile, rm, writeFile } from "node:fs/promises"
import path from "node:path"
import { HttpError } from "../http/HttpError.js"
import type { ReadableFileStorage } from "./fileStorage.js"

/**
 * Development-only storage on the backend's own disk, so local data never goes into the shared
 * Supabase buckets. URLs are relative to the backend, under `urlPrefix`, and signed with a
 * per-process key, like Supabase signed URLs. `label` names the files in errors, as "sticker".
 */
export class LocalFileStorage implements ReadableFileStorage {
  private readonly root: string

  constructor(
    root: string,
    readonly urlPrefix: string,
    private readonly label: string,
    private readonly secret: Buffer = randomBytes(32),
  ) {
    this.root = path.resolve(root)
  }

  async upload(assetPath: string, data: Buffer, _contentType?: string): Promise<void> {
    const file = this.resolve(assetPath)
    await mkdir(path.dirname(file), { recursive: true })
    await writeFile(file, data)
  }

  async remove(paths: string[]): Promise<void> {
    await Promise.all(paths.map((assetPath) => rm(this.resolve(assetPath), { force: true })))
  }

  async signedUrl(assetPath: string, expiresInSeconds: number): Promise<string> {
    this.resolve(assetPath)
    const expires = Math.floor(Date.now() / 1000) + expiresInSeconds
    const encoded = assetPath.split("/").map(encodeURIComponent).join("/")
    return `${this.urlPrefix}${encoded}?expires=${expires}&sig=${this.sign(assetPath, expires)}`
  }

  async signedUrls(assetPaths: string[], expiresInSeconds: number): Promise<string[]> {
    return Promise.all(assetPaths.map((assetPath) => this.signedUrl(assetPath, expiresInSeconds)))
  }

  async download(assetPath: string): Promise<Buffer> {
    try {
      return await readFile(this.resolve(assetPath))
    } catch (error) {
      if (error instanceof HttpError) throw error
      throw new HttpError(404, `${capitalized(this.label)} asset not found`)
    }
  }

  /** Reads a file through one of this storage's signed URLs. */
  async read(assetPath: string, expires: number, signature: string): Promise<Buffer> {
    if (!Number.isFinite(expires) || expires < Date.now() / 1000) {
      throw new HttpError(403, `${capitalized(this.label)} URL expired`)
    }
    const expected = Buffer.from(this.sign(assetPath, expires))
    const actual = Buffer.from(signature)
    if (expected.length !== actual.length || !timingSafeEqual(expected, actual)) {
      throw new HttpError(403, `Invalid ${this.label} URL signature`)
    }
    return this.download(assetPath)
  }

  private sign(assetPath: string, expires: number): string {
    return createHmac("sha256", this.secret).update(`${assetPath}\n${expires}`).digest("base64url")
  }

  private resolve(assetPath: string): string {
    const file = path.resolve(this.root, assetPath)
    if (!file.startsWith(this.root + path.sep)) throw new HttpError(400, `Invalid ${this.label} asset path`)
    return file
  }
}

function capitalized(value: string): string {
  return value.charAt(0).toUpperCase() + value.slice(1)
}
