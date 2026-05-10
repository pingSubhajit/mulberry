export const PresenceSurfaceTypes = ["ANDROID_WALLPAPER", "MACOS_OVERLAY"] as const
export type PresenceSurfaceType = (typeof PresenceSurfaceTypes)[number]

export interface PresenceSurfaceStatus {
  surfaceType: PresenceSurfaceType
  deviceInstanceId: string
  configured: boolean
  enabled: boolean
  canSeeLatestDrawings: boolean
  hasEverBeenAbleToSee: boolean
  details: Record<string, unknown>
  updatedAt: string
}

export interface PresenceSummary {
  canSeeLatestDrawings: boolean
  surfaces: PresenceSurfaceStatus[]
}

export interface UpdatePresenceSurfaceRequest {
  deviceInstanceId: string
  configured: boolean
  enabled: boolean
  canSeeLatestDrawings: boolean
  details?: Record<string, unknown>
}
