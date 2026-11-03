import { sign } from "node:crypto"
import { connect, type ClientHttp2Session } from "node:http2"
import type { ApnsKey, ApnsKeys } from "../config/config.js"

/** Where an Apple device's widgets can be told to reload: WidgetKit's token for the app's widgets. */
export interface WidgetPushTarget {
  /** The widget push token, as hex. */
  token: string
  /** The app's bundle ID; APNs takes widget pushes on `<bundle ID>.push-type.widgets`. */
  bundleId: string
  environment: ApnsEnvironment
}

export type ApnsEnvironment = "sandbox" | "production"

/** `unregistered`: the token is gone for good, so forget it. `failed`: try again on the next change. */
export type WidgetPushResult = "sent" | "unregistered" | "failed"

/**
 * Reloads an Apple device's widgets. WidgetKit acts on these pushes at once, while a reload asked
 * for by an app that a silent push woke in the background can wait indefinitely. Firebase can't
 * carry them, so they go to APNs directly.
 */
export interface WidgetPushSender {
  reloadWidgets(target: WidgetPushTarget): Promise<WidgetPushResult>
}

export class NoopWidgetPushSender implements WidgetPushSender {
  readonly sent: WidgetPushTarget[] = []

  async reloadWidgets(target: WidgetPushTarget): Promise<WidgetPushResult> {
    this.sent.push(target)
    return "sent"
  }
}

export function createWidgetPushSender(keys: ApnsKeys | undefined): WidgetPushSender {
  return keys && (keys.sandbox || keys.production) ? new ApnsWidgetPushSender(keys) : new NoopWidgetPushSender()
}

const HOSTS: Record<ApnsEnvironment, string> = {
  sandbox: "https://api.sandbox.push.apple.com",
  production: "https://api.push.apple.com",
}

/** APNs accepts a provider token for an hour; renew well before that. */
const TOKEN_LIFETIME_MS = 40 * 60 * 1_000

export class ApnsWidgetPushSender implements WidgetPushSender {
  private readonly sessions = new Map<ApnsEnvironment, ClientHttp2Session>()
  private readonly providerTokens = new Map<ApnsEnvironment, { value: string; issuedAt: number }>()

  constructor(private readonly keys: ApnsKeys) {}

  async reloadWidgets(target: WidgetPushTarget): Promise<WidgetPushResult> {
    const key = this.keys[target.environment]
    if (!key) {
      console.warn("[push] no APNs key for widget push environment", { environment: target.environment })
      return "failed"
    }
    try {
      const { status, reason } = await this.post(target, key)
      if (status === 200) return "sent"
      if (status === 410 || reason === "BadDeviceToken" || reason === "Unregistered") return "unregistered"
      console.warn("[push] widget push refused", { status, reason, environment: target.environment })
      return "failed"
    } catch (error) {
      console.warn("[push] widget push failed", {
        environment: target.environment,
        error: error instanceof Error ? error.message : String(error),
      })
      this.sessions.get(target.environment)?.close()
      this.sessions.delete(target.environment)
      return "failed"
    }
  }

  close(): void {
    for (const session of this.sessions.values()) session.close()
    this.sessions.clear()
  }

  private post(target: WidgetPushTarget, key: ApnsKey): Promise<{ status: number; reason?: string }> {
    return new Promise((resolve, reject) => {
      const request = this.session(target.environment).request({
        ":method": "POST",
        ":path": `/3/device/${target.token}`,
        authorization: `bearer ${this.providerToken(target.environment, key)}`,
        "apns-topic": `${target.bundleId}.push-type.widgets`,
        "apns-push-type": "widgets",
        "apns-priority": "10",
      })
      let status = 0
      let body = ""
      request.setEncoding("utf8")
      request.on("response", (headers) => {
        status = Number(headers[":status"] ?? 0)
      })
      request.on("data", (chunk: string) => {
        body += chunk
      })
      request.on("end", () => resolve({ status, reason: apnsReason(body) }))
      request.on("error", reject)
      request.setTimeout(10_000, () => request.close())
      request.end(JSON.stringify({ aps: { "content-changed": true } }))
    })
  }

  private session(environment: ApnsEnvironment): ClientHttp2Session {
    const existing = this.sessions.get(environment)
    if (existing && !existing.closed && !existing.destroyed) return existing
    const session = connect(HOSTS[environment])
    session.on("error", () => this.sessions.delete(environment))
    session.on("close", () => this.sessions.delete(environment))
    session.unref()
    this.sessions.set(environment, session)
    return session
  }

  private providerToken(environment: ApnsEnvironment, key: ApnsKey): string {
    const cached = this.providerTokens.get(environment)
    if (cached && Date.now() - cached.issuedAt < TOKEN_LIFETIME_MS) return cached.value
    const issuedAt = Date.now()
    const value = apnsProviderToken(this.keys.teamId, key, issuedAt)
    this.providerTokens.set(environment, { value, issuedAt })
    return value
  }
}

/** APNs' ES256 provider token for a team's auth key. */
export function apnsProviderToken(teamId: string, key: ApnsKey, now = Date.now()): string {
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString("base64url")
  const unsigned = `${encode({ alg: "ES256", kid: key.keyId })}.${encode({ iss: teamId, iat: Math.floor(now / 1_000) })}`
  const signature = sign("sha256", Buffer.from(unsigned), { key: key.privateKey, dsaEncoding: "ieee-p1363" })
  return `${unsigned}.${signature.toString("base64url")}`
}

function apnsReason(body: string): string | undefined {
  if (!body) return undefined
  try {
    const parsed: unknown = JSON.parse(body)
    if (typeof parsed === "object" && parsed !== null && "reason" in parsed && typeof parsed.reason === "string") {
      return parsed.reason
    }
  } catch {
    // Not JSON: no reason to report.
  }
  return undefined
}
