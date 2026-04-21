package com.subhajit.mulberry.wallpaper

data class BackgroundImageState(
    val assetPath: String? = null,
    val lastUpdatedAt: Long = 0L
) {
    val isConfigured: Boolean
        get() = !assetPath.isNullOrBlank()
}
