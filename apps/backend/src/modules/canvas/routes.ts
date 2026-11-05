import type { FastifyInstance, FastifyRequest } from "fastify"
import { requireBearerToken } from "../../infra/http/auth.js"
import { HttpError } from "../../infra/http/HttpError.js"
import type { ClientCanvasOperation } from "../../contracts/canvas.js"
import type { CanvasService } from "./service.js"
import { CANVAS_PHOTO_MAX_BYTES, canvasPhotoTooLarge, type CanvasPhotoService } from "./photos.js"
import { clientSupportsPhotos, operationsForClient, snapshotForClient } from "./featureCompat.js"

/** Photos are fetched by id and never change, so devices can keep them for good. */
const CANVAS_PHOTO_CACHE_CONTROL = "private, max-age=31536000, immutable"

export function registerCanvasRoutes(
  app: FastifyInstance,
  service: CanvasService,
  photoService: CanvasPhotoService,
): void {
  app.get("/canvas/ops", async (request) => {
    const query = request.query as { afterRevision?: string }
    const afterRevision = Number(query.afterRevision ?? "0")
    const response = await service.listCanvasOperations(
      requireBearerToken(request),
      Number.isFinite(afterRevision) ? afterRevision : 0,
    )
    return { ...response, operations: operationsForClient(response.operations, clientSupportsPhotos(request.headers)) }
  })

  app.post("/canvas/ops/batch", async (request) => {
    const body = request.body as {
      batchId?: string
      operations?: unknown[]
      clientCreatedAt?: string
    }
    const accepted = await service.acceptCanvasOperationBatchForAuthenticatedPair(
      requireBearerToken(request),
      {
        batchId: body.batchId ?? "",
        operations: (body.operations ?? []) as ClientCanvasOperation[],
        clientCreatedAt: body.clientCreatedAt ?? new Date().toISOString(),
      },
    )
    return { operations: operationsForClient(accepted, clientSupportsPhotos(request.headers)) }
  })

  app.get("/canvas/snapshot", async (request) => {
    const snapshot = await service.getCanvasSnapshot(requireBearerToken(request))
    return snapshotForClient(snapshot, clientSupportsPhotos(request.headers))
  })

  app.post("/canvas/photos", async (request) => {
    return photoService.uploadPhoto(requireBearerToken(request), () => readCanvasPhotoUpload(request))
  })

  app.get("/canvas/photos/:photoId", async (request, reply) => {
    const { photoId } = request.params as { photoId: string }
    const photo = await photoService.getPhoto(requireBearerToken(request), photoId)
    return reply
      .header("Cache-Control", CANVAS_PHOTO_CACHE_CONTROL)
      .type(photo.contentType)
      .send(photo.bytes)
  })
}

/** The multipart `image` field, up to 12 MB. */
async function readCanvasPhotoUpload(request: FastifyRequest): Promise<Buffer> {
  if (!request.isMultipart()) {
    throw new HttpError(400, "image is required")
  }
  try {
    for await (const part of request.parts({ limits: { fileSize: CANVAS_PHOTO_MAX_BYTES, files: 1 } })) {
      if (part.type === "file" && part.fieldname === "image") {
        return await part.toBuffer()
      }
    }
  } catch (error) {
    if (error instanceof HttpError) throw error
    if (multipartErrorCode(error) === "FST_REQ_FILE_TOO_LARGE") throw canvasPhotoTooLarge()
    throw new HttpError(400, "image could not be read")
  }
  throw new HttpError(400, "image is required")
}

function multipartErrorCode(error: unknown): string | null {
  if (typeof error !== "object" || error === null || !("code" in error)) return null
  return typeof error.code === "string" ? error.code : null
}

