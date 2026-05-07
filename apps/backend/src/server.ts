import { createApp } from "./app/createApp.js"
import { loadConfig } from "./infra/config/config.js"

const config = loadConfig()
const app = await createApp({ config })

await app.listen({
  host: "0.0.0.0",
  port: config.port,
})
