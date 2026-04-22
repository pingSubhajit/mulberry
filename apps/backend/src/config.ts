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
  return {
    port: Number(env.PORT ?? "8080"),
    databaseUrl: env.DATABASE_URL ?? "postgres://localhost:5432/mulberry",
    googleClientId: env.GOOGLE_SERVER_CLIENT_ID ?? "",
    allowDevGoogleTokens: (env.ALLOW_DEV_GOOGLE_TOKENS ?? (isProduction ? "false" : "true")) === "true",
    firebaseServiceAccountPath: env.FIREBASE_SERVICE_ACCOUNT_PATH,
    firebaseServiceAccountJson: env.FIREBASE_SERVICE_ACCOUNT_JSON,
  }
}
