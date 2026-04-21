package com.subhajit.mulberry.core.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.subhajit.mulberry.core.ui.metadata.AppSystemBarStyle

@Composable
@Suppress("DEPRECATION")
fun ApplySystemBarStyle(style: AppSystemBarStyle) {
    val view = LocalView.current
    if (view.isInEditMode) return

    DisposableEffect(view, style) {
        val window = (view.context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        val previousStatusBarColor = window.statusBarColor
        val previousNavigationBarColor = window.navigationBarColor
        val previousLightStatusBars = controller.isAppearanceLightStatusBars
        val previousLightNavigationBars = controller.isAppearanceLightNavigationBars

        window.statusBarColor = style.statusBarColorArgb.toInt()
        window.navigationBarColor = style.navigationBarColorArgb.toInt()
        controller.isAppearanceLightStatusBars = style.useDarkIcons
        controller.isAppearanceLightNavigationBars = style.useDarkIcons

        onDispose {
            window.statusBarColor = previousStatusBarColor
            window.navigationBarColor = previousNavigationBarColor
            controller.isAppearanceLightStatusBars = previousLightStatusBars
            controller.isAppearanceLightNavigationBars = previousLightNavigationBars
        }
    }
}
