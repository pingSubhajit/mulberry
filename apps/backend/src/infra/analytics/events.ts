import { PresenceSurfaceTypes, type PresenceSurfaceType } from "../../contracts/presence.js"
import type { ReactionType } from "../../contracts/reactions.js"

/**
 * Every event Mulberry sends to PostHog, with its properties. `docs/analytics/tracking-plan.md`
 * describes each one; change both together.
 */

export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue }
export type JsonObject = { [key: string]: JsonValue }

export const ClientPlatforms = ["ios", "android", "macos"] as const
export type ClientPlatform = (typeof ClientPlatforms)[number]

export const DeviceFamilies = ["iphone", "ipad", "mac", "android_phone", "android_tablet"] as const
export type DeviceFamily = (typeof DeviceFamilies)[number]

export type SignInMethod = "google" | "apple"

export const InviteRedeemFailureReasons = ["not_found", "expired", "no_longer_valid", "own_invite", "rate_limited"] as const
export type InviteRedeemFailureReason = (typeof InviteRedeemFailureReasons)[number]

export type PairEndedReason = "unpaired" | "account_deleted"

export const PushTypes = [
  "CANVAS_NUDGE",
  "DRAW_REMINDER",
  "REACTION",
  "PAIRING_CONFIRMED",
  "PAIRING_DISCONNECTED",
  "PARTNER_VISIBILITY_CHANGED",
  "ANNIVERSARY_CHANGED",
  "OTHER",
] as const
export type PushType = (typeof PushTypes)[number]
/** The push types the server records sending; `OTHER` exists only for the apps' opens. */
export type SentPushType = Exclude<PushType, "OTHER">

export const OnboardingSteps = [
  "welcome",
  "sign_in",
  "profile_name",
  "partner_details",
  "pairing_choice",
  "invite_code_shown",
  "redeem_code_entry",
  "invite_review",
  "waiting_for_partner",
  "paired_celebration",
  "notifications_permission",
  "wallpaper_setup",
  "onboarding_finished",
] as const
export type OnboardingStep = (typeof OnboardingSteps)[number]

export const WallpaperSetupSteps = [
  "intro",
  "choose_background",
  "set_wallpaper",
  "install_shortcut",
  "create_automation",
  "grant_permission",
  "enable_overlay",
  "run_test",
  "finished",
] as const
export type WallpaperSetupStep = (typeof WallpaperSetupSteps)[number]

export const WallpaperSetupOutcomes = ["viewed", "completed", "failed", "skipped"] as const
export type WallpaperSetupOutcome = (typeof WallpaperSetupOutcomes)[number]

export { PresenceSurfaceTypes }
export type { PresenceSurfaceType }

export const StreakMilestones = [3, 7, 14, 30, 50, 100, 200, 365] as const

export type CanvasBatchProperties = {
  latest_revision: number
  snapshot_revision: number
  durable_ops_total: number
  strokes_finished_count: number
  strokes_deleted_count: number
  canvas_cleared_count: number
  text_added_count: number
  text_updated_count: number
  text_deleted_count: number
  sticker_added_count: number
  sticker_updated_count: number
  sticker_deleted_count: number
  sticker_pack_keys: string[]
  canvas_stroke_render_mode: string
}

/** What made a pair active; `unknown` for days recorded before this was kept. */
export type PairActivitySource = "canvas" | "reaction" | "unknown"

/** Events the server records. */
export type ServerEventProperties = {
  mulberry_user_signed_up: { sign_in_method: SignInMethod }
  mulberry_app_opened: Record<string, never>
  mulberry_onboarding_completed: Record<string, never>
  mulberry_invite_created: { invite_id: string; expires_at: string }
  mulberry_invite_redeem_failed: { reason: InviteRedeemFailureReason }
  mulberry_invite_redeemed: { invite_id: string; inviter_user_id: string }
  mulberry_invite_declined: { invite_id: string; inviter_user_id: string }
  mulberry_invite_expired: { invite_id: string }
  mulberry_invite_accepted: { invite_id: string; inviter_user_id: string }
  mulberry_pair_created: { invite_id: string | null; inviter_user_id: string }
  mulberry_pair_ended: { reason: PairEndedReason; pair_age_days: number; pair_active_days: number }
  mulberry_account_deleted: { was_paired: boolean; account_age_days: number; is_internal: boolean }
  mulberry_canvas_batch_ingested: CanvasBatchProperties
  mulberry_canvas_first_action: { first_action_type: string; canvas_stroke_render_mode: string }
  mulberry_reaction_sent: { reaction_type: ReactionType; canvas_stroke_render_mode: string }
  mulberry_pair_active_day: { activity_source: PairActivitySource; activity_day: string; canvas_stroke_render_mode: string }
  mulberry_pair_active_week: { activity_source: PairActivitySource; week_start_day: string; canvas_stroke_render_mode: string }
  mulberry_streak_milestone: { streak_days: number }
  mulberry_wallpaper_first_visible: { surface_type: PresenceSurfaceType }
  mulberry_wallpaper_visibility_changed: { surface_type: PresenceSurfaceType; can_see_latest_drawings: boolean }
  mulberry_push_sent: { push_type: SentPushType }
}

/** Events the apps send through `POST /analytics/events`. */
export type ClientEventProperties = {
  mulberry_onboarding_step_viewed: { step: OnboardingStep }
  mulberry_wallpaper_setup_step: {
    surface_type: PresenceSurfaceType
    step: WallpaperSetupStep
    outcome: WallpaperSetupOutcome
    failure_reason?: string
  }
  mulberry_push_opened: { push_type: PushType }
}

export type AnalyticsEventProperties = ServerEventProperties & ClientEventProperties
export type AnalyticsEventName = keyof AnalyticsEventProperties
export type ServerEventName = keyof ServerEventProperties
export type ClientEventName = keyof ClientEventProperties

export const ClientEventNames = [
  "mulberry_onboarding_step_viewed",
  "mulberry_wallpaper_setup_step",
  "mulberry_push_opened",
] as const satisfies readonly ClientEventName[]

/** Where an event came from: the server, an app, or the one-time backfill of earlier history. */
export type EventSource = "server" | "app" | "backfill"

/** The app a request came from, read from its headers. */
export type ClientContext = {
  platform: ClientPlatform | null
  deviceFamily: DeviceFamily | null
  appBuild: number | null
  anonymousId: string | null
}
