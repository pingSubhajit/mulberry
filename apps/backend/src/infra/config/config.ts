import { existsSync, readFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { cwd } from "node:process"

export interface AppConfig {
  port: number
  databaseUrl: string
  googleClientId: string
  googleAllowedClientIds: string[]
  allowDevGoogleTokens: boolean
  firebaseServiceAccountPath?: string
  firebaseServiceAccountJson?: string
  posthogProjectApiKey?: string
  posthogHost?: string
  posthogEnvironment?: string
  posthogDisabled?: boolean
  canvasUpdatePushTtlMs?: number
  pairingConfirmationTtlMs?: number
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
  supabaseStickerBucket?: string
  stickerAdminPassword?: string
  cannySsoPrivateKey?: string
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  env = { ...loadDotenv(), ...env }
  const isProduction = env.NODE_ENV === "production"
  const databaseUrl = env.DATABASE_URL ?? (isProduction ? "" : "postgres://localhost:5432/mulberry")
  const googleClientId = env.GOOGLE_SERVER_CLIENT_ID ?? ""
  const googleAllowedClientIds = parseCsvList(env.GOOGLE_ALLOWED_CLIENT_IDS)
  const posthogProjectApiKey = env.POSTHOG_PROJECT_API_KEY
  const posthogHost = env.POSTHOG_HOST
  const posthogEnvironment = env.POSTHOG_ENVIRONMENT
  const posthogDisabled = (env.POSTHOG_DISABLED ?? "false") === "true"

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
    googleAllowedClientIds,
    allowDevGoogleTokens: (env.ALLOW_DEV_GOOGLE_TOKENS ?? (isProduction ? "false" : "true")) === "true",
    firebaseServiceAccountPath: env.FIREBASE_SERVICE_ACCOUNT_PATH,
    firebaseServiceAccountJson: env.FIREBASE_SERVICE_ACCOUNT_JSON,
    posthogProjectApiKey: posthogProjectApiKey?.trim() ? posthogProjectApiKey : undefined,
    posthogHost: posthogHost?.trim() ? posthogHost : undefined,
    posthogEnvironment: posthogEnvironment?.trim() ? posthogEnvironment : undefined,
    posthogDisabled,
    canvasUpdatePushTtlMs: optionalPositiveNumber(env.CANVAS_UPDATE_PUSH_TTL_MS),
    pairingConfirmationTtlMs: optionalPositiveNumber(env.PAIRING_CONFIRMATION_PUSH_TTL_MS),
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
    supabaseStickerBucket: env.SUPABASE_STICKER_BUCKET ?? env.SUPABASE_WALLPAPER_BUCKET,
    stickerAdminPassword: env.STICKER_ADMIN_PASSWORD ?? env.WALLPAPER_ADMIN_PASSWORD,
    cannySsoPrivateKey: env.CANNY_SSO_PRIVATE_KEY?.trim() ? env.CANNY_SSO_PRIVATE_KEY : undefined,
  }
}

function loadDotenv(startDir = cwd()): Record<string, string> {
  const path = findDotenv(startDir)
  if (!path) return {}
  return parseDotenv(readFileSync(path, "utf8"))
}

function findDotenv(startDir: string): string | null {
  let current = startDir
  while (true) {
    const candidate = join(current, ".env")
    if (existsSync(candidate)) return candidate
    const parent = dirname(current)
    if (parent === current) return null
    current = parent
  }
}

function parseDotenv(contents: string): Record<string, string> {
  const result: Record<string, string> = {}
  for (const rawLine of contents.split(/\r?\n/)) {
    const line = rawLine.trim()
    if (line.length === 0 || line.startsWith("#")) continue
    const assignment = line.startsWith("export ") ? line.slice("export ".length).trim() : line
    const separatorIndex = assignment.indexOf("=")
    if (separatorIndex < 0) continue
    const key = assignment.slice(0, separatorIndex).trim()
    if (key.length === 0) continue
    result[key] = unquoteEnvValue(assignment.slice(separatorIndex + 1).trim())
  }
  return result
}

function unquoteEnvValue(value: string): string {
  if (value.length >= 2 && ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'")))) {
    return value.slice(1, -1)
  }
  return value
}

function parseCsvList(value: string | undefined): string[] {
  if (value === undefined) return []
  return value
    .split(",")
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

function optionalPositiveNumber(value: string | undefined): number | undefined {
  if (value === undefined || value.trim() === "") return undefined
  const parsed = Number(value)
  if (!Number.isFinite(parsed) || parsed <= 0) return undefined
  return parsed
}
