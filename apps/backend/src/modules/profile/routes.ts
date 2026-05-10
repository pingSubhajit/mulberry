import type { FastifyInstance } from "fastify"
import { HttpError } from "../../infra/http/HttpError.js"
import { requireBearerToken } from "../../infra/http/auth.js"
import { readRequiredImageUpload, readRequiredPartnerProfileUpdateWithPhoto } from "../../infra/http/uploads.js"
import type { ProfileService } from "./service.js"

export function registerProfileRoutes(app: FastifyInstance, service: ProfileService): void {
  app.put("/me/profile", async (request) => {
    const body = request.body as {
      displayName?: string
      partnerDisplayName?: string
      anniversaryDate?: string
    }
    return service.updateProfile(requireBearerToken(request), {
      displayName: body.displayName ?? "",
      partnerDisplayName: body.partnerDisplayName ?? "",
      anniversaryDate: body.anniversaryDate ?? "",
    })
  })

  app.put("/me/display-name", async (request) => {
    const body = request.body as { displayName?: string }
    return service.updateDisplayName(requireBearerToken(request), body.displayName ?? "")
  })

  app.put("/me/profile-photo", async (request) => {
    return service.updateProfilePhoto(requireBearerToken(request), await readRequiredImageUpload(request))
  })

  app.put("/me/partner-profile", async (request) => {
    const body = request.body as {
      partnerDisplayName?: string
      anniversaryDate?: string
    }
    return service.updatePartnerProfile(requireBearerToken(request), {
      partnerDisplayName: body.partnerDisplayName ?? "",
      anniversaryDate: body.anniversaryDate ?? "",
    })
  })

  app.put("/me/partner-profile-with-photo", async (request) => {
    const body = await readRequiredPartnerProfileUpdateWithPhoto(request)
    return service.updatePartnerProfileWithPhoto(requireBearerToken(request), {
      partnerDisplayName: body.partnerDisplayName,
      anniversaryDate: body.anniversaryDate,
      upload: body.image,
    })
  })

  app.put("/me/partner-profile-photo", async (request) => {
    return service.updatePartnerProfilePhoto(requireBearerToken(request), await readRequiredImageUpload(request))
  })

  app.put("/me/canvas-stroke-render-mode", async (request) => {
    const body = request.body as { canvasStrokeRenderMode?: unknown }
    return service.updateCanvasStrokeRenderMode(requireBearerToken(request), {
      canvasStrokeRenderMode: body?.canvasStrokeRenderMode,
    })
  })

  app.put("/me/wallpaper-status", async (request) => {
    const body = request.body as {
      wallpaperSyncEnabled?: unknown
      wallpaperSelectedOnHome?: unknown
      wallpaperSelectedOnLock?: unknown
    }
    if (typeof body?.wallpaperSyncEnabled !== "boolean") {
      throw new HttpError(400, "wallpaperSyncEnabled must be a boolean")
    }
    if (typeof body?.wallpaperSelectedOnHome !== "boolean") {
      throw new HttpError(400, "wallpaperSelectedOnHome must be a boolean")
    }
    if (typeof body?.wallpaperSelectedOnLock !== "boolean") {
      throw new HttpError(400, "wallpaperSelectedOnLock must be a boolean")
    }
    return service.updateWallpaperStatus(requireBearerToken(request), {
      wallpaperSyncEnabled: body.wallpaperSyncEnabled,
      wallpaperSelectedOnHome: body.wallpaperSelectedOnHome,
      wallpaperSelectedOnLock: body.wallpaperSelectedOnLock,
    })
  })

  app.put("/me/presence-surfaces/:surfaceType", async (request) => {
    const params = request.params as { surfaceType?: string }
    const body = request.body as {
      deviceInstanceId?: unknown
      configured?: unknown
      enabled?: unknown
      canSeeLatestDrawings?: unknown
      details?: unknown
    }
    if (typeof body?.deviceInstanceId !== "string") {
      throw new HttpError(400, "deviceInstanceId must be a string")
    }
    if (typeof body?.configured !== "boolean") {
      throw new HttpError(400, "configured must be a boolean")
    }
    if (typeof body?.enabled !== "boolean") {
      throw new HttpError(400, "enabled must be a boolean")
    }
    if (typeof body?.canSeeLatestDrawings !== "boolean") {
      throw new HttpError(400, "canSeeLatestDrawings must be a boolean")
    }
    return service.updatePresenceSurface(requireBearerToken(request), params.surfaceType, {
      deviceInstanceId: body.deviceInstanceId,
      configured: body.configured,
      enabled: body.enabled,
      canSeeLatestDrawings: body.canSeeLatestDrawings,
      details: body.details && typeof body.details === "object" && !Array.isArray(body.details)
        ? body.details as Record<string, unknown>
        : undefined,
    })
  })
}
