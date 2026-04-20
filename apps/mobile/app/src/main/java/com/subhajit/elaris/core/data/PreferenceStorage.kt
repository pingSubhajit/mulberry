package com.subhajit.elaris.core.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

const val APP_PREFERENCES_FILE = "app_preferences"

object PreferenceStorage {
    val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
    val pairingStatus = stringPreferencesKey("pairing_status")
    val wallpaperConfigured = booleanPreferencesKey("wallpaper_configured")
    val sessionDisplayState = stringPreferencesKey("session_display_state")
    val backgroundImagePath = stringPreferencesKey("background_image_path")
    val backgroundImageUpdatedAt = stringPreferencesKey("background_image_updated_at")

    val placeholderPairingControlsOverride =
        booleanPreferencesKey("flag_placeholder_pairing_controls_override")
    val wallpaperSetupCtaOverride =
        booleanPreferencesKey("flag_wallpaper_setup_cta_override")
    val developerBootstrapActionsOverride =
        booleanPreferencesKey("flag_developer_bootstrap_actions_override")
}
