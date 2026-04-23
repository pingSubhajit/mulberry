package com.subhajit.mulberry.wallpaper

data class BackgroundImageState(
    val assetPath: String? = null,
    val lastUpdatedAt: Long = 0L,
    val selectedPresetResId: Int? = null,
    val selectedRemoteWallpaperId: String? = null
) {
    val isConfigured: Boolean
        get() = !assetPath.isNullOrBlank()
}
