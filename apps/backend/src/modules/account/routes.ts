import type { FastifyInstance } from "fastify"
import { requireBearerToken } from "../../infra/http/auth.js"
import type { AccountService } from "./service.js"

export function registerAccountRoutes(app: FastifyInstance, service: AccountService): void {
  app.delete("/me", async (request, reply) => {
    await service.deleteAccount(requireBearerToken(request))
    reply.code(204).send()
  })
}
