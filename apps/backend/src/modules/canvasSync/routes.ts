import type { FastifyInstance } from "fastify"
import type { CanvasSyncHub } from "./hub.js"
import { clientSupportsPhotos } from "../canvas/featureCompat.js"

export function registerCanvasSyncRoutes(app: FastifyInstance, hub: CanvasSyncHub): void {
  app.get("/canvas/sync", { websocket: true }, (socket, request) => {
    hub.attach(socket, clientSupportsPhotos(request.headers))
  })
}

