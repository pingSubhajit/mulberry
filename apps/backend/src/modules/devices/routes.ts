import type { FastifyInstance } from "fastify"
import { requireBearerToken } from "../../infra/http/auth.js"
import type { DevicesService } from "./service.js"

export function registerDeviceRoutes(app: FastifyInstance, service: DevicesService): void {
  app.post("/devices/fcm-token", async (request) => {
    const body = request.body as {
      token?: string
      platform?: "ANDROID"
      appEnvironment?: string
    }
    return service.registerFcmToken(requireBearerToken(request), {
      token: body.token ?? "",
      platform: body.platform ?? "ANDROID",
      appEnvironment: body.appEnvironment ?? "",
    })
  })

  app.delete("/devices/fcm-token", async (request, reply) => {
    const body = request.body as { token?: string }
    await service.unregisterFcmToken(requireBearerToken(request), body.token ?? "")
    reply.code(204).send()
  })
}

