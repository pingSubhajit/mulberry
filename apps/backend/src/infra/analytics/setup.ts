import type { AppConfig } from "../config/config.js"
import type { Database } from "../db/database.js"
import { Analytics } from "./recorder.js"
import { HttpPostHogTransport, type PostHogTransport } from "./transport.js"
import { AnalyticsOutboxWorker, type OutboxWorkerOptions } from "./worker.js"

export interface AnalyticsRuntime {
  analytics: Analytics
  /** Null when analytics is off. */
  worker: AnalyticsOutboxWorker | null
}

export interface AnalyticsRuntimeOptions {
  /** Replaces PostHog's HTTP API, as in tests. */
  transport?: PostHogTransport
  worker?: OutboxWorkerOptions
}

const DEFAULT_INGESTION_HOST = "https://us.i.posthog.com"

/**
 * Analytics needs a PostHog key, and is on in production and off elsewhere unless POSTHOG_DISABLED
 * says otherwise. Outside production everyone recorded is internal.
 */
export function createAnalyticsRuntime(config: AppConfig, db: Database, options: AnalyticsRuntimeOptions = {}): AnalyticsRuntime {
  const environment = config.posthogEnvironment ?? "development"
  const production = environment === "production"
  const transport = options.transport ?? httpTransport(config)
  const disabled = config.posthogDisabled ?? !production
  const enabled = !disabled && transport !== null
  const analytics = new Analytics({
    enabled,
    environment,
    accounts: { production, emails: new Set(config.posthogInternalEmails ?? []) },
  })
  if (!enabled || !transport) return { analytics, worker: null }
  return { analytics, worker: new AnalyticsOutboxWorker(db, transport, analytics, options.worker) }
}

function httpTransport(config: AppConfig): PostHogTransport | null {
  if (!config.posthogProjectApiKey) return null
  const ingestionHost = config.posthogHost ?? DEFAULT_INGESTION_HOST
  return new HttpPostHogTransport({
    projectApiKey: config.posthogProjectApiKey,
    ingestionHost,
    apiHost: config.posthogApiHost ?? ingestionHost.replace("://us.i.", "://us.").replace("://eu.i.", "://eu."),
    personalApiKey: config.posthogPersonalApiKey,
    projectId: config.posthogProjectId,
  })
}
