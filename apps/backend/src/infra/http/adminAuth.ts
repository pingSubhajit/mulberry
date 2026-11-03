import { timingSafeEqual } from "node:crypto"
import type { FastifyRequest } from "fastify"
import { HttpError } from "./HttpError.js"

/** The header every admin route reads its password from. */
export const ADMIN_PASSWORD_HEADER = "x-admin-password"

export function readAdminPassword(request: FastifyRequest): string | undefined {
  const header = request.headers[ADMIN_PASSWORD_HEADER]
  return Array.isArray(header) ? header[0] : header
}

/** Throws unless `candidate` matches the configured admin password. */
export function requireAdminPassword(configured: string | undefined, candidate: string | undefined): void {
  if (!configured) {
    throw new HttpError(503, "Admin is not configured: set ADMIN_PASSWORD on the server")
  }
  if (!candidate) {
    throw new HttpError(401, `Send the admin password in the ${ADMIN_PASSWORD_HEADER} header`)
  }
  if (!matches(candidate, configured)) {
    throw new HttpError(401, "Invalid admin password")
  }
}

function matches(candidate: string, configured: string): boolean {
  const left = Buffer.from(candidate)
  const right = Buffer.from(configured)
  return left.length === right.length && timingSafeEqual(left, right)
}
