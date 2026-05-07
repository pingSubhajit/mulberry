import Fastify, { type FastifyInstance } from "fastify"
import fastifyCors from "@fastify/cors"
import fastifyMultipart from "@fastify/multipart"
import fastifyWebsocket from "@fastify/websocket"
import type { AppConfig } from "../infra/config/config.js"
import { loadConfig } from "../infra/config/config.js"
import { createDatabase, type Database } from "../infra/db/database.js"
import { DefaultGoogleTokenVerifier, type GoogleTokenVerifier } from "../infra/auth/googleTokenVerifier.js"
import {
  createPushSender,
  type FirebasePushSenderOptions,
} from "../infra/push/sender.js"
import { PushDispatchService, type PushDispatchOptions } from "../infra/push/dispatchService.js"
import type { PushSender } from "../infra/push/types.js"
import { HttpError } from "../infra/http/HttpError.js"
import {
  createWallpaperStorage,
  SharpWallpaperImageProcessor,
  WallpaperCatalogService,
  type WallpaperImageProcessor,
  type WallpaperStorage,
} from "../modules/wallpapers/service.js"
import { createStickerStorage, StickerCatalogService } from "../modules/stickers/service.js"
import { registerWhatsNewRoutes } from "../modules/whatsNew/routes.js"
import { StreakService } from "../modules/streak/service.js"
import { BootstrapService } from "../modules/bootstrap/service.js"
import { AuthService } from "../modules/auth/service.js"
import { DevicesService } from "../modules/devices/service.js"
import { ProfileService } from "../modules/profile/service.js"
import { PairingService } from "../modules/pairing/service.js"
import { ReactionsService } from "../modules/reactions/service.js"
import { CanvasService } from "../modules/canvas/service.js"
import { CanvasSyncHub } from "../modules/canvasSync/hub.js"
import { registerAuthRoutes } from "../modules/auth/routes.js"
import { registerDeviceRoutes } from "../modules/devices/routes.js"
import { registerBootstrapRoutes } from "../modules/bootstrap/routes.js"
import { registerStreakRoutes } from "../modules/streak/routes.js"
import { registerProfileRoutes } from "../modules/profile/routes.js"
import { registerPairingRoutes } from "../modules/pairing/routes.js"
import { registerReactionRoutes } from "../modules/reactions/routes.js"
import { registerCanvasRoutes } from "../modules/canvas/routes.js"
import { registerCanvasSyncRoutes } from "../modules/canvasSync/routes.js"
import { registerStickerRoutes } from "../modules/stickers/routes.js"
import { registerWallpaperRoutes } from "../modules/wallpapers/routes.js"
import { registerHealthRoutes } from "../modules/health/routes.js"

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
    } satisfies FirebasePushSenderOptions),
    {
      canvasUpdateTtlMs: config.canvasUpdatePushTtlMs,
      pairingConfirmationTtlMs: config.pairingConfirmationTtlMs,
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

  const streakService = new StreakService(db)
  const bootstrapService = new BootstrapService(db, streakService, storage)
  const authService = new AuthService(db, googleVerifier, bootstrapService)
  const devicesService = new DevicesService(db)
  const profileService = new ProfileService(db, bootstrapService, pushDispatchService, storage)
  const pairingService = new PairingService(db, bootstrapService, pushDispatchService)
  const reactionsService = new ReactionsService(db, pushDispatchService)
  const canvasService = new CanvasService(db, streakService, pushDispatchService)
  const canvasSyncHub = new CanvasSyncHub(canvasService)

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

  registerWhatsNewRoutes(app)

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

  registerAuthRoutes(app, authService)
  registerDeviceRoutes(app, devicesService)
  registerBootstrapRoutes(app, bootstrapService)
  registerStreakRoutes(app, streakService)
  registerProfileRoutes(app, profileService)
  registerPairingRoutes(app, pairingService)
  registerReactionRoutes(app, reactionsService)
  registerCanvasRoutes(app, canvasService)
  registerWallpaperRoutes(app, wallpaperCatalogService)
  registerStickerRoutes(app, stickerCatalogService)
  registerCanvasSyncRoutes(app, canvasSyncHub)
  registerHealthRoutes(app)

  return app
}
