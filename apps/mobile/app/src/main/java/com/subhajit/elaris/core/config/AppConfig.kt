package com.subhajit.elaris.core.config

import com.subhajit.elaris.BuildConfig
import com.subhajit.elaris.core.flags.FeatureFlags

data class AppConfig(
    val environment: AppEnvironment,
    val apiBaseUrl: String,
    val enableDebugMenu: Boolean,
    val defaultFeatureFlags: FeatureFlags
)

object AppConfigFactory {
    fun fromFields(
        environmentName: String,
        apiBaseUrl: String,
        enableDebugMenu: Boolean
    ): AppConfig {
        val environment = AppEnvironment.fromRaw(environmentName)
        val defaultFeatureFlags = when (environment) {
            AppEnvironment.DEV -> FeatureFlags(
                showPlaceholderPairingControls = true,
                showWallpaperSetupCta = true,
                showDeveloperBootstrapActions = true
            )

            AppEnvironment.PROD -> FeatureFlags(
                showPlaceholderPairingControls = true,
                showWallpaperSetupCta = true,
                showDeveloperBootstrapActions = false
            )
        }

        return AppConfig(
            environment = environment,
            apiBaseUrl = apiBaseUrl,
            enableDebugMenu = enableDebugMenu,
            defaultFeatureFlags = defaultFeatureFlags
        )
    }

    fun fromBuildConfig(): AppConfig =
        fromFields(
            environmentName = BuildConfig.APP_ENVIRONMENT,
            apiBaseUrl = BuildConfig.API_BASE_URL,
            enableDebugMenu = BuildConfig.ENABLE_DEBUG_MENU
        )
}
