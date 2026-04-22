import pg from "pg"

const APP_TABLES = [
  "canvas_operations",
  "canvas_snapshots",
  "invites",
  "pair_sessions",
  "device_tokens",
  "sessions",
  "user_profiles",
  "users",
] as const

async function main(): Promise<void> {
  const args = process.argv.slice(2)
  const databaseUrl = args.find((arg) => !arg.startsWith("--")) ?? process.env.DATABASE_URL
  const confirmed = args.includes("--yes")

  if (!databaseUrl) {
    throw new Error("Usage: npm run db:clear -- <DATABASE_URL> --yes")
  }

  if (!confirmed) {
    throw new Error("Refusing to clear database without --yes")
  }

  const pool = new pg.Pool({ connectionString: databaseUrl })
  try {
    await pool.query("BEGIN")
    await pool.query(
      `TRUNCATE TABLE ${APP_TABLES.map((table) => `"${table}"`).join(", ")} RESTART IDENTITY CASCADE`,
    )
    await pool.query("COMMIT")
    console.info(`Cleared ${APP_TABLES.length} tables without dropping schema.`)
  } catch (error) {
    await pool.query("ROLLBACK").catch(() => undefined)
    throw error
  } finally {
    await pool.end()
  }
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : error)
  process.exit(1)
})
