package com.subhajit.mulberry.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = MulberryPrimary,
    onPrimary = MulberrySurface,
    primaryContainer = MulberryPrimaryLight,
    onPrimaryContainer = MulberryPrimaryDark,
    secondary = MulberryAccent,
    onSecondary = MulberryInk,
    background = MulberrySurface,
    onBackground = MulberryInk,
    surface = MulberrySurface,
    onSurface = MulberryInk,
    surfaceVariant = MulberrySurfaceVariant,
    onSurfaceVariant = MulberryMutedInk,
    outline = MulberryMutedInk,
    outlineVariant = MulberryPrimaryLight,
    error = MulberryError
)

private val DarkColors = darkColorScheme(
    primary = MulberryPrimary,
    onPrimary = MulberryDarkInk,
    primaryContainer = MulberryPrimaryDark,
    onPrimaryContainer = MulberryDarkInk,
    secondary = MulberryAccent,
    onSecondary = MulberryInk,
    background = MulberryDarkBackground,
    onBackground = MulberryDarkInk,
    surface = MulberryDarkSurface,
    onSurface = MulberryDarkInk,
    surfaceVariant = MulberryDarkSurfaceVariant,
    onSurfaceVariant = MulberryPrimaryLight,
    outline = MulberryPrimaryLight,
    outlineVariant = MulberryDarkSurfaceVariant,
    error = MulberryError
)

@Composable
fun MulberryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MulberryTypography,
        shapes = MulberryShapes,
        content = content
    )
}
