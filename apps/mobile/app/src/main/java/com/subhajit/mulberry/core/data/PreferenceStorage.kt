package com.subhajit.mulberry.core.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

const val APP_PREFERENCES_FILE = "app_preferences"

object PreferenceStorage {
    val authStatus = stringPreferencesKey("auth_status")
    val accessToken = stringPreferencesKey("access_token")
    val refreshToken = stringPreferencesKey("refresh_token")
    val sessionUserId = stringPreferencesKey("session_user_id")
    val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
    val userEmail = stringPreferencesKey("user_email")
    val userPhotoUrl = stringPreferencesKey("user_photo_url")
    val userDisplayName = stringPreferencesKey("user_display_name")
    val partnerPhotoUrl = stringPreferencesKey("partner_photo_url")
    val partnerDisplayName = stringPreferencesKey("partner_display_name")
    val canvasStrokeRenderMode = stringPreferencesKey("canvas_stroke_render_mode")
    val partnerWallpaperStatusUpdatedAt =
        stringPreferencesKey("partner_wallpaper_status_updated_at")
    val partnerWallpaperSyncEnabled =
        booleanPreferencesKey("partner_wallpaper_sync_enabled")
    val partnerWallpaperSelectedOnHome =
        booleanPreferencesKey("partner_wallpaper_selected_on_home")
    val partnerWallpaperSelectedOnLock =
        booleanPreferencesKey("partner_wallpaper_selected_on_lock")
    val partnerCanSeeLatestDrawings =
        booleanPreferencesKey("partner_can_see_latest_drawings")
    val partnerHasEverBeenAbleToSee =
        booleanPreferencesKey("partner_has_ever_been_able_to_see")
    val anniversaryDate = stringPreferencesKey("anniversary_date")
    val partnerProfileNextUpdateAt = stringPreferencesKey("partner_profile_next_update_at")
    val pairedAt = stringPreferencesKey("paired_at")
    val currentStreakDays = intPreferencesKey("current_streak_days")
    val pairingStatus = stringPreferencesKey("pairing_status")
    val pairSessionId = stringPreferencesKey("pair_session_id")
    val pendingInviteId = stringPreferencesKey("pending_invite_id")
    val pendingInviteCode = stringPreferencesKey("pending_invite_code")
    val pendingInviterDisplayName = stringPreferencesKey("pending_inviter_display_name")
    val pendingRecipientDisplayName = stringPreferencesKey("pending_recipient_display_name")
    val pendingInviteStatus = stringPreferencesKey("pending_invite_status")
    val pendingInboundInviteCode = stringPreferencesKey("pending_inbound_invite_code")
    val pendingInboundInviteReceivedAtMs = longPreferencesKey("pending_inbound_invite_received_at_ms")
    val pendingInboundInviteSource = stringPreferencesKey("pending_inbound_invite_source")
    val pendingInboundInviteDismissedAtMs = longPreferencesKey("pending_inbound_invite_dismissed_at_ms")
    val installReferrerChecked = booleanPreferencesKey("install_referrer_checked")
    val wallpaperConfigured = booleanPreferencesKey("wallpaper_configured")
    val backgroundImagePath = stringPreferencesKey("background_image_path")
    val backgroundImageUpdatedAt = stringPreferencesKey("background_image_updated_at")
    val backgroundImagePresetResId = stringPreferencesKey("background_image_preset_res_id")
    val backgroundImageRemoteWallpaperId =
        stringPreferencesKey("background_image_remote_wallpaper_id")
    val wallpaperSyncEnabled = booleanPreferencesKey("wallpaper_sync_enabled")
    val wallpaperSyncPausedReminderCount = intPreferencesKey("wallpaper_sync_paused_reminder_count")
    val wallpaperSyncPausedSinceMs = longPreferencesKey("wallpaper_sync_paused_since_ms")
    val onboardingDraftDisplayName = stringPreferencesKey("onboarding_draft_display_name")
    val onboardingDraftPartnerDisplayName =
        stringPreferencesKey("onboarding_draft_partner_display_name")
    val onboardingDraftAnniversaryDate =
        stringPreferencesKey("onboarding_draft_anniversary_date")
    val syncLastAppliedServerRevision =
        stringPreferencesKey("sync_last_applied_server_revision")
    val syncPairSessionId = stringPreferencesKey("sync_pair_session_id")
    val syncLastError = stringPreferencesKey("sync_last_error")
    val syncPendingOperationsJson = stringPreferencesKey("sync_pending_operations_json")
    val fcmToken = stringPreferencesKey("fcm_token")
    val fcmRegisteredToken = stringPreferencesKey("fcm_registered_token")
    val fcmRegisteredUserId = stringPreferencesKey("fcm_registered_user_id")
    val installationDeviceId = stringPreferencesKey("installation_device_id")
    val lastUsedReactionType = stringPreferencesKey("last_used_reaction_type")
    val pendingReactionGeneration = longPreferencesKey("pending_reaction_generation")
    val pendingReactionPairSessionId = stringPreferencesKey("pending_reaction_pair_session_id")
    val pendingReactionHeartCount = intPreferencesKey("pending_reaction_heart_count")
    val pendingReactionHugCount = intPreferencesKey("pending_reaction_hug_count")
    val pendingReactionKissCount = intPreferencesKey("pending_reaction_kiss_count")
    val pendingReactionSmileCount = intPreferencesKey("pending_reaction_smile_count")
    val pendingReactionLaughCount = intPreferencesKey("pending_reaction_laugh_count")
    val pendingReactionSparkleCount = intPreferencesKey("pending_reaction_sparkle_count")
    val pendingReactionReceivedAtMs = longPreferencesKey("pending_reaction_received_at_ms")
    val developerOptionsEnabled = booleanPreferencesKey("developer_options_enabled")
    val reviewMilestone3Reached = booleanPreferencesKey("review_milestone_3_reached")
    val reviewAttemptCount = intPreferencesKey("review_attempt_count")
    val reviewLastAttemptAtMs = longPreferencesKey("review_last_attempt_at_ms")
    val reviewNextEligibleAtMs = longPreferencesKey("review_next_eligible_at_ms")

    val inAppUpdateDeclinedVersionCode = intPreferencesKey("in_app_update_declined_version_code")
    val inAppUpdateDeclinedAtMs = longPreferencesKey("in_app_update_declined_at_ms")

    val whatsNewLastSeenVersionName = stringPreferencesKey("whats_new_last_seen_version_name")
    val whatsNewPendingVersionName = stringPreferencesKey("whats_new_pending_version_name")
    val whatsNewNextRetryAtMs = longPreferencesKey("whats_new_next_retry_at_ms")
    val whatsNewRetryAttempt = intPreferencesKey("whats_new_retry_attempt")

    val brushToolGuideShownUserIds = stringSetPreferencesKey("brush_tool_guide_shown_user_ids")

    val placeholderPairingControlsOverride =
        booleanPreferencesKey("flag_placeholder_pairing_controls_override")
    val wallpaperSetupCtaOverride =
        booleanPreferencesKey("flag_wallpaper_setup_cta_override")
    val developerBootstrapActionsOverride =
        booleanPreferencesKey("flag_developer_bootstrap_actions_override")
}
