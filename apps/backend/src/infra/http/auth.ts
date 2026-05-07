import type { FastifyRequest } from "fastify"
import { HttpError } from "./HttpError.js"

export function requireBearerToken(request: FastifyRequest): string {
  const header = request.headers.authorization
  if (!header?.startsWith("Bearer ")) {
    throw new HttpError(401, "Missing bearer token")
  }
  return header.slice("Bearer ".length)
}

