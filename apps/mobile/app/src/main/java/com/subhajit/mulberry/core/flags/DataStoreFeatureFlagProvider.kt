package com.subhajit.mulberry.core.flags

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.subhajit.mulberry.core.config.AppConfig
import com.subhajit.mulberry.core.data.PreferenceStorage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class DataStoreFeatureFlagProvider @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val appConfig: AppConfig
) : FeatureFlagProvider {

    override val flags: Flow<FeatureFlags> = dataStore.data.map { preferences ->
        val defaults = appConfig.defaultFeatureFlags
        FeatureFlags(
            showPlaceholderPairingControls =
                preferences[PreferenceStorage.placeholderPairingControlsOverride]
                    ?: defaults.showPlaceholderPairingControls,
            showWallpaperSetupCta =
                preferences[PreferenceStorage.wallpaperSetupCtaOverride]
                    ?: defaults.showWallpaperSetupCta,
            showDeveloperBootstrapActions =
                preferences[PreferenceStorage.developerBootstrapActionsOverride]
                    ?: defaults.showDeveloperBootstrapActions
        )
    }

    override suspend fun setOverride(flag: FeatureFlag, enabled: Boolean?) {
        if (!appConfig.enableDebugMenu) return

        dataStore.edit { preferences ->
            val key = flag.preferenceKey()
            if (enabled == null) {
                preferences.remove(key)
            } else {
                preferences[key] = enabled
            }
        }
    }

    override suspend fun clearOverrides() {
        if (!appConfig.enableDebugMenu) return

        dataStore.edit { preferences ->
            preferences.remove(PreferenceStorage.placeholderPairingControlsOverride)
            preferences.remove(PreferenceStorage.wallpaperSetupCtaOverride)
            preferences.remove(PreferenceStorage.developerBootstrapActionsOverride)
        }
    }

    private fun FeatureFlag.preferenceKey() = when (this) {
        FeatureFlag.PLACEHOLDER_PAIRING_CONTROLS ->
            PreferenceStorage.placeholderPairingControlsOverride

        FeatureFlag.WALLPAPER_SETUP_CTA ->
            PreferenceStorage.wallpaperSetupCtaOverride

        FeatureFlag.DEVELOPER_BOOTSTRAP_ACTIONS ->
            PreferenceStorage.developerBootstrapActionsOverride
    }
}
