package com.subhajit.mulberry.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

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
    onSurfaceVariant = MulberryDarkMutedInk,
    outline = MulberryDarkSoftBorder,
    outlineVariant = MulberryDarkSurfaceVariant,
    error = MulberryError
)

@Immutable
data class MulberryAppColors(
    val inputSurface: Color,
    val softSurface: Color,
    val softSurfaceAlt: Color,
    val softSurfaceStrong: Color,
    val softSurfaceSelected: Color,
    val softBorder: Color,
    val mutedText: Color,
    val subtleText: Color,
    val iconMuted: Color,
    val previewFrame: Color,
    val dragHandle: Color,
    val authMessageSurface: Color,
    val authButtonSurface: Color,
    val authButtonContent: Color
)

private val LightAppColors = MulberryAppColors(
    inputSurface = Color(0xFFF3F3F3),
    softSurface = Color(0xFFFFF7F8),
    softSurfaceAlt = Color(0xFFFFEBED),
    softSurfaceStrong = Color(0xFFFFD6DA),
    softSurfaceSelected = Color(0xFFFFEEF1),
    softBorder = Color(0xFFFFEDF0),
    mutedText = Color.Black.copy(alpha = 0.60f),
    subtleText = Color.Black.copy(alpha = 0.40f),
    iconMuted = Color(0xFF46514D),
    previewFrame = Color(0xFFD2D2D2),
    dragHandle = Color(0xFFDEDEDE),
    authMessageSurface = Color(0xDFFFFFFF),
    authButtonSurface = Color.White,
    authButtonContent = Color(0xFF595959)
)

private val DarkAppColors = MulberryAppColors(
    inputSurface = MulberryDarkFieldSurface,
    softSurface = MulberryDarkSoftSurface,
    softSurfaceAlt = MulberryDarkSoftSurfaceAlt,
    softSurfaceStrong = MulberryDarkSoftSurfaceStrong,
    softSurfaceSelected = MulberryDarkSoftSurfaceSelected,
    softBorder = MulberryDarkSoftBorder,
    mutedText = MulberryDarkInk.copy(alpha = 0.72f),
    subtleText = MulberryDarkInk.copy(alpha = 0.56f),
    iconMuted = MulberryDarkInk.copy(alpha = 0.78f),
    previewFrame = MulberryDarkPreviewFrame,
    dragHandle = MulberryDarkHandle,
    authMessageSurface = Color(0xE61E1E1E),
    authButtonSurface = Color(0xFF1C1C1C),
    authButtonContent = MulberryDarkInk
)

private val LocalMulberryAppColors = staticCompositionLocalOf { LightAppColors }

val MaterialTheme.mulberryAppColors: MulberryAppColors
    @Composable
    get() = LocalMulberryAppColors.current

@Composable
fun MulberryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val appColors = if (darkTheme) DarkAppColors else LightAppColors

    CompositionLocalProvider(LocalMulberryAppColors provides appColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MulberryTypography,
            shapes = MulberryShapes,
            content = content
        )
    }
}
