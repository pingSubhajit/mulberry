import type { FastifyInstance } from "fastify"
import type { CanvasSyncHub } from "./hub.js"

export function registerCanvasSyncRoutes(app: FastifyInstance, hub: CanvasSyncHub): void {
  app.get("/canvas/sync", { websocket: true }, (socket) => {
    hub.attach(socket)
  })
}

