// Isolated bridge for Swift/TypeScript contract tests. Never loads .env, never
// connects to PostgreSQL, and binds only loopback on an OS-assigned port.
import { writeFileSync } from "node:fs"
import { newDb } from "pg-mem"
import { createApp } from "../src/app/createApp.js"
import { runMigrations, type Database } from "../src/infra/db/database.js"

const output = process.argv[2]
if (!output) throw new Error("A readiness file is required")
const memoryDb = newDb({ autoCreateForeignKeyIndices: true })
const adapter = memoryDb.adapters.createPg()
const pool = new adapter.Pool()
await runMigrations(pool)
const db: Database = {
  query: pool.query.bind(pool),
  transaction: async (fn) => {
    await pool.query("BEGIN")
    try {
      const result = await fn({ query: pool.query.bind(pool) })
      await pool.query("COMMIT")
      return result
    } catch (error) {
      await pool.query("ROLLBACK")
      throw error
    }
  },
  end: async () => { await pool.end() },
}
const app = await createApp({
  db,
  config: {
    port: 0,
    databaseUrl: "postgres://unused",
    googleClientId: "",
    googleAllowedClientIds: [],
    allowDevGoogleTokens: true,
    posthogDisabled: true,
    cannySsoPrivateKey: "isolated-contract-test-key",
  },
})
const url = await app.listen({ port: 0, host: "127.0.0.1" })
writeFileSync(output, JSON.stringify({ url }), { mode: 0o600 })
process.on("SIGTERM", () => { void app.close().then(() => process.exit(0)) })
