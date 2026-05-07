import type { FastifyInstance } from "fastify"
import { requireBearerToken } from "../../infra/http/auth.js"
import type { StreakService } from "./service.js"

export function registerStreakRoutes(app: FastifyInstance, service: StreakService): void {
  app.get("/streak", async (request) => {
    const query = request.query as { today?: string }
    return service.getStreak(requireBearerToken(request), query.today ?? "")
  })
}

