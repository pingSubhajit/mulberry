import Fastify, { type FastifyInstance, type FastifyRequest } from "fastify"
import fastifyWebsocket from "@fastify/websocket"
import { CanvasSyncHub } from "./canvasSync.js"
import type { AppConfig } from "./config.js"
import { loadConfig } from "./config.js"
import { createDatabase, type Database } from "./db.js"
import { DefaultGoogleTokenVerifier, type GoogleTokenVerifier } from "./googleAuth.js"
import { MulberryService, HttpError } from "./service.js"

export interface CreateAppOptions {
  config?: AppConfig
  db?: Database
  googleVerifier?: GoogleTokenVerifier
}

export async function createApp(options: CreateAppOptions = {}): Promise<FastifyInstance> {
  const config = options.config ?? loadConfig()
  const db = options.db ?? (await createDatabase(config.databaseUrl))
  const googleVerifier = options.googleVerifier ?? new DefaultGoogleTokenVerifier(config)
  const service = new MulberryService(db, googleVerifier)
  const canvasSyncHub = new CanvasSyncHub(service)
  const app = Fastify({ logger: false })
  await app.register(fastifyWebsocket)

  app.addHook("onClose", async () => {
    await db.end()
  })

  app.setErrorHandler((error, _request, reply) => {
    if (error instanceof HttpError) {
      void reply.code(error.statusCode).send({ message: error.message })
      return
    }
    app.log.error(error)
    void reply.code(500).send({ message: "Internal server error" })
  })

  app.post("/auth/google", async (request) => {
    const body = request.body as { idToken?: string }
    if (!body?.idToken) {
      throw new HttpError(400, "idToken is required")
    }
    return service.authenticateWithGoogle(body.idToken)
  })

  app.post("/auth/refresh", async (request) => {
    const body = request.body as { refreshToken?: string }
    if (!body?.refreshToken) {
      throw new HttpError(400, "refreshToken is required")
    }
    return service.refreshSession(body.refreshToken)
  })

  app.post("/auth/logout", async (request, reply) => {
    await service.logout(requireBearerToken(request))
    reply.code(204).send()
  })

  app.get("/bootstrap", async (request) => {
    return service.getBootstrap(requireBearerToken(request))
  })

  app.put("/me/profile", async (request) => {
    const body = request.body as {
      displayName?: string
      partnerDisplayName?: string
      anniversaryDate?: string
    }
    return service.updateProfile(requireBearerToken(request), {
      displayName: body.displayName ?? "",
      partnerDisplayName: body.partnerDisplayName ?? "",
      anniversaryDate: body.anniversaryDate ?? "",
    })
  })

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

  app.get("/canvas/ops", async (request) => {
    const query = request.query as { afterRevision?: string }
    const afterRevision = Number(query.afterRevision ?? "0")
    return service.listCanvasOperations(
      requireBearerToken(request),
      Number.isFinite(afterRevision) ? afterRevision : 0,
    )
  })

  app.get("/canvas/snapshot", async (request) => {
    return service.getCanvasSnapshot(requireBearerToken(request))
  })

  app.get("/canvas/sync", { websocket: true }, (socket) => {
    canvasSyncHub.attach(socket)
  })

  app.get("/health", async (_request, reply) => {
    reply.code(200).send({ ok: true })
  })

  return app
}

function requireBearerToken(request: FastifyRequest): string {
  const header = request.headers.authorization
  if (!header?.startsWith("Bearer ")) {
    throw new HttpError(401, "Missing bearer token")
  }
  return header.slice("Bearer ".length)
}
