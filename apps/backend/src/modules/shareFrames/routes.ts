import type { FastifyInstance, FastifyRequest } from "fastify"
import { HttpError } from "../../infra/http/HttpError.js"
import { readAdminPassword } from "../../infra/http/adminAuth.js"
import { requireBearerToken } from "../../infra/http/auth.js"
import { multipartBoolean, multipartNumber, validateMultipartFields } from "../catalog/adminRoutes.js"
import { MAX_SHARE_FRAME_BYTES } from "./image.js"
import type { ShareFrameCatalogService, ShareFrameImageUpload } from "./service.js"
import { absolutizeLocalShareFrameUrls, LOCAL_SHARE_FRAME_ASSET_PREFIX, LocalShareFrameStorage, type ShareFrameStorage } from "./storage.js"

const SHARE_FRAME_ROUTE = /^\/(admin\/)?share-frames(\/|\?|$)/

interface FrameParams {
  frameKey: string
  frameVersion: string
}

interface MultipartFrameUpload {
  fields: Record<string, string>
  layout?: string
  image?: ShareFrameImageUpload
}

/**
 * Reads a frame form: text fields, one `image` file and the layout, either as a `layout` field
 * or as a `layout` file (as `curl -F layout=@layout.json` sends it).
 */
async function readFrameUpload(request: FastifyRequest): Promise<MultipartFrameUpload> {
  const upload: MultipartFrameUpload = { fields: {} }
  for await (const part of request.parts({ limits: { files: 2, fileSize: MAX_SHARE_FRAME_BYTES } })) {
    if (part.type === "file") {
      if (part.fieldname === "image") {
        if (upload.image) throw new HttpError(400, "Send one image file", { code: "INVALID_CATALOG_INPUT" })
        upload.image = { filename: part.filename, contentType: part.mimetype, data: await part.toBuffer() }
      } else if (part.fieldname === "layout") {
        if (upload.layout !== undefined) throw new HttpError(400, "Send one layout", { code: "INVALID_CATALOG_INPUT" })
        upload.layout = (await part.toBuffer()).toString("utf8")
      } else {
        throw new HttpError(400, `Unknown file field ${part.fieldname}. Send image and layout.`, { code: "INVALID_CATALOG_INPUT" })
      }
    } else if (part.fieldname === "layout") {
      if (upload.layout !== undefined) throw new HttpError(400, "Send one layout", { code: "INVALID_CATALOG_INPUT" })
      upload.layout = String(part.value)
    } else {
      upload.fields[part.fieldname] = String(part.value)
    }
  }
  return upload
}

/** Multipart dates: empty means none. */
function multipartDate(value: string | undefined): string | null | undefined {
  if (value === undefined) return undefined
  return value.trim() === "" ? null : value.trim()
}

function routeVersion(raw: string): number {
  const version = raw.trim() ? Number(raw) : NaN
  if (!Number.isSafeInteger(version) || version < 1 || version > 2147483647) {
    throw new HttpError(400, "The version in the path must be a positive 32-bit integer", { code: "INVALID_CATALOG_INPUT" })
  }
  return version
}

export function registerShareFrameRoutes(
  app: FastifyInstance,
  service: ShareFrameCatalogService,
  storage: ShareFrameStorage | null = null,
  authenticate: (accessToken: string) => Promise<unknown> = async () => undefined,
): void {
  if (storage instanceof LocalShareFrameStorage) {
    // Development only: give relative local asset URLs the host the client called.
    app.addHook("preSerialization", async (request, _reply, payload) => {
      if (!SHARE_FRAME_ROUTE.test(request.url)) return payload
      return absolutizeLocalShareFrameUrls(payload, `${request.protocol}://${request.headers.host ?? "localhost"}`)
    })

    app.get(`${LOCAL_SHARE_FRAME_ASSET_PREFIX}*`, async (request, reply) => {
      const params = request.params as { "*"?: string }
      const asset = await storage.read(params["*"] ?? "")
      reply.header("cache-control", "public, max-age=31536000, immutable").type(asset.contentType)
      return asset.data
    })
  }

  app.get("/share-frames", async (request) => {
    await authenticate(requireBearerToken(request))
    return service.listPublished()
  })

  app.get("/admin/share-frames", async (request) => service.listForAdmin(readAdminPassword(request)))

  app.get("/admin/share-frames/history", async (request) => {
    const query = request.query as { limit?: string }
    return service.history(readAdminPassword(request), query.limit === undefined ? undefined : Number(query.limit))
  })

  app.post("/admin/share-frames/history/:changeId/restore", async (request) => {
    const params = request.params as { changeId: string }
    return service.restore(readAdminPassword(request), params.changeId, (request.body as { confirm?: boolean } | undefined)?.confirm)
  })

  app.get("/admin/share-frames/:frameKey/:frameVersion", async (request) => {
    const params = request.params as FrameParams
    return service.getForAdmin(readAdminPassword(request), params.frameKey, routeVersion(params.frameVersion))
  })

  app.post("/admin/share-frames", async (request) => {
    const upload = await readFrameUpload(request)
    const { fields } = upload
    validateMultipartFields(fields, ["frameKey", "frameVersion", "title", "sortOrder", "availableFrom", "availableUntil", "published"])
    return service.create({
      adminPassword: readAdminPassword(request),
      frameKey: (fields.frameKey ?? "").trim(),
      frameVersion: multipartNumber(fields.frameVersion, 1),
      title: fields.title,
      sortOrder: multipartNumber(fields.sortOrder, 0),
      availableFrom: multipartDate(fields.availableFrom),
      availableUntil: multipartDate(fields.availableUntil),
      published: multipartBoolean(fields.published),
      layout: upload.layout,
      image: upload.image,
    })
  })

  app.patch("/admin/share-frames/:frameKey/:frameVersion", async (request) => {
    const params = request.params as FrameParams
    const body = (request.body ?? {}) as Partial<{
      title: string
      sortOrder: number
      availableFrom: string | null
      availableUntil: string | null
      published: boolean
    }>
    return service.update({
      adminPassword: readAdminPassword(request),
      frameKey: params.frameKey,
      frameVersion: routeVersion(params.frameVersion),
      ...body,
    })
  })

  app.put("/admin/share-frames/:frameKey/:frameVersion/content", async (request) => {
    const params = request.params as FrameParams
    const frameVersion = routeVersion(params.frameVersion)
    const upload = await readFrameUpload(request)
    validateMultipartFields(upload.fields, [])
    return service.replaceContent({
      adminPassword: readAdminPassword(request),
      frameKey: params.frameKey,
      frameVersion,
      layout: upload.layout,
      image: upload.image,
    })
  })

  app.post("/admin/share-frames/:frameKey/:frameVersion/copy", async (request) => {
    const params = request.params as FrameParams
    const body = (request.body ?? {}) as { newVersion?: number }
    return service.copy({
      adminPassword: readAdminPassword(request),
      frameKey: params.frameKey,
      frameVersion: routeVersion(params.frameVersion),
      newVersion: body.newVersion,
    })
  })

  app.delete("/admin/share-frames/:frameKey/:frameVersion", async (request) => {
    const params = request.params as FrameParams
    return service.delete({
      adminPassword: readAdminPassword(request),
      frameKey: params.frameKey,
      frameVersion: routeVersion(params.frameVersion),
      confirm: (request.body as { confirm?: boolean } | undefined)?.confirm,
    })
  })
}
