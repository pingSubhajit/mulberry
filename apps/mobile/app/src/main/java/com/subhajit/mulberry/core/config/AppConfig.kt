package com.subhajit.mulberry.core.config

import com.subhajit.mulberry.BuildConfig
import com.subhajit.mulberry.core.flags.FeatureFlags
import com.subhajit.mulberry.drawing.render.CanvasStrokeRenderMode

data class AppConfig(
    val environment: AppEnvironment,
    val apiBaseUrl: String,
    val enableDebugMenu: Boolean,
    val googleServerClientId: String,
    val canvasStrokeRenderMode: CanvasStrokeRenderMode,
    val defaultFeatureFlags: FeatureFlags
)

object AppConfigFactory {
    fun fromFields(
        environmentName: String,
        apiBaseUrl: String,
        enableDebugMenu: Boolean,
        googleServerClientId: String = "",
        canvasStrokeRenderMode: String = CanvasStrokeRenderMode.Hybrid.name
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
            googleServerClientId = googleServerClientId,
            canvasStrokeRenderMode = CanvasStrokeRenderMode.fromRaw(canvasStrokeRenderMode),
            defaultFeatureFlags = defaultFeatureFlags
        )
    }

    fun fromBuildConfig(): AppConfig =
        fromFields(
            environmentName = BuildConfig.APP_ENVIRONMENT,
            apiBaseUrl = BuildConfig.API_BASE_URL,
            enableDebugMenu = BuildConfig.ENABLE_DEBUG_MENU,
            googleServerClientId = BuildConfig.GOOGLE_SERVER_CLIENT_ID,
            canvasStrokeRenderMode = BuildConfig.CANVAS_STROKE_RENDER_MODE
        )
}
