package com.subhajit.elaris.wallpaper

data class WallpaperRenderState(
    val snapshotPath: String? = null,
    val backgroundImagePath: String? = null,
    val fallbackColorArgb: Int = 0xFFF8F4F0.toInt()
)
