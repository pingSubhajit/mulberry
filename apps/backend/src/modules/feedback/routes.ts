import type { FastifyInstance } from "fastify"
import { requireBearerToken } from "../../infra/http/auth.js"
import type { FeedbackService } from "./service.js"

export function registerFeedbackRoutes(app: FastifyInstance, service: FeedbackService): void {
  app.get("/feedback/canny-sso-token", async (request) => {
    return service.createCannySsoToken(requireBearerToken(request))
  })
}
