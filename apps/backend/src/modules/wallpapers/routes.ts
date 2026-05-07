import type { FastifyInstance } from "fastify"
import { HttpError } from "../../infra/http/HttpError.js"
import { readWallpaperAdminPassword } from "../../infra/http/adminHeaders.js"
import type { WallpaperCatalogService } from "./service.js"

export function registerWallpaperRoutes(app: FastifyInstance, wallpaperCatalogService: WallpaperCatalogService): void {
  app.get("/wallpapers", async (request) => {
    const query = request.query as { cursor?: string; limit?: string }
    const limit = Number(query.limit ?? "24")
    return wallpaperCatalogService.listPublishedWallpapers({
      cursor: query.cursor,
      limit: Number.isFinite(limit) ? limit : 24,
    })
  })

  app.get("/admin/wallpapers", async (request) => {
    return wallpaperCatalogService.listWallpapersForAdmin({
      adminPassword: readWallpaperAdminPassword(request),
    })
  })

  app.post("/admin/wallpapers", async (request) => {
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
      throw new HttpError(400, "image is required")
    }
    const published = fields.published === "true"
    const sortOrder = Number(fields.sortOrder ?? "0")
    return wallpaperCatalogService.createWallpaper({
      adminPassword: readWallpaperAdminPassword(request),
      title: fields.title,
      description: fields.description,
      sortOrder: Number.isFinite(sortOrder) ? sortOrder : 0,
      published,
      filename: uploadedFile.filename,
      contentType: uploadedFile.mimetype,
      data: uploadedFile.data,
    })
  })

  app.patch("/admin/wallpapers/:wallpaperId", async (request) => {
    const params = request.params as { wallpaperId?: string }
    const body = request.body as {
      title?: string
      description?: string
      sortOrder?: number
      published?: boolean
    }
    return wallpaperCatalogService.updateWallpaper({
      adminPassword: readWallpaperAdminPassword(request),
      id: params.wallpaperId ?? "",
      title: body.title,
      description: body.description,
      sortOrder: body.sortOrder,
      published: body.published,
    })
  })

  app.delete("/admin/wallpapers/:wallpaperId", async (request, reply) => {
    const params = request.params as { wallpaperId?: string }
    await wallpaperCatalogService.deleteWallpaper({
      adminPassword: readWallpaperAdminPassword(request),
      id: params.wallpaperId ?? "",
    })
    reply.code(204).send()
  })
}

