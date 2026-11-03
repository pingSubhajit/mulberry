import { readRequiredImageUpload } from "../../infra/http/uploads.js"
import { multipartNumber, multipartBoolean, validateMultipartFields } from "../catalog/adminRoutes.js"
import type { FastifyInstance } from "fastify"
import { HttpError } from "../../infra/http/HttpError.js"
import { readAdminPassword } from "../../infra/http/adminAuth.js"
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
      adminPassword: readAdminPassword(request),
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

    validateMultipartFields(fields, ["title", "description", "sortOrder", "published"])
    if (!uploadedFile) {
      throw new HttpError(400, "image is required")
    }
    const published = multipartBoolean(fields.published)
    const sortOrder = multipartNumber(fields.sortOrder, 0)
    return wallpaperCatalogService.createWallpaper({
      adminPassword: readAdminPassword(request),
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
    const body = (request.body ?? {}) as {
      title?: string
      description?: string
      sortOrder?: number
      published?: boolean
    }
    return wallpaperCatalogService.updateWallpaper({
      adminPassword: readAdminPassword(request),
      id: params.wallpaperId ?? "",
      title: body.title,
      description: body.description,
      sortOrder: body.sortOrder,
      published: body.published,
    })
  })

  app.delete("/admin/wallpapers/:wallpaperId", async (request) => {
    const params = request.params as { wallpaperId?: string }
    await wallpaperCatalogService.deleteWallpaper({
      adminPassword: readAdminPassword(request),
      id: params.wallpaperId ?? "",
      confirm: (request.body as { confirm?: boolean } | undefined)?.confirm,
    })
    return { message: "Wallpaper deleted. Assets retained for recovery." }
  })
  app.get("/admin/wallpapers/history", async (request) => {
    const query = request.query as { limit?: string }
    return wallpaperCatalogService.history(readAdminPassword(request) ?? "", query.limit === undefined ? undefined : Number(query.limit))
  })

  app.put("/admin/wallpapers/:wallpaperId/image", async (request) => {
    const params = request.params as { wallpaperId: string }
    const upload = await readRequiredImageUpload(request)
    return wallpaperCatalogService.replaceWallpaperImage({ ...upload, adminPassword: readAdminPassword(request), id: params.wallpaperId })
  })

  app.post("/admin/wallpapers/history/:changeId/restore", async (request) => {
    const params = request.params as { changeId: string }
    return wallpaperCatalogService.restore(readAdminPassword(request) ?? "", params.changeId,
      (request.body as { confirm?: boolean } | undefined)?.confirm)
  })

}

