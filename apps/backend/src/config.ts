export interface AppConfig {
  port: number
  databaseUrl: string
  googleClientId: string
  allowDevGoogleTokens: boolean
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  return {
    port: Number(env.PORT ?? "8080"),
    databaseUrl: env.DATABASE_URL ?? "postgres://localhost:5432/mulberry",
    googleClientId: env.GOOGLE_SERVER_CLIENT_ID ?? "",
    allowDevGoogleTokens: (env.ALLOW_DEV_GOOGLE_TOKENS ?? "true") === "true",
  }
}
