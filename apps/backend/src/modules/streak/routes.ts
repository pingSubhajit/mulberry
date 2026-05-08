import type { FastifyInstance } from "fastify"
import { requireBearerToken } from "../../infra/http/auth.js"
import type { StreakService } from "./service.js"

export function registerStreakRoutes(app: FastifyInstance, service: StreakService): void {
  app.get("/streak", async (request) => {
    const query = request.query as { today?: string }
    return service.getStreak(requireBearerToken(request), query.today ?? "")
  })

  app.post("/debug/streak/clear-activity-day", async (request) => {
    const body = request.body as { day?: string }
    return service.clearActivityDay(requireBearerToken(request), body?.day ?? "")
  })
}
