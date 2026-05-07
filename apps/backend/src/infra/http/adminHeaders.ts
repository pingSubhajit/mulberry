import type { FastifyRequest } from "fastify"

export function readWallpaperAdminPassword(request: FastifyRequest): string | undefined {
  const header = request.headers["x-wallpaper-admin-password"]
  return Array.isArray(header) ? header[0] : header
}

export function readStickerAdminPassword(request: FastifyRequest): string | undefined {
  const header = request.headers["x-sticker-admin-password"]
  return Array.isArray(header) ? header[0] : header
}

