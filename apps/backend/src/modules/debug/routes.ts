import type { FastifyInstance } from "fastify"
import { requireBearerToken } from "../../infra/http/auth.js"
import type { PairingService } from "../pairing/service.js"
import type { StreakService } from "../streak/service.js"

/**
 * Tools for the apps' developer menus: test pushes and clearing a streak day. Registered only when
 * ENABLE_DEBUG_ROUTES is on, which it is by default in development and never by default in
 * production.
 */
export function registerDebugRoutes(
  app: FastifyInstance,
  pairingService: PairingService,
  streakService: StreakService,
): void {
  app.post("/debug/pairing-confirmation-push", async (request) => {
    return pairingService.sendDebugPairingConfirmed(requireBearerToken(request))
  })

  app.post("/debug/pairing-disconnected-push", async (request) => {
    return pairingService.sendDebugPairingDisconnected(requireBearerToken(request))
  })

  app.post("/debug/streak/clear-activity-day", async (request) => {
    const body = request.body as { day?: string }
    return streakService.clearActivityDay(requireBearerToken(request), body?.day ?? "")
  })
}
