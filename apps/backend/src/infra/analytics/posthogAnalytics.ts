import { PostHog } from "posthog-node"
import type { AppConfig } from "../config/config.js"
import type { AnalyticsCaptureInput, AnalyticsClient } from "./analytics.js"
import { NoopAnalyticsClient } from "./analytics.js"

export function createAnalyticsClient(config: AppConfig): AnalyticsClient {
  if (config.posthogDisabled || !config.posthogProjectApiKey) {
    return new NoopAnalyticsClient()
  }

  const host = config.posthogHost ?? "https://us.i.posthog.com"
  const client = new PostHog(config.posthogProjectApiKey, { host })
  const environment = config.posthogEnvironment ?? (process.env.NODE_ENV ?? "development")
  const baseProperties = {
    app: "mulberry-backend",
    environment,
  } as const

  return {
    enabled: true,
    capture(input: AnalyticsCaptureInput) {
      client.capture({
        distinctId: input.distinctId,
        event: input.event,
        properties: { ...baseProperties, ...(input.properties ?? {}) },
        timestamp: input.timestamp,
        groups: input.groups,
      })
    },
    async shutdown() {
      await client.shutdown()
    },
  }
}
