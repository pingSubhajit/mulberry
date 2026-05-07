import type { FastifyInstance } from "fastify"
import { HttpError } from "../../infra/http/HttpError.js"
import { requireBearerToken } from "../../infra/http/auth.js"
import { readStickerAdminPassword } from "../../infra/http/adminHeaders.js"
import { readRequiredImageUpload } from "../../infra/http/uploads.js"
import type { StickerCatalogService } from "./service.js"

export function registerStickerRoutes(app: FastifyInstance, stickerCatalogService: StickerCatalogService): void {
  app.get("/stickers/packs", async (request) => {
    requireBearerToken(request)
    return stickerCatalogService.listPublishedStickerPacks()
  })

  app.get("/stickers/packs/:packKey", async (request) => {
    requireBearerToken(request)
    const params = request.params as { packKey?: string }
    const query = request.query as { version?: string }
    const version = query.version ? Number(query.version) : undefined
    return stickerCatalogService.getPublishedStickerPackDetail(
      params.packKey ?? "",
      Number.isFinite(version) ? version : undefined,
    )
  })

  app.get("/stickers/assets/url", async (request) => {
    requireBearerToken(request)
    const query = request.query as {
      packKey?: string
      version?: string
      stickerId?: string
      variant?: string
    }
    const version = Number(query.version ?? "")
    const variant = query.variant === "thumbnail" ? "thumbnail" : "full"
    return stickerCatalogService.getStickerAssetSignedUrl({
      packKey: query.packKey ?? "",
      packVersion: Number.isFinite(version) ? version : 0,
      stickerId: query.stickerId ?? "",
      variant,
    })
  })

  app.get("/admin/stickers/packs", async (request) => {
    return stickerCatalogService.listStickerPacksForAdmin({
      adminPassword: readStickerAdminPassword(request) ?? "",
    })
  })

  app.get("/admin/stickers/packs/:packKey/:packVersion", async (request) => {
    const params = request.params as { packKey?: string; packVersion?: string }
    const packVersion = Number(params.packVersion ?? "0")
    return stickerCatalogService.getStickerPackDetailForAdmin({
      adminPassword: readStickerAdminPassword(request) ?? "",
      packKey: params.packKey ?? "",
      packVersion: Number.isFinite(packVersion) ? packVersion : 0,
    })
  })

  app.post("/admin/stickers/packs", async (request) => {
    const fields: Record<string, string> = {}
    let uploadedFile: {
      filename: string
      mimetype: string
      data: Buffer
    } | null = null

    for await (const part of request.parts()) {
      if (part.type === "file") {
        uploadedFile = {
          filename: part.filename,
          mimetype: part.mimetype,
          data: await part.toBuffer(),
        }
      } else if (typeof part.value === "string") {
        fields[part.fieldname] = part.value
      }
    }

    if (!uploadedFile) {
      throw new HttpError(400, "cover image is required")
    }

    const published = fields.published === "true"
    const featured = fields.featured === "true"
    const sortOrder = Number(fields.sortOrder ?? "0")
    const packVersion = Number(fields.packVersion ?? "1")
    return stickerCatalogService.createStickerPackVersion({
      adminPassword: readStickerAdminPassword(request) ?? "",
      packKey: fields.packKey ?? "",
      packVersion: Number.isFinite(packVersion) ? packVersion : 1,
      title: fields.title ?? "",
      description: fields.description ?? "",
      sortOrder: Number.isFinite(sortOrder) ? sortOrder : 0,
      featured,
      published,
      coverFilename: uploadedFile.filename,
      coverContentType: uploadedFile.mimetype,
      coverData: uploadedFile.data,
    })
  })

  app.patch("/admin/stickers/packs/:packKey/:packVersion", async (request) => {
    const params = request.params as { packKey?: string; packVersion?: string }
    const body = request.body as Partial<{
      title: string
      description: string
      sortOrder: number
      featured: boolean
      published: boolean
    }>
    const packVersion = Number(params.packVersion ?? "0")
    return stickerCatalogService.updateStickerPackVersionMetadata({
      adminPassword: readStickerAdminPassword(request) ?? "",
      packKey: params.packKey ?? "",
      packVersion: Number.isFinite(packVersion) ? packVersion : 0,
      title: body.title,
      description: body.description,
      sortOrder: body.sortOrder,
      featured: body.featured,
      published: body.published,
    })
  })

  app.put("/admin/stickers/packs/:packKey/:packVersion/cover", async (request) => {
    const params = request.params as { packKey?: string; packVersion?: string }
    const upload = await readRequiredImageUpload(request)
    const packVersion = Number(params.packVersion ?? "0")
    return stickerCatalogService.updateStickerPackVersionCoverForAdmin({
      adminPassword: readStickerAdminPassword(request) ?? "",
      packKey: params.packKey ?? "",
      packVersion: Number.isFinite(packVersion) ? packVersion : 0,
      filename: upload.filename,
      contentType: upload.contentType,
      data: upload.data,
    })
  })

  app.post("/admin/stickers/packs/:packKey/:packVersion/stickers", async (request) => {
    const params = request.params as { packKey?: string; packVersion?: string }
    const fields: Record<string, string> = {}
    let uploadedFile: {
      filename: string
      mimetype: string
      data: Buffer
    } | null = null

    for await (const part of request.parts()) {
      if (part.type === "file") {
        uploadedFile = {
          filename: part.filename,
          mimetype: part.mimetype,
          data: await part.toBuffer(),
        }
      } else if (typeof part.value === "string") {
        fields[part.fieldname] = part.value
      }
    }

    if (!uploadedFile) {
      throw new HttpError(400, "sticker image is required")
    }

    const sortOrder = Number(fields.sortOrder ?? "0")
    const packVersion = Number(params.packVersion ?? "0")
    return stickerCatalogService.uploadSticker({
      adminPassword: readStickerAdminPassword(request) ?? "",
      packKey: params.packKey ?? "",
      packVersion: Number.isFinite(packVersion) ? packVersion : 0,
      stickerId: fields.stickerId ?? "",
      sortOrder: Number.isFinite(sortOrder) ? sortOrder : 0,
      filename: uploadedFile.filename,
      contentType: uploadedFile.mimetype,
      data: uploadedFile.data,
    })
  })

  app.delete("/admin/stickers/packs/:packKey/:packVersion", async (request, reply) => {
    const params = request.params as { packKey?: string; packVersion?: string }
    const packVersion = Number(params.packVersion ?? "0")
    await stickerCatalogService.deleteStickerPackVersionForAdmin({
      adminPassword: readStickerAdminPassword(request) ?? "",
      packKey: params.packKey ?? "",
      packVersion: Number.isFinite(packVersion) ? packVersion : 0,
    })
    reply.code(204).send()
  })
}

