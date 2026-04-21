package com.subhajit.mulberry.wallpaper

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent

object WallpaperIntentFactory {
    fun createWallpaperPickerIntent(context: Context): Intent {
        return Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(context, MulberryWallpaperService::class.java)
            )
        }
    }

    fun openWallpaperPicker(context: Context) {
        val directIntent = createWallpaperPickerIntent(context)
        try {
            context.startActivity(directIntent)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }
}
