import type { FastifyInstance } from "fastify"

export function registerHealthRoutes(app: FastifyInstance): void {
  // Returned, not reply.send(): async preSerialization hooks would otherwise let the handler
  // resolve before the send finishes, and Fastify would answer twice.
  app.get("/health", async () => ({ ok: true }))
}

