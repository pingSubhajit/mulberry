package com.subhajit.mulberry.ui.animation

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * The system's animation setting. "Remove animations" sets the animator duration scale to zero,
 * which is Android's Reduce Motion: decorative motion falls back to a fade or a still state.
 */
@Immutable
data class SystemAnimationSettings(
    /** The animator duration scale; 1 is normal speed, 0 means animations are removed. */
    val durationScale: Float
) {
    val enabled: Boolean get() = durationScale != 0f

    companion object {
        val Default = SystemAnimationSettings(durationScale = 1f)
    }
}

/** Reads the setting now, for code outside composition such as the live wallpaper. */
fun Context.systemAnimationSettings(): SystemAnimationSettings =
    runCatching {
        SystemAnimationSettings(
            durationScale = Settings.Global.getFloat(
                contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        )
    }.getOrDefault(SystemAnimationSettings.Default)

/** False when "Remove animations" is on, read outside composition. */
fun Context.systemAnimationsEnabled(): Boolean = systemAnimationSettings().enabled

/** The setting, kept current while the composable is shown. */
@Composable
fun rememberSystemAnimationSettings(): SystemAnimationSettings {
    val context = LocalContext.current
    var settings by remember(context) { mutableStateOf(context.systemAnimationSettings()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                settings = context.systemAnimationSettings()
            }
        }
        val registered = runCatching {
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer
            )
        }.isSuccess
        onDispose {
            if (registered) context.contentResolver.unregisterContentObserver(observer)
        }
    }
    return settings
}

/** False when "Remove animations" is on (the animator duration scale is zero). */
@Composable
fun rememberSystemAnimationsEnabled(): Boolean = rememberSystemAnimationSettings().enabled
