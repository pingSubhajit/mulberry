import type { FastifyInstance } from "fastify"
import { HttpError } from "../../infra/http/HttpError.js"
import { readAdminPassword, requireAdminPassword } from "../../infra/http/adminAuth.js"

export function registerCatalogAdminHooks(app: FastifyInstance, password?: string): void {
  app.addHook("preValidation", async (request) => {
    if (!/^\/admin\/(stickers|wallpapers)(\/|$)/.test(request.url)) return
    requireAdminPassword(password, readAdminPassword(request))
    if (request.body === undefined) return
    if (!request.body || typeof request.body !== "object" || Array.isArray(request.body) || Buffer.isBuffer(request.body)) {
      throw new HttpError(400, "Supply a JSON object", { code: "INVALID_CATALOG_INPUT" })
    }
    const body = request.body as Record<string, unknown>
    let allowed: Record<string, string> = {}
    if (request.method === "DELETE" || request.url.split("?")[0].endsWith("/restore")) allowed = { confirm: "boolean" }
    else if (request.method === "POST" && request.url.split("?")[0].endsWith("/copy")) allowed = { newVersion: "number" }
    else if (request.method === "PATCH" && /\/stickers\/[^/]+(?:\?|$)/.test(request.url)) allowed = { sortOrder: "number" }
    else if (request.method === "PATCH") allowed = { title: "string", description: "string", sortOrder: "number", published: "boolean", ...(request.url.startsWith("/admin/stickers") ? { featured: "boolean" } : {}) }
    for (const [key, value] of Object.entries(body)) {
      if (!allowed[key] || typeof value !== allowed[key] || (typeof value === "number" && (!Number.isSafeInteger(value) || value < -2147483648 || value > 2147483647))) {
        throw new HttpError(400, `Invalid field ${key}. Use the documented fields and types.`, { code: "INVALID_CATALOG_INPUT" })
      }
    }
    if (typeof body.title === "string" && !body.title.trim()) throw new HttpError(400, "title must not be empty", { code: "INVALID_CATALOG_INPUT" })
  })
  app.addHook("preSerialization", async (request, _reply, payload) => {
    if (!/^\/admin\/(stickers|wallpapers)(\/|$)/.test(request.url) || !payload || typeof payload !== "object") return payload
    const result = payload as Record<string, unknown>
    if (_reply.statusCode >= 400) {
      return { ...result, code: result.code ?? (_reply.statusCode === 401 ? "ADMIN_UNAUTHORIZED" : _reply.statusCode === 404 ? "CATALOG_NOT_FOUND" : _reply.statusCode === 503 ? "CATALOG_UNAVAILABLE" : _reply.statusCode >= 500 ? "CATALOG_FAILURE" : "INVALID_CATALOG_INPUT") }
    }
    return { ...result, message: result.message ?? (request.method === "GET" ? "Catalog loaded." : "Catalog change saved.") }
  })
}

export function multipartNumber(value: string | undefined, fallback: number): number {
  if (value === undefined) return fallback
  const number = value.trim() ? Number(value) : NaN
  if (!Number.isSafeInteger(number) || number < -2147483648 || number > 2147483647) throw new HttpError(400, "Numeric fields must be integers", { code: "INVALID_CATALOG_INPUT" })
  return number
}

export function multipartBoolean(value: string | undefined): boolean {
  if (value === undefined) return false
  if (value !== "true" && value !== "false") throw new HttpError(400, "Boolean fields must be true or false", { code: "INVALID_CATALOG_INPUT" })
  return value === "true"
}

export function validateMultipartFields(fields: Record<string, string>, allowed: string[]): void {
  for (const key of Object.keys(fields)) if (!allowed.includes(key)) {
    throw new HttpError(400, `Unknown field ${key}`, { code: "INVALID_CATALOG_INPUT" })
  }
}
