import { registerCatalogAdminHooks } from "../modules/catalog/adminRoutes.js"
import Fastify, { type FastifyInstance } from "fastify"
import fastifyCors from "@fastify/cors"
import fastifyMultipart from "@fastify/multipart"
import fastifyWebsocket from "@fastify/websocket"
import type { AppConfig } from "../infra/config/config.js"
import { loadConfig } from "../infra/config/config.js"
import { createDatabase, type Database } from "../infra/db/database.js"
import { DefaultGoogleTokenVerifier, type GoogleTokenVerifier } from "../infra/auth/googleTokenVerifier.js"
import { DefaultAppleTokenVerifier, type AppleTokenVerifier } from "../infra/auth/appleTokenVerifier.js"
import { createAppleCredentialClient, type AppleCredentialClient } from "../infra/auth/appleCredentialClient.js"
import { createAnalyticsRuntime } from "../infra/analytics/setup.js"
import type { PostHogTransport } from "../infra/analytics/transport.js"
import type { OutboxWorkerOptions } from "../infra/analytics/worker.js"
import { registerClientContext } from "../infra/analytics/context.js"
import { registerClientRecorder } from "../infra/analytics/clientRecorder.js"
import { runAnalyticsBackfill } from "../infra/analytics/backfill.js"
import { ClientAnalyticsService } from "../modules/analytics/service.js"
import { registerAnalyticsRoutes } from "../modules/analytics/routes.js"
import { getRemoteConfig } from "firebase-admin/remote-config"
import { firebaseApp, type FirebaseCredentials } from "../infra/firebase/app.js"
import { createPushSender } from "../infra/push/sender.js"
import { createWidgetPushSender, type WidgetPushSender } from "../infra/push/widgetSender.js"
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
import { LocalWallpaperStorage } from "../modules/wallpapers/localStorage.js"
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
import { CanvasPhotoService } from "../modules/canvas/photos.js"
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
import { FeedbackService } from "../modules/feedback/service.js"
import { registerFeedbackRoutes } from "../modules/feedback/routes.js"
import { registerDebugRoutes } from "../modules/debug/routes.js"
import { AccountService } from "../modules/account/service.js"
import { registerAccountRoutes } from "../modules/account/routes.js"
import { registerAdminRoutes } from "../modules/admin/routes.js"
import { AppVersionsService, type RemoteConfigClient } from "../modules/appVersions/service.js"
import { registerAppVersionRoutes } from "../modules/appVersions/routes.js"
import { registerUpdateGate } from "../modules/appVersions/updateGate.js"
import { registerTimeZoneRecorder } from "../modules/timeZones/recorder.js"
import { ADMIN_PASSWORD_HEADER } from "../infra/http/adminAuth.js"

export interface CreateAppOptions {
  config?: AppConfig
  db?: Database
  googleVerifier?: GoogleTokenVerifier
  appleVerifier?: AppleTokenVerifier
  appleCredentials?: AppleCredentialClient
  /** Replaces PostHog's HTTP API, as in tests. */
  analyticsTransport?: PostHogTransport
  analyticsWorker?: OutboxWorkerOptions
  /** Sends PostHog the history from before analytics was keyed to people, once. On by default. */
  analyticsBackfill?: boolean
  pushSender?: PushSender
  pushOptions?: PushDispatchOptions
  widgetPushSender?: WidgetPushSender
  wallpaperStorage?: WallpaperStorage
  wallpaperImageProcessor?: WallpaperImageProcessor
  /** Stands in for Firebase Remote Config; null leaves it unconfigured. */
  remoteConfig?: RemoteConfigClient | null
}

