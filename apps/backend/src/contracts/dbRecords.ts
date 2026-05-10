import type { CanvasOperationType } from "./canvas.js"
import type { DevicePlatform } from "./devices.js"
import type { InviteStatus } from "./pairing.js"
import type { PresenceSurfaceType } from "./presence.js"

export interface UserRecord {
  id: string
  google_subject: string
  email: string
  google_picture_url: string | null
}

export interface ProfileRecord {
  user_id: string
  display_name: string | null
  partner_display_name: string | null
  anniversary_date: string | null
  onboarding_completed_at: string | null
  partner_profile_updated_at: string | null
  profile_photo_path: string | null
  partner_profile_photo_path: string | null
}

export interface SessionRecord {
  id: string
  user_id: string
  access_token: string
  refresh_token: string
}

export interface InviteRecord {
  id: string
  inviter_user_id: string
  recipient_user_id: string | null
  code: string
  status: InviteStatus
  expires_at: Date | string
}

export interface PairSessionRecord {
  id: string
  user_one_id: string
  user_two_id: string
  canvas_stroke_render_mode: string
  created_at: Date | string
}

export interface CanvasOperationRecord {
  id: string
  pair_session_id: string
  server_revision: number
  client_operation_id: string
  actor_user_id: string
  type: CanvasOperationType
  stroke_id: string | null
  payload_json: unknown
  client_created_at: Date | string
  created_at: Date | string
}

export interface DeviceTokenRow {
  id: string
  user_id: string
  token: string
  platform: DevicePlatform
  app_environment: string
  last_seen_at: Date | string
  revoked_at: Date | string | null
}

export interface UserWallpaperStatusRow {
  user_id: string
  pair_session_id: string | null
  wallpaper_sync_enabled: boolean
  wallpaper_selected_on_home: boolean
  wallpaper_selected_on_lock: boolean
  can_see_latest_drawings: boolean
  has_ever_been_able_to_see: boolean
  updated_at: Date | string
}

export interface UserPresenceSurfaceRow {
  user_id: string
  pair_session_id: string | null
  device_instance_id: string
  surface_type: PresenceSurfaceType
  configured: boolean
  enabled: boolean
  can_see_latest_drawings: boolean
  has_ever_been_able_to_see: boolean
  details_json: unknown
  updated_at: Date | string
}
