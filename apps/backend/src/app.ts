import Fastify, { type FastifyInstance, type FastifyRequest } from "fastify"
import fastifyCors from "@fastify/cors"
import fastifyMultipart from "@fastify/multipart"
import fastifyWebsocket from "@fastify/websocket"
import { CanvasSyncHub } from "./canvasSync.js"
import type { AppConfig } from "./config.js"
import { loadConfig } from "./config.js"
import { createDatabase, type Database } from "./db.js"
import { DefaultGoogleTokenVerifier, type GoogleTokenVerifier } from "./googleAuth.js"
import type { ClientCanvasOperation } from "./domain.js"
import {
  createPushSender,
  PushDispatchService,
  type PushDispatchOptions,
  type PushSender,
} from "./push.js"
import { MulberryService, HttpError } from "./service.js"
import {
  createWallpaperStorage,
  SharpWallpaperImageProcessor,
  WallpaperCatalogService,
  type WallpaperImageProcessor,
  type WallpaperStorage,
} from "./wallpapers.js"
import { createStickerStorage, StickerCatalogService } from "./stickers.js"

export interface CreateAppOptions {
  config?: AppConfig
  db?: Database
  googleVerifier?: GoogleTokenVerifier
  pushSender?: PushSender
  pushOptions?: PushDispatchOptions
  wallpaperStorage?: WallpaperStorage
  wallpaperImageProcessor?: WallpaperImageProcessor
}

