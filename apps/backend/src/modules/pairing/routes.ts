import type { FastifyInstance } from "fastify"
import { HttpError } from "../../infra/http/HttpError.js"
import { requireBearerToken } from "../../infra/http/auth.js"
import type { PairingService } from "./service.js"

export function registerPairingRoutes(app: FastifyInstance, service: PairingService): void {
  app.post("/invites", async (request) => {
    return service.createInvite(requireBearerToken(request))
  })

  app.post("/invites/redeem", async (request) => {
    const body = request.body as { code?: string }
    if (!body?.code || !/^\d{6}$/.test(body.code)) {
      throw new HttpError(400, "A 6-digit code is required")
    }
    return service.redeemInvite(requireBearerToken(request), body.code)
  })

  app.post("/invites/:inviteId/accept", async (request) => {
    const params = request.params as { inviteId?: string }
    if (!params.inviteId) {
      throw new HttpError(400, "inviteId is required")
    }
    return service.acceptInvite(requireBearerToken(request), params.inviteId)
  })

  app.post("/invites/:inviteId/decline", async (request) => {
    const params = request.params as { inviteId?: string }
    if (!params.inviteId) {
      throw new HttpError(400, "inviteId is required")
    }
    return service.declineInvite(requireBearerToken(request), params.inviteId)
  })

  app.post("/pairing/disconnect", async (request) => {
    return service.disconnectPairing(requireBearerToken(request))
  })

  app.post("/debug/pairing-confirmation-push", async (request) => {
    return service.sendDebugPairingConfirmed(requireBearerToken(request))
  })

  app.post("/debug/pairing-disconnected-push", async (request) => {
    return service.sendDebugPairingDisconnected(requireBearerToken(request))
  })
}

