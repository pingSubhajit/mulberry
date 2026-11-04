import { createApp } from "./app/createApp.js"
import { loadConfig } from "./infra/config/config.js"

const config = loadConfig()
const app = await createApp({ config })

// Fly stops a machine with SIGINT or SIGTERM. Closing the app first lets it finish sending
// analytics and release the database.
for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.once(signal, () => {
    app.close().then(
      () => process.exit(0),
      (error: unknown) => {
        console.error("[server] shutdown failed", { error: error instanceof Error ? error.message : String(error) })
        process.exit(1)
      },
    )
  })
}

await app.listen({
  host: "0.0.0.0",
  port: config.port,
})