export async function createApp(options: CreateAppOptions = {}): Promise<FastifyInstance> {
  const config = options.config ?? loadConfig()
  const db = options.db ?? (await createDatabase(config.databaseUrl))
  const googleVerifier = options.googleVerifier ?? new DefaultGoogleTokenVerifier(config)
  const pushDispatchService = new PushDispatchService(
    db,
    options.pushSender ?? createPushSender({
      serviceAccountPath: config.firebaseServiceAccountPath,
      serviceAccountJson: config.firebaseServiceAccountJson,
    }),
    {
      canvasUpdateTtlMs: config.canvasUpdatePushTtlMs,
      pairingConfirmationTtlMs: config.pairingConfirmationPushTtlMs,
      canvasNudgeDelayMs: config.canvasNudgeDelayMs,
      canvasNudgePollIntervalMs: config.canvasNudgePollIntervalMs,
      canvasNudgeTtlMs: config.canvasNudgePushTtlMs,
      drawReminderBaseDelayMs: config.drawReminderBaseDelayMs,
      drawReminderPollIntervalMs: config.drawReminderPollIntervalMs,
      drawReminderTtlMs: config.drawReminderPushTtlMs,
      drawReminderMaxBackoffDays: config.drawReminderMaxBackoffDays,
      ...options.pushOptions,
    },
  )
  const storage = options.wallpaperStorage ?? createWallpaperStorage(config)
  const service = new MulberryService(db, googleVerifier, pushDispatchService, storage)
  const wallpaperCatalogService = new WallpaperCatalogService(
    db,
    storage,
    options.wallpaperImageProcessor ?? new SharpWallpaperImageProcessor(),
    config,
  )
  const stickerCatalogService = new StickerCatalogService(
    db,
    createStickerStorage({
      supabaseUrl: config.supabaseUrl,
      supabaseServiceRoleKey: config.supabaseServiceRoleKey,
      supabaseStickerBucket: config.supabaseStickerBucket,
      stickerAdminPassword: config.stickerAdminPassword,
    }),
    config.stickerAdminPassword,
  )
  const canvasSyncHub = new CanvasSyncHub(service)
  const app = Fastify({ logger: false })
  await app.register(fastifyCors, {
    origin: true,
    methods: ["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
    allowedHeaders: ["authorization", "content-type", "x-wallpaper-admin-password", "x-sticker-admin-password"],
  })
  await app.register(fastifyMultipart, {
    limits: {
      fileSize: 20 * 1024 * 1024,
      files: 1,
    },
  })
  await app.register(fastifyWebsocket)

  app.addHook("onClose", async () => {
    pushDispatchService.dispose()
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

  app.get("/bootstrap", async (request) => {
    return service.getBootstrap(requireBearerToken(request))
  })

  app.get("/streak", async (request) => {
    const query = request.query as { today?: string }
    return service.getStreak(requireBearerToken(request), query.today ?? "")
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

  app.put("/me/display-name", async (request) => {
    const body = request.body as { displayName?: string }
    return service.updateDisplayName(requireBearerToken(request), body.displayName ?? "")
  })

  app.put("/me/profile-photo", async (request) => {
    return service.updateProfilePhoto(requireBearerToken(request), await readRequiredImageUpload(request))
  })

  app.put("/me/partner-profile", async (request) => {
    const body = request.body as {
      partnerDisplayName?: string
      anniversaryDate?: string
    }
    return service.updatePartnerProfile(requireBearerToken(request), {
      partnerDisplayName: body.partnerDisplayName ?? "",
      anniversaryDate: body.anniversaryDate ?? "",
    })
  })

  app.put("/me/partner-profile-photo", async (request) => {
    return service.updatePartnerProfilePhoto(requireBearerToken(request), await readRequiredImageUpload(request))
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

  app.post("/pairing/disconnect", async (request) => {
    return service.disconnectPairing(requireBearerToken(request))
  })

  app.post("/debug/pairing-confirmation-push", async (request) => {
    return service.sendDebugPairingConfirmed(requireBearerToken(request))
  })

  app.post("/debug/pairing-disconnected-push", async (request) => {
    return service.sendDebugPairingDisconnected(requireBearerToken(request))
  })

  app.get("/canvas/ops", async (request) => {
    const query = request.query as { afterRevision?: string }
    const afterRevision = Number(query.afterRevision ?? "0")
    return service.listCanvasOperations(
      requireBearerToken(request),
      Number.isFinite(afterRevision) ? afterRevision : 0,
    )
  })

  app.post("/canvas/ops/batch", async (request) => {
    const body = request.body as {
      batchId?: string
      operations?: unknown[]
      clientCreatedAt?: string
    }
    const accepted = await service.acceptCanvasOperationBatchForAuthenticatedPair(
      requireBearerToken(request),
      {
        batchId: body.batchId ?? "",
        operations: (body.operations ?? []) as ClientCanvasOperation[],
        clientCreatedAt: body.clientCreatedAt ?? new Date().toISOString(),
      },
    )
    return { operations: accepted }
  })

  app.get("/canvas/snapshot", async (request) => {
    return service.getCanvasSnapshot(requireBearerToken(request))
  })

  app.get("/wallpapers", async (request) => {
    const query = request.query as { cursor?: string; limit?: string }
    const limit = Number(query.limit ?? "24")
    return wallpaperCatalogService.listPublishedWallpapers({
      cursor: query.cursor,
      limit: Number.isFinite(limit) ? limit : 24,
    })
  })

  app.get("/stickers/packs", async (request) => {
    requireBearerToken(request)
    return stickerCatalogService.listPublishedStickerPacks()
  })

  app.get("/stickers/packs/:packKey", async (request) => {
    requireBearerToken(request)
    const params = request.params as { packKey?: string }
    const query = request.query as { version?: string }
    const version = query.version ? Number(query.version) : undefined
    return stickerCatalogService.getPublishedStickerPackDetail(
      params.packKey ?? "",
      Number.isFinite(version) ? version : undefined,
    )
  })

  app.get("/stickers/assets/url", async (request) => {
    requireBearerToken(request)
    const query = request.query as {
      packKey?: string
      version?: string
      stickerId?: string
      variant?: string
    }
    const version = Number(query.version ?? "")
    const variant = query.variant === "thumbnail" ? "thumbnail" : "full"
    return stickerCatalogService.getStickerAssetSignedUrl({
      packKey: query.packKey ?? "",
      packVersion: Number.isFinite(version) ? version : 0,
      stickerId: query.stickerId ?? "",
      variant,
    })
  })

  app.get("/admin/wallpapers", async (request) => {
    return wallpaperCatalogService.listWallpapersForAdmin({
      adminPassword: readWallpaperAdminPassword(request),
    })
  })

  app.get("/admin/stickers/packs", async (request) => {
    return stickerCatalogService.listStickerPacksForAdmin({
      adminPassword: readStickerAdminPassword(request) ?? "",
    })
  })

  app.get("/admin/stickers/packs/:packKey/:packVersion", async (request) => {
    const params = request.params as { packKey?: string; packVersion?: string }
    const packVersion = Number(params.packVersion ?? "0")
    return stickerCatalogService.getStickerPackDetailForAdmin({
      adminPassword: readStickerAdminPassword(request) ?? "",
      packKey: params.packKey ?? "",
      packVersion: Number.isFinite(packVersion) ? packVersion : 0,
    })
  })

  app.post("/admin/stickers/packs", async (request) => {
    const fields: Record<string, string> = {}
    let uploadedFile: {
      filename: string
      mimetype: string
      data: Buffer
    } | null = null

    for await (const part of request.parts()) {
      if (part.type === "file") {
        uploadedFile = {
          filename: part.filename,
          mimetype: part.mimetype,
          data: await part.toBuffer(),
        }
      } else if (typeof part.value === "string") {
        fields[part.fieldname] = part.value
      }
    }

    if (!uploadedFile) {
      throw new HttpError(400, "cover image is required")
    }

    const published = fields.published === "true"
    const featured = fields.featured === "true"
    const sortOrder = Number(fields.sortOrder ?? "0")
    const packVersion = Number(fields.packVersion ?? "1")
    return stickerCatalogService.createStickerPackVersion({
      adminPassword: readStickerAdminPassword(request) ?? "",
      packKey: fields.packKey ?? "",
      packVersion: Number.isFinite(packVersion) ? packVersion : 1,
      title: fields.title ?? "",
      description: fields.description ?? "",
      sortOrder: Number.isFinite(sortOrder) ? sortOrder : 0,
      featured,
      published,
      coverFilename: uploadedFile.filename,
      coverContentType: uploadedFile.mimetype,
      coverData: uploadedFile.data,
    })
  })

  app.patch("/admin/stickers/packs/:packKey/:packVersion", async (request) => {
    const params = request.params as { packKey?: string; packVersion?: string }
    const body = request.body as Partial<{
      title: string
      description: string
      sortOrder: number
      featured: boolean
      published: boolean
    }>
    const packVersion = Number(params.packVersion ?? "0")
    return stickerCatalogService.updateStickerPackVersionMetadata({
      adminPassword: readStickerAdminPassword(request) ?? "",
      packKey: params.packKey ?? "",
      packVersion: Number.isFinite(packVersion) ? packVersion : 0,
      title: body.title,
      description: body.description,
      sortOrder: body.sortOrder,
      featured: body.featured,
      published: body.published,
    })
  })

  app.put("/admin/stickers/packs/:packKey/:packVersion/cover", async (request) => {
    const params = request.params as { packKey?: string; packVersion?: string }
    const upload = await readRequiredImageUpload(request)
    const packVersion = Number(params.packVersion ?? "0")
    return stickerCatalogService.updateStickerPackVersionCoverForAdmin({
      adminPassword: readStickerAdminPassword(request) ?? "",
      packKey: params.packKey ?? "",
      packVersion: Number.isFinite(packVersion) ? packVersion : 0,
      filename: upload.filename,
      contentType: upload.contentType,
      data: upload.data,
    })
  })

  app.post("/admin/stickers/packs/:packKey/:packVersion/stickers", async (request) => {
    const params = request.params as { packKey?: string; packVersion?: string }
    const fields: Record<string, string> = {}
    let uploadedFile: {
      filename: string
      mimetype: string
      data: Buffer
    } | null = null

    for await (const part of request.parts()) {
      if (part.type === "file") {
        uploadedFile = {
          filename: part.filename,
          mimetype: part.mimetype,
          data: await part.toBuffer(),
        }
      } else if (typeof part.value === "string") {
        fields[part.fieldname] = part.value
      }
    }

    if (!uploadedFile) {
      throw new HttpError(400, "sticker image is required")
    }

    const sortOrder = Number(fields.sortOrder ?? "0")
    const packVersion = Number(params.packVersion ?? "0")
    return stickerCatalogService.uploadSticker({
      adminPassword: readStickerAdminPassword(request) ?? "",
      packKey: params.packKey ?? "",
      packVersion: Number.isFinite(packVersion) ? packVersion : 0,
      stickerId: fields.stickerId ?? "",
      sortOrder: Number.isFinite(sortOrder) ? sortOrder : 0,
      filename: uploadedFile.filename,
      contentType: uploadedFile.mimetype,
      data: uploadedFile.data,
    })
  })

  app.delete("/admin/stickers/packs/:packKey/:packVersion", async (request, reply) => {
    const params = request.params as { packKey?: string; packVersion?: string }
    const packVersion = Number(params.packVersion ?? "0")
    await stickerCatalogService.deleteStickerPackVersionForAdmin({
      adminPassword: readStickerAdminPassword(request) ?? "",
      packKey: params.packKey ?? "",
      packVersion: Number.isFinite(packVersion) ? packVersion : 0,
    })
    reply.code(204).send()
  })

  app.post("/admin/wallpapers", async (request) => {
    const fields: Record<string, string> = {}
    let uploadedFile: {
      filename: string
      mimetype: string
      data: Buffer
    } | null = null

    for await (const part of request.parts()) {
      if (part.type === "file") {
        uploadedFile = {
          filename: part.filename,
          mimetype: part.mimetype,
          data: await part.toBuffer(),
        }
      } else if (typeof part.value === "string") {
        fields[part.fieldname] = part.value
      }
    }

    if (!uploadedFile) {
      throw new HttpError(400, "image is required")
    }
    const published = fields.published === "true"
    const sortOrder = Number(fields.sortOrder ?? "0")
    return wallpaperCatalogService.createWallpaper({
      adminPassword: readWallpaperAdminPassword(request),
      title: fields.title,
      description: fields.description,
      sortOrder: Number.isFinite(sortOrder) ? sortOrder : 0,
      published,
      filename: uploadedFile.filename,
      contentType: uploadedFile.mimetype,
      data: uploadedFile.data,
    })
  })

  app.patch("/admin/wallpapers/:wallpaperId", async (request) => {
    const params = request.params as { wallpaperId?: string }
    const body = request.body as {
      title?: string
      description?: string
      sortOrder?: number
      published?: boolean
    }
    return wallpaperCatalogService.updateWallpaper({
      adminPassword: readWallpaperAdminPassword(request),
      id: params.wallpaperId ?? "",
      title: body.title,
      description: body.description,
      sortOrder: body.sortOrder,
      published: body.published,
    })
  })

  app.delete("/admin/wallpapers/:wallpaperId", async (request, reply) => {
    const params = request.params as { wallpaperId?: string }
    await wallpaperCatalogService.deleteWallpaper({
      adminPassword: readWallpaperAdminPassword(request),
      id: params.wallpaperId ?? "",
    })
    reply.code(204).send()
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

function readWallpaperAdminPassword(request: FastifyRequest): string | undefined {
  const header = request.headers["x-wallpaper-admin-password"]
  return Array.isArray(header) ? header[0] : header
}

function readStickerAdminPassword(request: FastifyRequest): string | undefined {
  const header = request.headers["x-sticker-admin-password"]
  return Array.isArray(header) ? header[0] : header
}

async function readRequiredImageUpload(request: FastifyRequest): Promise<{
  filename: string
  contentType: string
  data: Buffer
}> {
  for await (const part of request.parts()) {
    if (part.type === "file" && part.fieldname === "image") {
      return {
        filename: part.filename,
        contentType: part.mimetype,
        data: await part.toBuffer(),
      }
    }
  }
  throw new HttpError(400, "image is required")
}
