import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { describe, expect, it } from "vitest"
import { loadConfig } from "../src/infra/config/config.js"

describe("loadConfig", () => {
  it("turns the debug routes off by default in production and on in development", () => {
    // Explicit values, so a developer's own .env doesn't decide the outcome.
    const production = {
      NODE_ENV: "production",
      DATABASE_URL: "postgres://x",
      GOOGLE_SERVER_CLIENT_ID: "server-client",
      STICKER_LOCAL_STORAGE_DIR: "",
    }
    expect(loadConfig(production).enableDebugRoutes).toBe(false)
    expect(loadConfig({ ...production, ENABLE_DEBUG_ROUTES: "true" }).enableDebugRoutes).toBe(true)
    expect(loadConfig({ NODE_ENV: "development" }).enableDebugRoutes).toBe(true)
    expect(loadConfig({ NODE_ENV: "development", ENABLE_DEBUG_ROUTES: "false" }).enableDebugRoutes).toBe(false)
  })

  it("loads root .env values when the process starts from a child directory", () => {
    const root = mkdtempSync(join(tmpdir(), "mulberry-config-"))
    try {
      mkdirSync(join(root, "apps", "backend"), { recursive: true })
      writeFileSync(
        join(root, ".env"),
        [
          "GOOGLE_SERVER_CLIENT_ID=server-client",
          "GOOGLE_ALLOWED_CLIENT_IDS=mac-client,web-client",
          "DATABASE_URL=postgres://env-file",
        ].join("\n"),
        "utf8",
      )

      const previousCwd = process.cwd()
      try {
        process.chdir(join(root, "apps", "backend"))
        const config = loadConfig({})
        expect(config.googleClientId).toBe("server-client")
        expect(config.googleAllowedClientIds).toEqual(["mac-client", "web-client"])
        expect(config.databaseUrl).toBe("postgres://env-file")
      } finally {
        process.chdir(previousCwd)
      }
    } finally {
      rmSync(root, { recursive: true, force: true })
    }
  })

  it("lets explicit process env override .env values", () => {
    const root = mkdtempSync(join(tmpdir(), "mulberry-config-"))
    try {
      writeFileSync(join(root, ".env"), "GOOGLE_SERVER_CLIENT_ID=from-file", "utf8")
      const previousCwd = process.cwd()
      try {
        process.chdir(root)
        const config = loadConfig({ GOOGLE_SERVER_CLIENT_ID: "from-env" })
        expect(config.googleClientId).toBe("from-env")
      } finally {
        process.chdir(previousCwd)
      }
    } finally {
      rmSync(root, { recursive: true, force: true })
    }
  })
})
