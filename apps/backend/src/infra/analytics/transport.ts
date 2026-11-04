import type { JsonObject } from "./events.js"

/** One event in PostHog's capture format. */
export interface PostHogMessage {
  uuid: string
  event: string
  distinct_id: string
  timestamp: string
  properties: JsonObject
}

export interface PostHogTransport {
  /** Resolves once PostHog has accepted every message; throws otherwise. */
  sendBatch(messages: PostHogMessage[]): Promise<void>
  /** False when no personal API key is set, so people can't be deleted. */
  readonly canDeletePersons: boolean
  /** Deletes the person with this distinct ID, keeping their events. A missing person is fine. */
  deletePerson(distinctId: string): Promise<void>
}

export interface HttpPostHogTransportOptions {
  projectApiKey: string
  /** Where events go, as https://us.i.posthog.com. */
  ingestionHost: string
  /** PostHog's app API, as https://us.posthog.com, for deleting people. */
  apiHost: string
  personalApiKey?: string
  projectId?: string
  fetch?: typeof fetch
}

const REQUEST_TIMEOUT_MS = 15_000

export class HttpPostHogTransport implements PostHogTransport {
  private readonly fetch: typeof fetch

  constructor(private readonly options: HttpPostHogTransportOptions) {
    this.fetch = options.fetch ?? fetch
  }

  get canDeletePersons(): boolean {
    return Boolean(this.options.personalApiKey && this.options.projectId)
  }

  async sendBatch(messages: PostHogMessage[]): Promise<void> {
    if (messages.length === 0) return
    const response = await this.fetch(`${trimSlash(this.options.ingestionHost)}/batch/`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ api_key: this.options.projectApiKey, batch: messages }),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    })
    if (!response.ok) {
      throw new Error(`PostHog capture failed: ${response.status} ${await response.text().catch(() => "")}`.trim())
    }
  }

  async deletePerson(distinctId: string): Promise<void> {
    const { personalApiKey, projectId } = this.options
    if (!personalApiKey || !projectId) throw new Error("POSTHOG_PERSONAL_API_KEY and POSTHOG_PROJECT_ID are needed to delete people")
    const base = `${trimSlash(this.options.apiHost)}/api/projects/${encodeURIComponent(projectId)}/persons`
    const headers = { Authorization: `Bearer ${personalApiKey}` }

    const lookup = await this.fetch(`${base}/?distinct_id=${encodeURIComponent(distinctId)}`, {
      headers,
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    })
    if (!lookup.ok) throw new Error(`PostHog person lookup failed: ${lookup.status}`)
    const body = (await lookup.json()) as { results?: Array<{ id?: string }> }
    for (const person of body.results ?? []) {
      if (!person.id) continue
      const deletion = await this.fetch(`${base}/${encodeURIComponent(person.id)}/`, {
        method: "DELETE",
        headers,
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      })
      if (!deletion.ok && deletion.status !== 404) throw new Error(`PostHog person deletion failed: ${deletion.status}`)
    }
  }
}

function trimSlash(url: string): string {
  return url.replace(/\/+$/, "")
}
