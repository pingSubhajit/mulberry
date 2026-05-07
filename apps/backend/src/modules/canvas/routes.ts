import type { FastifyInstance } from "fastify"
import { requireBearerToken } from "../../infra/http/auth.js"
import type { ClientCanvasOperation } from "../../contracts/canvas.js"
import type { CanvasService } from "./service.js"

export function registerCanvasRoutes(app: FastifyInstance, service: CanvasService): void {
  app.get("/canvas/ops", async (request) => {
    const query = request.query as { afterRevision?: string }
    const afterRevision = Number(query.afterRevision ?? "0")
    return service.listCanvasOperations(
      requireBearerToken(request),
      Number.isFinite(afterRevision) ? afterRevision : 0,
    )
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
    return { operations: accepted }
  })

  app.get("/canvas/snapshot", async (request) => {
    return service.getCanvasSnapshot(requireBearerToken(request))
  })
}

