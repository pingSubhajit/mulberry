import type { FastifyInstance } from "fastify"
import { requireBearerToken } from "../../infra/http/auth.js"
import type { ReactionsService } from "./service.js"

export function registerReactionRoutes(app: FastifyInstance, service: ReactionsService): void {
  app.post("/reactions/send", async (request) => {
    const body = request.body as { reactionType?: string }
    return service.sendReaction(requireBearerToken(request), body?.reactionType ?? "")
  })

  app.post("/reactions/lease", async (request) => {
    const body = request.body as { generation?: unknown; deviceId?: unknown }
    return service.leaseReactionPlayback(requireBearerToken(request), body)
  })

  app.post("/reactions/confirm", async (request) => {
    const body = request.body as { generation?: unknown; deviceId?: unknown }
    return service.confirmReactionPlayback(requireBearerToken(request), body)
  })
}

