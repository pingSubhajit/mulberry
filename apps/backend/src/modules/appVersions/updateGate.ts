import type { FastifyInstance, FastifyRequest } from "fastify"
import { APP_PLATFORMS } from "../../contracts/appVersions.js"
import type { AppVersionsService } from "./service.js"

/** The apps name their platform and build number in these headers on every request. */
export const APP_PLATFORM_HEADER = "x-app-platform"
export const APP_BUILD_HEADER = "x-app-build"

/** The reply's `code` for a build below its platform's minimum. The apps show their update screen on it. */
export const UPDATE_REQUIRED_CODE = "UPDATE_REQUIRED"

/**
 * Turns away builds below their platform's minimum with 426. A request without both headers
 * passes: builds from before the gate send neither, and neither does the website.
 */
export function registerUpdateGate(app: FastifyInstance, service: AppVersionsService): void {
  app.addHook("onRequest", async (request, reply) => {
    const platform = APP_PLATFORMS.find((candidate) => candidate === header(request, APP_PLATFORM_HEADER))
    const build = Number(header(request, APP_BUILD_HEADER) ?? Number.NaN)
    if (!platform || !Number.isSafeInteger(build)) return

    const minimum = service.minimumBuild(platform)
    if (minimum !== null && build < minimum) {
      return reply.code(426).send({ message: "Update Mulberry to keep going.", code: UPDATE_REQUIRED_CODE })
    }
  })
}

function header(request: FastifyRequest, name: string): string | undefined {
  const value = request.headers[name]
  return Array.isArray(value) ? value[0] : value
}
