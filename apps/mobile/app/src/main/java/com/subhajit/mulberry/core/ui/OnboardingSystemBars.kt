package com.subhajit.mulberry.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.subhajit.mulberry.core.ui.metadata.AppSystemBarStyle

@Composable
fun rememberOnboardingSystemBarStyle(): AppSystemBarStyle {
    val useDarkIcons = !isSystemInDarkTheme()
    return remember(useDarkIcons) {
        AppSystemBarStyle(
            statusBarColorArgb = 0x00000000,
            navigationBarColorArgb = 0x00000000,
            useDarkIcons = useDarkIcons
        )
    }
}
