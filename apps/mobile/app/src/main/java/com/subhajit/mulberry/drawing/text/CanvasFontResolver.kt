package com.subhajit.mulberry.drawing.text

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.res.ResourcesCompat
import com.subhajit.mulberry.R
import com.subhajit.mulberry.drawing.model.CanvasTextFont
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.VirgilFontFamily

class CanvasFontResolver(context: Context) {
    private val appContext = context.applicationContext
    private val fallbackTypeface: Typeface by lazy {
        ResourcesCompat.getFont(appContext, R.font.poppins_regular) ?: Typeface.DEFAULT
    }
    private val virgilTypeface: Typeface by lazy {
        ResourcesCompat.getFont(appContext, R.font.virgil_regular) ?: fallbackTypeface
    }
    private val assetTypefaceCache = mutableMapOf<CanvasTextFont, Typeface>()
    private val assetFontFamilyCache = mutableMapOf<CanvasTextFont, FontFamily>()

    fun typefaceFor(font: CanvasTextFont): Typeface = when (font) {
        CanvasTextFont.POPPINS -> fallbackTypeface
        CanvasTextFont.VIRGIL -> virgilTypeface
        else -> assetTypefaceFor(font) ?: fallbackTypeface
    }

    fun fontFamilyFor(font: CanvasTextFont): FontFamily {
        return when (font) {
            CanvasTextFont.POPPINS -> PoppinsFontFamily
            CanvasTextFont.VIRGIL -> VirgilFontFamily
            else -> {
                assetFontFamilyCache[font]?.let { return it }
                val typeface = assetTypefaceFor(font) ?: return PoppinsFontFamily
                FontFamily(typeface).also { assetFontFamilyCache[font] = it }
            }
        }
    }

    private fun assetTypefaceFor(font: CanvasTextFont): Typeface? {
        assetTypefaceCache[font]?.let { return it }
        val assetPath = font.assetPath() ?: return null
        val typeface = runCatching { Typeface.createFromAsset(appContext.assets, assetPath) }
            .getOrNull()
            ?: return null
        assetTypefaceCache[font] = typeface
        return typeface
    }

    companion object {
        fun create(context: Context): CanvasFontResolver = CanvasFontResolver(context)
    }
}

@Composable
fun rememberCanvasFontResolver(): CanvasFontResolver {
    val context = LocalContext.current.applicationContext
    return remember(context) { CanvasFontResolver(context) }
}

fun CanvasTextFont.canvasDisplayName(): String = when (this) {
    CanvasTextFont.POPPINS -> "Poppins"
    CanvasTextFont.VIRGIL -> "Virgil"
    CanvasTextFont.DM_SANS -> "DM Sans"
    CanvasTextFont.SPACE_MONO -> "Space Mono"
    CanvasTextFont.PLAYFAIR_DISPLAY -> "Playfair"
    CanvasTextFont.BANGERS -> "Bangers"
    CanvasTextFont.PERMANENT_MARKER -> "Marker"
    CanvasTextFont.KALAM -> "Kalam"
    CanvasTextFont.OSWALD -> "Oswald"
}

private fun CanvasTextFont.assetPath(): String? = when (this) {
    CanvasTextFont.POPPINS,
    CanvasTextFont.VIRGIL -> null
    CanvasTextFont.DM_SANS -> "fonts/dm_sans_regular.ttf"
    CanvasTextFont.SPACE_MONO -> "fonts/space_mono_regular.ttf"
    CanvasTextFont.PLAYFAIR_DISPLAY -> "fonts/playfair_display_regular.ttf"
    CanvasTextFont.BANGERS -> "fonts/bangers_regular.ttf"
    CanvasTextFont.PERMANENT_MARKER -> "fonts/permanent_marker_regular.ttf"
    CanvasTextFont.KALAM -> "fonts/kalam_regular.ttf"
    CanvasTextFont.OSWALD -> "fonts/oswald_regular.ttf"
}
