import type { FastifyInstance } from "fastify"

export function registerHealthRoutes(app: FastifyInstance): void {
  app.get("/health", async (_request, reply) => {
    reply.code(200).send({ ok: true })
  })
}

