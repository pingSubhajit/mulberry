export interface AppConfig {
  port: number
  databaseUrl: string
  googleClientId: string
  allowDevGoogleTokens: boolean
  canvasModeToggleCooldownMs: number
  firebaseServiceAccountPath?: string
  firebaseServiceAccountJson?: string
  canvasUpdatePushTtlMs?: number
  pairingConfirmationPushTtlMs?: number
  canvasNudgeDelayMs?: number
  canvasNudgePollIntervalMs?: number
  canvasNudgePushTtlMs?: number
  drawReminderBaseDelayMs?: number
  drawReminderPollIntervalMs?: number
  drawReminderPushTtlMs?: number
  drawReminderMaxBackoffDays?: number
  supabaseUrl?: string
  supabaseServiceRoleKey?: string
  supabaseWallpaperBucket?: string
  wallpaperAdminPassword?: string
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const isProduction = env.NODE_ENV === "production"
  const databaseUrl = env.DATABASE_URL ?? (isProduction ? "" : "postgres://localhost:5432/mulberry")
  const googleClientId = env.GOOGLE_SERVER_CLIENT_ID ?? ""

  if (isProduction && databaseUrl.trim() === "") {
    throw new Error("DATABASE_URL is required when NODE_ENV=production")
  }

  if (isProduction && googleClientId.trim() === "") {
    throw new Error("GOOGLE_SERVER_CLIENT_ID is required when NODE_ENV=production")
  }

  return {
    port: Number(env.PORT ?? "8080"),
    databaseUrl,
    googleClientId,
    allowDevGoogleTokens: (env.ALLOW_DEV_GOOGLE_TOKENS ?? (isProduction ? "false" : "true")) === "true",
    canvasModeToggleCooldownMs:
      optionalPositiveNumber(env.CANVAS_MODE_TOGGLE_COOLDOWN_MS) ??
      (isProduction ? 24 * 60 * 60 * 1_000 : 60 * 1_000),
    firebaseServiceAccountPath: env.FIREBASE_SERVICE_ACCOUNT_PATH,
    firebaseServiceAccountJson: env.FIREBASE_SERVICE_ACCOUNT_JSON,
    canvasUpdatePushTtlMs: optionalPositiveNumber(env.CANVAS_UPDATE_PUSH_TTL_MS),
    pairingConfirmationPushTtlMs: optionalPositiveNumber(env.PAIRING_CONFIRMATION_PUSH_TTL_MS),
    canvasNudgeDelayMs: optionalPositiveNumber(env.CANVAS_NUDGE_DELAY_MS),
    canvasNudgePollIntervalMs: optionalPositiveNumber(env.CANVAS_NUDGE_POLL_INTERVAL_MS),
    canvasNudgePushTtlMs: optionalPositiveNumber(env.CANVAS_NUDGE_PUSH_TTL_MS),
    drawReminderBaseDelayMs: optionalPositiveNumber(env.DRAW_REMINDER_BASE_DELAY_MS),
    drawReminderPollIntervalMs: optionalPositiveNumber(env.DRAW_REMINDER_POLL_INTERVAL_MS),
    drawReminderPushTtlMs: optionalPositiveNumber(env.DRAW_REMINDER_PUSH_TTL_MS),
    drawReminderMaxBackoffDays: optionalPositiveNumber(env.DRAW_REMINDER_MAX_BACKOFF_DAYS),
    supabaseUrl: env.SUPABASE_URL,
    supabaseServiceRoleKey: env.SUPABASE_SERVICE_ROLE_KEY,
    supabaseWallpaperBucket: env.SUPABASE_WALLPAPER_BUCKET,
    wallpaperAdminPassword: env.WALLPAPER_ADMIN_PASSWORD,
  }
}

function optionalPositiveNumber(value: string | undefined): number | undefined {
  if (value === undefined || value.trim() === "") return undefined
  const parsed = Number(value)
  if (!Number.isFinite(parsed) || parsed <= 0) return undefined
  return parsed
}
