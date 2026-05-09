package com.subhajit.mulberry.wallpaper

import androidx.annotation.DrawableRes
import com.subhajit.mulberry.R

data class WallpaperPreset(
    val id: String,
    val assetPath: String,
    @DrawableRes val previewDrawableResId: Int,
    @DrawableRes val thumbnailDrawableResId: Int,
    val label: String
)

val DefaultWallpaperPresets: List<WallpaperPreset> = listOf(
    WallpaperPreset(
        id = "wallpaper_preset_1",
        assetPath = "wallpapers/wallpaper_preset_1.jpg",
        previewDrawableResId = R.drawable.wallpaper_preset_1_preview,
        thumbnailDrawableResId = R.drawable.wallpaper_preset_1_thumb,
        label = "Warm canyon"
    ),
    WallpaperPreset(
        id = "wallpaper_preset_2",
        assetPath = "wallpapers/wallpaper_preset_2.jpg",
        previewDrawableResId = R.drawable.wallpaper_preset_2_preview,
        thumbnailDrawableResId = R.drawable.wallpaper_preset_2_thumb,
        label = "Red waves"
    ),
    WallpaperPreset(
        id = "wallpaper_preset_3",
        assetPath = "wallpapers/wallpaper_preset_3.jpg",
        previewDrawableResId = R.drawable.wallpaper_preset_3_preview,
        thumbnailDrawableResId = R.drawable.wallpaper_preset_3_thumb,
        label = "Quiet dusk"
    ),
    WallpaperPreset(
        id = "wallpaper_preset_4",
        assetPath = "wallpapers/wallpaper_preset_4.jpg",
        previewDrawableResId = R.drawable.wallpaper_preset_4_preview,
        thumbnailDrawableResId = R.drawable.wallpaper_preset_4_thumb,
        label = "Mulberry glow"
    )
)
