import type { FastifyInstance } from "fastify"
import { requireBearerToken } from "../../infra/http/auth.js"
import type { DevicesService } from "./service.js"
import type {
  DevicePlatform,
  UpdateLiveActivityTokensRequest,
  UpdateWidgetPushTokenRequest,
} from "../../contracts/devices.js"

export function registerDeviceRoutes(app: FastifyInstance, service: DevicesService): void {
  app.post("/devices/fcm-token", async (request) => {
    const body = request.body as {
      token?: string
      platform?: DevicePlatform
      appEnvironment?: string
    }
    return service.registerFcmToken(requireBearerToken(request), {
      token: body.token ?? "",
      platform: body.platform ?? "ANDROID",
      appEnvironment: body.appEnvironment ?? "",
    })
  })

  app.put("/devices/live-activity", async (request, reply) => {
    const body = request.body as Partial<UpdateLiveActivityTokensRequest>
    await service.updateLiveActivityTokens(requireBearerToken(request), {
      token: body.token ?? "",
      pushToStartToken: body.pushToStartToken,
      activityPushToken: body.activityPushToken,
    })
    reply.code(204).send()
  })

  app.put("/devices/widget-push", async (request, reply) => {
    const body = request.body as Partial<UpdateWidgetPushTokenRequest>
    await service.updateWidgetPushToken(requireBearerToken(request), {
      token: body.token ?? "",
      widgetPushToken: body.widgetPushToken ?? null,
      bundleId: body.bundleId ?? "",
      apnsEnvironment: body.apnsEnvironment ?? "production",
      widgetSecret: body.widgetSecret,
    })
    reply.code(204).send()
  })

  // The widget has no session; it proves itself with the secret its app registered.
  app.get("/widget/time-together", async (request) => {
    const secret = request.headers["x-mulberry-widget-secret"]
    return service.widgetTimeTogether(typeof secret === "string" ? secret : "")
  })

  app.delete("/devices/fcm-token", async (request, reply) => {
    const body = request.body as { token?: string }
    await service.unregisterFcmToken(requireBearerToken(request), body.token ?? "")
    reply.code(204).send()
  })
}

