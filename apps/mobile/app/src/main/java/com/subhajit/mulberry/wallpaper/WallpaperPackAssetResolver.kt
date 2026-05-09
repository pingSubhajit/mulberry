package com.subhajit.mulberry.wallpaper

import android.content.Context
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import java.io.File
import java.io.InputStream

private const val WALLPAPER_ASSET_PACK = "wallpaper_pack"

class WallpaperPackAssetResolver(
    private val context: Context,
    private val assetPackManager: AssetPackManager? = createAssetPackManager(context)
) {
    fun requestFastFollowPack() {
        val manager = assetPackManager ?: return
        if (manager.getPackLocation(WALLPAPER_ASSET_PACK) != null) return
        runCatching { manager.fetch(listOf(WALLPAPER_ASSET_PACK)) }
    }

    fun fileFor(preset: WallpaperPreset): File? {
        val assetsPath = assetPackManager
            ?.getPackLocation(WALLPAPER_ASSET_PACK)
            ?.assetsPath()
            ?: return null
        return File(assetsPath, preset.assetPath).takeIf(File::isFile)
    }

    fun openAssetStream(preset: WallpaperPreset): InputStream? =
        runCatching { context.assets.open(preset.assetPath) }.getOrNull()

    companion object {
        fun create(context: Context): WallpaperPackAssetResolver = WallpaperPackAssetResolver(context)
    }
}

private fun createAssetPackManager(context: Context): AssetPackManager? =
    runCatching { AssetPackManagerFactory.getInstance(context.applicationContext) }.getOrNull()
