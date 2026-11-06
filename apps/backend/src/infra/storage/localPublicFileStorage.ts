import { mkdir, readFile, rm, writeFile } from "node:fs/promises"
import path from "node:path"
import { HttpError } from "../http/HttpError.js"

const CONTENT_TYPES: Record<string, string> = {
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".png": "image/png",
  ".webp": "image/webp",
}

export interface LocalPublicAsset {
  data: Buffer
  contentType: string
}

/**
 * Development-only storage on the backend's own disk, standing in for a public Supabase bucket so
 * a local catalog never uploads into the shared project. URLs are relative to the backend, under
 * `urlPrefix`, and unsigned, like the public bucket. `label` names the files in errors, as "wallpaper".
 */
export class LocalPublicFileStorage {
  private readonly root: string

  constructor(
    root: string,
    readonly urlPrefix: string,
    private readonly label: string,
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

  publicUrl(assetPath: string): string {
    this.resolve(assetPath)
    return `${this.urlPrefix}${assetPath.split("/").map(encodeURIComponent).join("/")}`
  }

  async download(assetPath: string): Promise<Buffer> {
    return (await this.read(assetPath)).data
  }

  async read(assetPath: string): Promise<LocalPublicAsset> {
    const file = this.resolve(assetPath)
    const contentType = CONTENT_TYPES[path.extname(file).toLowerCase()]
    if (!contentType) throw new HttpError(404, `${capitalized(this.label)} asset not found`)
    try {
      return { data: await readFile(file), contentType }
    } catch {
      throw new HttpError(404, `${capitalized(this.label)} asset not found`)
    }
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
