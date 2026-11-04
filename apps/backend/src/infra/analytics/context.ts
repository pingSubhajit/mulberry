import { AsyncLocalStorage } from "node:async_hooks"
import type { FastifyInstance, FastifyRequest } from "fastify"
import { ClientPlatforms, DeviceFamilies, type ClientContext } from "./events.js"

/** The apps describe themselves in these headers on every request. */
export const PLATFORM_HEADER = "x-app-platform"
export const BUILD_HEADER = "x-app-build"
export const DEVICE_FAMILY_HEADER = "x-device-family"
export const ANONYMOUS_ID_HEADER = "x-analytics-anonymous-id"

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

const EMPTY_CONTEXT: ClientContext = { platform: null, deviceFamily: null, appBuild: null, anonymousId: null }
const storage = new AsyncLocalStorage<ClientContext>()

/**
 * Makes the requesting app's platform, device and build available to everything the request
 * runs, so analytics can describe the client without every service passing it along.
 */
export function registerClientContext(app: FastifyInstance): void {
  app.addHook("onRequest", (request, _reply, done) => {
    storage.run(clientContextOf(request), done)
  })
}

/** The app behind the current request; empty outside a request, as in background jobs. */
export function currentClientContext(): ClientContext {
  return storage.getStore() ?? EMPTY_CONTEXT
}

export function clientContextOf(request: FastifyRequest): ClientContext {
  const platform = ClientPlatforms.find((candidate) => candidate === header(request, PLATFORM_HEADER)?.toLowerCase())
  const deviceFamily = DeviceFamilies.find((candidate) => candidate === header(request, DEVICE_FAMILY_HEADER)?.toLowerCase())
  const build = Number(header(request, BUILD_HEADER) ?? Number.NaN)
  return {
    platform: platform ?? null,
    deviceFamily: deviceFamily ?? null,
    appBuild: Number.isSafeInteger(build) && build > 0 ? build : null,
    anonymousId: anonymousIdOf(header(request, ANONYMOUS_ID_HEADER)),
  }
}

export function anonymousIdOf(raw: string | undefined): string | null {
  const value = raw?.trim().toLowerCase()
  return value && UUID_PATTERN.test(value) ? value : null
}

export function isUuid(value: string): boolean {
  return UUID_PATTERN.test(value)
}

/** The PostHog distinct ID for an install that hasn't signed in yet. */
export function anonymousDistinctId(anonymousId: string): string {
  return `anon:${anonymousId}`
}

function header(request: FastifyRequest, name: string): string | undefined {
  const value = request.headers[name]
  return Array.isArray(value) ? value[0] : value
}
