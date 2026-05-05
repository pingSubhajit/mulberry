package com.subhajit.mulberry.app.shortcut

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.subhajit.mulberry.R
import com.subhajit.mulberry.ToggleWallpaperDoodlesShortcutActivity

object WallpaperDoodlesShortcutPublisher {
    private const val SHORTCUT_ID = "toggle_wallpaper_doodles_dynamic"
    private const val ACTION_TOGGLE = "com.subhajit.mulberry.action.TOGGLE_WALLPAPER_DOODLES"

    fun publish(context: Context, enabled: Boolean) {
        val toggleIntent = Intent(context, ToggleWallpaperDoodlesShortcutActivity::class.java).apply {
            action = ACTION_TOGGLE
        }
        val shortLabel = if (enabled) "Hide doodles" else "Show doodles"
        val longLabel = if (enabled) "Hide doodles on wallpaper" else "Show doodles on wallpaper"

        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel(shortLabel)
            .setLongLabel(longLabel)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_toggle_wallpaper_doodles))
            .setIntent(toggleIntent)
            .build()

        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }
}

