export interface AppConfig {
  port: number
  databaseUrl: string
  googleClientId: string
  allowDevGoogleTokens: boolean
  firebaseServiceAccountPath?: string
  firebaseServiceAccountJson?: string
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
    firebaseServiceAccountPath: env.FIREBASE_SERVICE_ACCOUNT_PATH,
    firebaseServiceAccountJson: env.FIREBASE_SERVICE_ACCOUNT_JSON,
  }
}
