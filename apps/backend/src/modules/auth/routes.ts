import type { FastifyInstance } from "fastify"
import { HttpError } from "../../infra/http/HttpError.js"
import { requireBearerToken } from "../../infra/http/auth.js"
import type { AuthService } from "./service.js"

export function registerAuthRoutes(app: FastifyInstance, service: AuthService): void {
  app.post("/auth/google", async (request) => {
    const body = request.body as { idToken?: string }
    if (!body?.idToken) {
      throw new HttpError(400, "idToken is required")
    }
    return service.authenticateWithGoogle(body.idToken)
  })

  app.post("/auth/refresh", async (request) => {
    const body = request.body as { refreshToken?: string }
    if (!body?.refreshToken) {
      throw new HttpError(400, "refreshToken is required")
    }
    return service.refreshSession(body.refreshToken)
  })

  app.post("/auth/logout", async (request, reply) => {
    await service.logout(requireBearerToken(request))
    reply.code(204).send()
  })
}

