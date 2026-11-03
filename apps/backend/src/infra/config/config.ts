import { existsSync, readFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { cwd } from "node:process"

export interface AppConfig {
  port: number
  databaseUrl: string
  googleClientId: string
  googleAllowedClientIds: string[]
  allowDevGoogleTokens: boolean
  /** The bundle IDs Sign in with Apple identity tokens may be issued to. */
  appleClientIds?: string[]
  /** Accepts `dev-apple:` tokens, as `allowDevGoogleTokens` does for Google. */
  allowDevAppleTokens?: boolean
  /** A Sign in with Apple key, to revoke Apple's tokens when an account is deleted. */
  appleSignInKey?: AppleSignInKey
  /** The developer menus' test pushes and streak reset. Off by default in production. */
  enableDebugRoutes: boolean
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
  supabaseStickerBucket?: string
  /** Unlocks every admin route: wallpapers, stickers and app versions. */
  adminPassword?: string
  stickerLocalStorageDir?: string
  cannySsoPrivateKey?: string
  /**
   * iCloud links to the iOS wallpaper Shortcut, by app bundle ID. Each build's Shortcut calls
   * that build's own App Intents, so dev and production need their own link.
   */
  wallpaperShortcutLinks?: Record<string, string>
}

export interface AppleSignInKey {
  teamId: string
  keyId: string
  /** The key's `.p8` contents, in PEM. */
  privateKey: string
}

/** Mulberry's production and development bundle IDs, on iPhone, iPad and Mac. */
const DEFAULT_APPLE_CLIENT_IDS = ["com.subhajit.mulberry.beta", "com.subhajit.mulberry.dev"]

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
    appleClientIds: env.APPLE_CLIENT_IDS === undefined ? DEFAULT_APPLE_CLIENT_IDS : parseCsvList(env.APPLE_CLIENT_IDS),
    allowDevAppleTokens: (env.ALLOW_DEV_APPLE_TOKENS ?? (isProduction ? "false" : "true")) === "true",
    appleSignInKey: appleSignInKey(env),
    enableDebugRoutes: (env.ENABLE_DEBUG_ROUTES ?? (isProduction ? "false" : "true")) === "true",
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
    supabaseStickerBucket: env.SUPABASE_STICKER_BUCKET ?? env.SUPABASE_WALLPAPER_BUCKET,
    adminPassword: env.ADMIN_PASSWORD?.trim() ? env.ADMIN_PASSWORD.trim() : undefined,
    stickerLocalStorageDir: optionalLocalStickerStorageDir(env.STICKER_LOCAL_STORAGE_DIR, isProduction),
    cannySsoPrivateKey: env.CANNY_SSO_PRIVATE_KEY?.trim() ? env.CANNY_SSO_PRIVATE_KEY : undefined,
    wallpaperShortcutLinks: wallpaperShortcutLinks(env.WALLPAPER_SHORTCUT_LINKS),
  }
}

function appleSignInKey(env: NodeJS.ProcessEnv): AppleSignInKey | undefined {
  const teamId = env.APPLE_TEAM_ID?.trim()
  const keyId = env.APPLE_SIGN_IN_KEY_ID?.trim()
  // Hosts' secret stores often keep a PEM on one line, with its line breaks escaped.
  const privateKey = env.APPLE_SIGN_IN_PRIVATE_KEY?.trim().replaceAll("\\n", "\n")
  if (!teamId || !keyId || !privateKey) return undefined
  return { teamId, keyId, privateKey }
}

const SHORTCUT_LINK_PREFIX = "https://www.icloud.com/shortcuts/"

/** `bundle.id=https://www.icloud.com/shortcuts/…` pairs, comma separated. A malformed entry stops startup. */
export function wallpaperShortcutLinks(value: string | undefined): Record<string, string> {
  const links: Record<string, string> = {}
  for (const entry of parseCsvList(value)) {
    const separator = entry.indexOf("=")
    const bundleId = separator > 0 ? entry.slice(0, separator).trim() : ""
    const link = separator > 0 ? entry.slice(separator + 1).trim() : ""
    if (!bundleId || !link.startsWith(SHORTCUT_LINK_PREFIX) || link.length === SHORTCUT_LINK_PREFIX.length) {
      throw new Error(`WALLPAPER_SHORTCUT_LINKS needs bundle.id=${SHORTCUT_LINK_PREFIX}… pairs, got "${entry}"`)
    }
    links[bundleId] = link
  }
  return links
}

// Local sticker files exist only for development against the local database.
function optionalLocalStickerStorageDir(value: string | undefined, isProduction: boolean): string | undefined {
  const dir = value?.trim()
  if (!dir) return undefined
  if (isProduction) throw new Error("STICKER_LOCAL_STORAGE_DIR is for local development only")
  return dir
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
