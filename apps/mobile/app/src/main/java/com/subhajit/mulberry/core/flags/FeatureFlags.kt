package com.subhajit.mulberry.core.flags

data class FeatureFlags(
    val showPlaceholderPairingControls: Boolean = true,
    val showWallpaperSetupCta: Boolean = true,
    val showDeveloperBootstrapActions: Boolean = false
)
