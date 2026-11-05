import type { FastifyInstance } from "fastify"
import type { AppleSignInRequest } from "../../contracts/auth.js"
import { HttpError } from "../../infra/http/HttpError.js"
import { requireBearerToken } from "../../infra/http/auth.js"
import type { AuthService } from "./service.js"
import { registerAppleWebCallbackRoute } from "./appleWebCallback.js"

export function registerAuthRoutes(app: FastifyInstance, service: AuthService): void {
  registerAppleWebCallbackRoute(app)

  app.post("/auth/google", async (request) => {
    return service.authenticateWithGoogle(googleIdToken(request.body))
  })

  app.post("/auth/apple", async (request) => {
    return service.authenticateWithApple(appleSignInRequest(request.body))
  })

  app.post("/me/sign-in-methods/google", async (request) => {
    return service.linkGoogle(requireBearerToken(request), googleIdToken(request.body))
  })

  app.post("/me/sign-in-methods/apple", async (request) => {
    return service.linkApple(requireBearerToken(request), appleSignInRequest(request.body))
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

function googleIdToken(body: unknown): string {
  const idToken = (body as { idToken?: unknown } | undefined)?.idToken
  if (typeof idToken !== "string" || idToken.length === 0) {
    throw new HttpError(400, "idToken is required")
  }
  return idToken
}

function appleSignInRequest(body: unknown): AppleSignInRequest {
  const fields = (body ?? {}) as Partial<Record<keyof AppleSignInRequest, unknown>>
  if (typeof fields.identityToken !== "string" || fields.identityToken.length === 0) {
    throw new HttpError(400, "identityToken is required")
  }
  if (typeof fields.nonce !== "string" || fields.nonce.length === 0) {
    throw new HttpError(400, "nonce is required")
  }
  const displayName = typeof fields.displayName === "string" ? fields.displayName.trim() : ""
  return {
    identityToken: fields.identityToken,
    nonce: fields.nonce,
    authorizationCode: typeof fields.authorizationCode === "string" ? fields.authorizationCode : undefined,
    displayName: displayName.length > 0 ? displayName : undefined,
  }
}
