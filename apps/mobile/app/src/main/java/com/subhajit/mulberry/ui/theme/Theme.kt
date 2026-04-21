package com.subhajit.mulberry.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Blue40,
    onPrimary = LightSurface,
    primaryContainer = Blue20,
    onPrimaryContainer = LightSurface,
    secondary = Orange40,
    onSecondary = DarkBackground,
    background = LightBackground,
    onBackground = DarkBackground,
    surface = LightSurface,
    onSurface = DarkBackground,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Blue20
)

private val DarkColors = darkColorScheme(
    primary = Blue80,
    onPrimary = DarkBackground,
    primaryContainer = Blue20,
    onPrimaryContainer = LightSurface,
    secondary = Orange40,
    onSecondary = DarkBackground,
    background = DarkBackground,
    onBackground = LightSurface,
    surface = DarkSurface,
    onSurface = LightSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Blue80
)

@Composable
fun MulberryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
