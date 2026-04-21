package com.subhajit.mulberry.core.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

const val APP_PREFERENCES_FILE = "app_preferences"

object PreferenceStorage {
    val authStatus = stringPreferencesKey("auth_status")
    val accessToken = stringPreferencesKey("access_token")
    val refreshToken = stringPreferencesKey("refresh_token")
    val sessionUserId = stringPreferencesKey("session_user_id")
    val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
    val userDisplayName = stringPreferencesKey("user_display_name")
    val partnerDisplayName = stringPreferencesKey("partner_display_name")
    val anniversaryDate = stringPreferencesKey("anniversary_date")
    val pairingStatus = stringPreferencesKey("pairing_status")
    val pairSessionId = stringPreferencesKey("pair_session_id")
    val pendingInviteId = stringPreferencesKey("pending_invite_id")
    val pendingInviteCode = stringPreferencesKey("pending_invite_code")
    val pendingInviterDisplayName = stringPreferencesKey("pending_inviter_display_name")
    val pendingRecipientDisplayName = stringPreferencesKey("pending_recipient_display_name")
    val pendingInviteStatus = stringPreferencesKey("pending_invite_status")
    val wallpaperConfigured = booleanPreferencesKey("wallpaper_configured")
    val backgroundImagePath = stringPreferencesKey("background_image_path")
    val backgroundImageUpdatedAt = stringPreferencesKey("background_image_updated_at")
    val onboardingDraftDisplayName = stringPreferencesKey("onboarding_draft_display_name")
    val onboardingDraftPartnerDisplayName =
        stringPreferencesKey("onboarding_draft_partner_display_name")
    val onboardingDraftAnniversaryDate =
        stringPreferencesKey("onboarding_draft_anniversary_date")

    val placeholderPairingControlsOverride =
        booleanPreferencesKey("flag_placeholder_pairing_controls_override")
    val wallpaperSetupCtaOverride =
        booleanPreferencesKey("flag_wallpaper_setup_cta_override")
    val developerBootstrapActionsOverride =
        booleanPreferencesKey("flag_developer_bootstrap_actions_override")
}