export async function createApp(options: CreateAppOptions = {}): Promise<FastifyInstance> {
  const config = options.config ?? loadConfig()
  const db = options.db ?? (await createDatabase(config.databaseUrl))
  const googleVerifier = options.googleVerifier ?? new DefaultGoogleTokenVerifier(config)
  const appleVerifier = options.appleVerifier ?? new DefaultAppleTokenVerifier(config)
  const appleCredentials = options.appleCredentials ?? createAppleCredentialClient(config.appleSignInKey, config.appleWebSignIn)
  const { analytics, worker: analyticsWorker } = createAnalyticsRuntime(config, db, {
    transport: options.analyticsTransport,
    worker: options.analyticsWorker,
  })

  const firebaseCredentials: FirebaseCredentials = {
    serviceAccountPath: config.firebaseServiceAccountPath,
    serviceAccountJson: config.firebaseServiceAccountJson,
  }
  const pushDispatchService = new PushDispatchService(
    db,
    options.pushSender ?? createPushSender(firebaseCredentials),
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
    options.widgetPushSender ?? createWidgetPushSender(config.apnsKeys),
    analytics,
  )

  // Profile photos share the Supabase wallpaper bucket. Their URLs leave in bootstrap and push
  // payloads, so only the wallpaper catalog, served by its own routes, may use local files.
  const storage = options.wallpaperStorage ?? createWallpaperStorage(config)
  const wallpaperStorage = options.wallpaperStorage
    ?? (config.wallpaperLocalStorageDir ? new LocalWallpaperStorage(config.wallpaperLocalStorageDir) : storage)
  const wallpaperCatalogService = new WallpaperCatalogService(
    db,
    wallpaperStorage,
    options.wallpaperImageProcessor ?? new SharpWallpaperImageProcessor(),
    config,
  )
  const stickerStorage = createStickerStorage({
    supabaseUrl: config.supabaseUrl,
    supabaseServiceRoleKey: config.supabaseServiceRoleKey,
    supabaseStickerBucket: config.supabaseStickerBucket,
    stickerLocalStorageDir: config.stickerLocalStorageDir,
  })
  const stickerCatalogService = new StickerCatalogService(db, stickerStorage, config.adminPassword)
  const remoteConfig = options.remoteConfig !== undefined ? options.remoteConfig : remoteConfigFor(firebaseCredentials)
  const appVersionsService = new AppVersionsService(remoteConfig, config.adminPassword)
  void appVersionsService.refreshMinimums()

  const streakService = new StreakService(db)
  const bootstrapService = new BootstrapService(db, streakService, storage, config.wallpaperShortcutLinks, analytics)
  const authService = new AuthService(db, googleVerifier, bootstrapService, appleVerifier, appleCredentials, analytics)
  const devicesService = new DevicesService(db)
  const profileService = new ProfileService(db, bootstrapService, pushDispatchService, storage, analytics)
  const pairingService = new PairingService(db, bootstrapService, pushDispatchService, analytics)
  const reactionsService = new ReactionsService(db, pushDispatchService, analytics)
  const canvasService = new CanvasService(db, streakService, pushDispatchService, analytics)
  const canvasPhotoService = new CanvasPhotoService(db)
  const canvasSyncHub = new CanvasSyncHub(canvasService)
  const feedbackService = new FeedbackService(db, config.cannySsoPrivateKey)
  const accountService = new AccountService(db, pushDispatchService, storage, canvasSyncHub, appleCredentials, analytics)
  const clientAnalyticsService = new ClientAnalyticsService(db, analytics)

  const app = Fastify({ logger: false })
  await app.register(fastifyCors, {
    origin: true,
    methods: ["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
    allowedHeaders: ["authorization", "content-type", ADMIN_PASSWORD_HEADER],
  })
  await app.register(fastifyMultipart, {
    limits: {
      fileSize: 20 * 1024 * 1024,
      files: 1,
    },
  })
  await app.register(fastifyWebsocket)

  registerClientContext(app)
  registerUpdateGate(app, appVersionsService)
  registerClientRecorder(app, db, analytics)
  registerTimeZoneRecorder(app, db)

  registerWhatsNewRoutes(app)

  app.addHook("onClose", async () => {
    pushDispatchService.dispose()
    if (analyticsWorker) {
      analyticsWorker.stop()
      await flushWithin(analyticsWorker.flush(), ANALYTICS_SHUTDOWN_FLUSH_MS)
    }
    await db.end()
  })

  app.setErrorHandler((error, request, reply) => {
    if (error instanceof HttpError) {
      void reply.code(error.statusCode).send({ ...error.details, message: error.message })
      return
    }
    const status = (error as { statusCode?: number }).statusCode
    if (/^\/admin\/(stickers|wallpapers)(\/|$)/.test(request.url) && status && status >= 400 && status < 500) {
      void reply.code(status).send({ code: "INVALID_CATALOG_INPUT", message: (error as Error).message })
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
  registerCanvasRoutes(app, canvasService, canvasPhotoService)
  registerCatalogAdminHooks(app, config.adminPassword)
  registerWallpaperRoutes(app, wallpaperCatalogService, wallpaperStorage)
  registerStickerRoutes(app, stickerCatalogService, stickerStorage)
  registerCanvasSyncRoutes(app, canvasSyncHub)
  registerFeedbackRoutes(app, feedbackService)
  registerAccountRoutes(app, accountService)
  registerAdminRoutes(app, config.adminPassword)
  registerAppVersionRoutes(app, appVersionsService)
  if (config.enableDebugRoutes) {
    registerDebugRoutes(app, pairingService, streakService)
  }
  registerAnalyticsRoutes(app, clientAnalyticsService)
  registerHealthRoutes(app)

  if (analyticsWorker) {
    analyticsWorker.start()
  }
  if (analyticsWorker && options.analyticsBackfill !== false) {
    const backfillCutoff = new Date()
    void runAnalyticsBackfill(db, analytics, backfillCutoff).catch((error: unknown) => {
      console.error("[analytics] backfill failed", { error: error instanceof Error ? error.message : String(error) })
    })
  }

  return app
}

/** How long shutdown waits for the analytics outbox to send. Whatever's left goes after restart. */
const ANALYTICS_SHUTDOWN_FLUSH_MS = 5_000

async function flushWithin(flush: Promise<number>, ms: number): Promise<void> {
  let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<void>((resolve) => {
    timer = setTimeout(resolve, ms)
  })
  await Promise.race([flush.then(() => undefined).catch(() => undefined), timeout])
  clearTimeout(timer)
}

function remoteConfigFor(credentials: FirebaseCredentials): RemoteConfigClient | null {
  const firebase = firebaseApp(credentials)
  return firebase ? getRemoteConfig(firebase) : null
}
