import type { FastifyInstance } from "fastify"
import { readAdminPassword, requireAdminPassword } from "../../infra/http/adminAuth.js"

export function registerAdminRoutes(app: FastifyInstance, adminPassword: string | undefined): void {
  /** Lets the admin site check a password before it opens. */
  app.get("/admin/session", async (request, reply) => {
    requireAdminPassword(adminPassword, readAdminPassword(request))
    reply.code(204).send()
  })
}
