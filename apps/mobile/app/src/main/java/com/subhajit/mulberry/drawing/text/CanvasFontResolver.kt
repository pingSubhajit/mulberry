package com.subhajit.mulberry.drawing.text

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.res.ResourcesCompat
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.subhajit.mulberry.R
import com.subhajit.mulberry.drawing.model.CanvasTextFont
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.VirgilFontFamily
import java.io.File

private const val CANVAS_FONTS_ASSET_PACK = "canvas_fonts"

class CanvasFontResolver(
    context: Context,
    private val assetPackManager: AssetPackManager? = createAssetPackManager(context)
) {
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

    fun fontFamilyFor(font: CanvasTextFont): FontFamily = when (font) {
        CanvasTextFont.POPPINS -> PoppinsFontFamily
        CanvasTextFont.VIRGIL -> VirgilFontFamily
        else -> assetFontFamilyCache.getOrPut(font) {
            val typeface = assetTypefaceFor(font) ?: return@getOrPut PoppinsFontFamily
            FontFamily(typeface)
        }
    }

    fun requestFastFollowPack() {
        val manager = assetPackManager ?: return
        if (manager.getPackLocation(CANVAS_FONTS_ASSET_PACK) != null) return
        runCatching { manager.fetch(listOf(CANVAS_FONTS_ASSET_PACK)) }
    }

    private fun assetTypefaceFor(font: CanvasTextFont): Typeface? {
        assetTypefaceCache[font]?.let { return it }
        val relativePath = font.assetPackRelativePath() ?: return null
        val file = assetFileFor(relativePath)
        val typeface = if (file != null) {
            runCatching { Typeface.createFromFile(file) }.getOrNull()
        } else {
            runCatching { Typeface.createFromAsset(appContext.assets, relativePath) }.getOrNull()
        } ?: return null
        assetTypefaceCache[font] = typeface
        return typeface
    }

    private fun assetFileFor(relativePath: String): File? {
        val assetsPath = assetPackManager
            ?.getPackLocation(CANVAS_FONTS_ASSET_PACK)
            ?.assetsPath()
            ?: return null
        return File(assetsPath, relativePath).takeIf(File::isFile)
    }

    companion object {
        fun create(context: Context): CanvasFontResolver = CanvasFontResolver(context)
    }
}

@Composable
fun rememberCanvasFontResolver(): CanvasFontResolver {
    val context = LocalContext.current.applicationContext
    val assetPackManager = remember(context) { createAssetPackManager(context) }
    var generation by remember { mutableIntStateOf(0) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(assetPackManager) {
        if (assetPackManager == null) return@DisposableEffect onDispose { }
        val listener = AssetPackStateUpdateListener { state ->
            if (
                state.name() == CANVAS_FONTS_ASSET_PACK &&
                state.status() in setOf(
                    AssetPackStatus.COMPLETED,
                    AssetPackStatus.FAILED,
                    AssetPackStatus.CANCELED,
                    AssetPackStatus.NOT_INSTALLED
                )
            ) {
                mainHandler.post {
                    generation += 1
                }
            }
        }
        assetPackManager.registerListener(listener)
        onDispose { assetPackManager.unregisterListener(listener) }
    }

    val resolver = remember(context, assetPackManager, generation) {
        CanvasFontResolver(context, assetPackManager)
    }

    LaunchedEffect(resolver) {
        resolver.requestFastFollowPack()
    }

    return resolver
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

private fun CanvasTextFont.assetPackRelativePath(): String? = when (this) {
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

private fun createAssetPackManager(context: Context): AssetPackManager? =
    runCatching { AssetPackManagerFactory.getInstance(context.applicationContext) }.getOrNull()
