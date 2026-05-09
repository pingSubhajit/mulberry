package com.subhajit.mulberry.wallpaper

import android.content.Context
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.delay

private const val WALLPAPER_ASSET_PACK = "wallpaper_pack"

class WallpaperPackAssetResolver(
    private val context: Context,
    private val assetPackManager: AssetPackManager? = createAssetPackManager(context)
) {
    fun requestFastFollowPack() {
        val manager = currentAssetPackManager() ?: return
        if (manager.getPackLocation(WALLPAPER_ASSET_PACK) != null) return
        runCatching { manager.fetch(listOf(WALLPAPER_ASSET_PACK)) }
    }

    fun fileFor(preset: WallpaperPreset): File? {
        val assetsPath = currentAssetPackManager()
            ?.getPackLocation(WALLPAPER_ASSET_PACK)
            ?.assetsPath()
            ?: return null
        return File(assetsPath, preset.assetPath).takeIf(File::isFile)
    }

    suspend fun awaitFileFor(
        preset: WallpaperPreset,
        timeoutMs: Long = 12_000L,
        pollIntervalMs: Long = 400L
    ): File? {
        requestFastFollowPack()
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            fileFor(preset)?.let { return it }
            delay(pollIntervalMs)
        }
        return fileFor(preset)
    }

    fun openAssetStream(preset: WallpaperPreset): InputStream? =
        runCatching { context.assets.open(preset.assetPath) }.getOrNull()

    private fun currentAssetPackManager(): AssetPackManager? =
        assetPackManager
            ?.takeIf { manager -> manager.getPackLocation(WALLPAPER_ASSET_PACK) != null }
            ?: createAssetPackManager(context)

    companion object {
        fun create(context: Context): WallpaperPackAssetResolver = WallpaperPackAssetResolver(context)
    }
}

private fun createAssetPackManager(context: Context): AssetPackManager? =
    runCatching { AssetPackManagerFactory.getInstance(context.applicationContext) }.getOrNull()
