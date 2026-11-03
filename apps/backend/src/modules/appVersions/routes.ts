import type { FastifyInstance } from "fastify"
import { readAdminPassword } from "../../infra/http/adminAuth.js"
import type { AppVersionsService } from "./service.js"

export function registerAppVersionRoutes(app: FastifyInstance, service: AppVersionsService): void {
  app.get("/admin/app-versions", async (request) => {
    return service.read(readAdminPassword(request))
  })

  app.patch("/admin/app-versions/:platform", async (request) => {
    const params = request.params as { platform?: string }
    return service.update({
      adminPassword: readAdminPassword(request),
      platform: params.platform,
      body: request.body,
    })
  })
}
