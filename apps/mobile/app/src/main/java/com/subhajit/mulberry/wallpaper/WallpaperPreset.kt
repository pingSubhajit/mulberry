package com.subhajit.mulberry.wallpaper

import androidx.annotation.DrawableRes
import com.subhajit.mulberry.R

data class WallpaperPreset(
    @DrawableRes val drawableResId: Int,
    @DrawableRes val previewDrawableResId: Int,
    @DrawableRes val thumbnailDrawableResId: Int,
    val label: String
)

val DefaultWallpaperPresets: List<WallpaperPreset> = listOf(
    WallpaperPreset(
        drawableResId = R.drawable.wallpaper_preset_1,
        previewDrawableResId = R.drawable.wallpaper_preset_1_preview,
        thumbnailDrawableResId = R.drawable.wallpaper_preset_1_thumb,
        label = "Warm canyon"
    ),
    WallpaperPreset(
        drawableResId = R.drawable.wallpaper_preset_2,
        previewDrawableResId = R.drawable.wallpaper_preset_2_preview,
        thumbnailDrawableResId = R.drawable.wallpaper_preset_2_thumb,
        label = "Red waves"
    ),
    WallpaperPreset(
        drawableResId = R.drawable.wallpaper_preset_3,
        previewDrawableResId = R.drawable.wallpaper_preset_3_preview,
        thumbnailDrawableResId = R.drawable.wallpaper_preset_3_thumb,
        label = "Quiet dusk"
    ),
    WallpaperPreset(
        drawableResId = R.drawable.wallpaper_preset_4,
        previewDrawableResId = R.drawable.wallpaper_preset_4_preview,
        thumbnailDrawableResId = R.drawable.wallpaper_preset_4_thumb,
        label = "Mulberry glow"
    )
)
