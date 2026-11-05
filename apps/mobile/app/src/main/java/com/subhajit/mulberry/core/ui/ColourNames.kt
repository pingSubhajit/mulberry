package com.subhajit.mulberry.core.ui

import kotlin.math.floor

/**
 * Spoken names for colours, so TalkBack says "Mulberry red" rather than nothing or a code. The
 * same names and rules as the iPhone's `ColourNames`.
 */
object ColourNames {
    private val palette: Map<Long, String> = mapOf(
        0xFFB31329L to "Mulberry red", 0xFFFF6A2AL to "Orange", 0xFFFFE66DL to "Lemon yellow",
        0xFF2457D6L to "Blue", 0xFF5BB7E8L to "Sky blue", 0xFF006B4FL to "Forest green",
        0xFF567A3AL to "Olive green", 0xFF80D8B0L to "Mint", 0xFFE85072L to "Pink",
        0xFFA78BFAL to "Lavender", 0xFF3B2F8FL to "Indigo", 0xFFF5B83DL to "Amber",
        0xFF141414L to "Black", 0xFFF7F4EFL to "Off-white", 0xFF3A3A3AL to "Charcoal",
        0xFF7A4A32L to "Brown", 0xFFB56A43L to "Terracotta", 0xFFD8A0A8L to "Dusty rose",
        0xFF00A53CL to "Green"
    )

    private val hueNames = listOf(
        "Red", "Orange", "Yellow", "Lime", "Green", "Teal", "Cyan", "Blue", "Indigo", "Purple",
        "Magenta", "Pink", "Red"
    )

    /** A palette colour's name, or a description of any other colour by its hue and lightness. */
    fun name(argb: Long): String {
        val opaque = (argb and 0xFFFFFFFFL) or 0xFF000000L
        palette[opaque]?.let { return it }
        val hsb = hsb(opaque)
        if (hsb.brightness < 0.15f) return "Black"
        if (hsb.saturation < 0.12f) {
            return when {
                hsb.brightness > 0.9f -> "White"
                hsb.brightness > 0.6f -> "Light grey"
                hsb.brightness > 0.3f -> "Grey"
                else -> "Dark grey"
            }
        }
        val base = hueNames[floor(hsb.hue * 12f + 0.5f).toInt().coerceIn(0, hueNames.lastIndex)]
        return when {
            hsb.brightness < 0.45f -> "Dark ${base.lowercase()}"
            hsb.saturation < 0.45f && hsb.brightness > 0.8f -> "Light ${base.lowercase()}"
            else -> base
        }
    }

    /** [name] for a colour held as an `Int`, as Android's graphics APIs give it. */
    fun name(argb: Int): String = name(argb.toLong() and 0xFFFFFFFFL)

    /** Hue in 0..1, saturation and brightness in 0..1, as UIKit's `getHue` gives them. */
    private data class Hsb(val hue: Float, val saturation: Float, val brightness: Float)

    private fun hsb(argb: Long): Hsb {
        val r = ((argb shr 16) and 0xFF).toFloat() / 255f
        val g = ((argb shr 8) and 0xFF).toFloat() / 255f
        val b = (argb and 0xFF).toFloat() / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val saturation = if (max == 0f) 0f else delta / max
        val hueDegrees = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta).mod(6f))
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return Hsb(hue = hueDegrees / 360f, saturation = saturation, brightness = max)
    }
}
