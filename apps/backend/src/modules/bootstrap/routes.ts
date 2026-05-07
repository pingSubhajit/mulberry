import type { FastifyInstance } from "fastify"
import { requireBearerToken } from "../../infra/http/auth.js"
import type { BootstrapService } from "./service.js"

export function registerBootstrapRoutes(app: FastifyInstance, service: BootstrapService): void {
  app.get("/bootstrap", async (request) => {
    return service.getBootstrap(requireBearerToken(request))
  })
}

