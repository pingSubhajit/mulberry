import type { FastifyInstance } from "fastify"
import { clientContextOf } from "../../infra/analytics/context.js"
import type { ClientAnalyticsService } from "./service.js"

/** The apps' own analytics events: onboarding, wallpaper setup and notification opens. */
export function registerAnalyticsRoutes(app: FastifyInstance, service: ClientAnalyticsService): void {
  app.post("/analytics/events", async (request, reply) => {
    const authorization = request.headers.authorization
    const accessToken = authorization?.startsWith("Bearer ") ? authorization.slice("Bearer ".length) || null : null
    const result = await service.record({ accessToken, client: clientContextOf(request), body: request.body })
    return reply.code(202).send(result)
  })
}
