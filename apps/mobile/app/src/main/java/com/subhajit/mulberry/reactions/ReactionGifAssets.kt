package com.subhajit.mulberry.reactions

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RawRes
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.subhajit.mulberry.R
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

internal const val REACTION_ASSET_PACK = "reaction_pack"

object ReactionGifAssets {
    private val fastFollowRequestStarted = AtomicBoolean(false)

    @RawRes
    private fun rawResFor(type: ReactionType): Int? = when (type) {
        ReactionType.HEART -> R.raw.reaction_heart
        else -> null
    }

    fun requestFastFollowPack(context: Context) {
        val manager = createReactionAssetPackManager(context) ?: return
        if (manager.getPackLocation(REACTION_ASSET_PACK) != null) return
        if (!fastFollowRequestStarted.compareAndSet(false, true)) return
        runCatching { manager.fetch(listOf(REACTION_ASSET_PACK)) }
            .onFailure { fastFollowRequestStarted.set(false) }
    }

    internal fun onFastFollowPackStatus(status: Int) {
        if (
            status == AssetPackStatus.FAILED ||
            status == AssetPackStatus.CANCELED ||
            status == AssetPackStatus.NOT_INSTALLED
        ) {
            fastFollowRequestStarted.set(false)
        }
    }

    /**
     * Decodes a reaction's GIF from disk: slow (tens of milliseconds for the larger ones), so
     * never on the main thread. The app's screens go through [ReactionGifUiCache].
     */
    fun decodeMovie(context: Context, type: ReactionType): Movie? {
        rawResFor(type)?.let { resId ->
            return runCatching {
                context.resources.openRawResource(resId).use(Movie::decodeStream)
            }.getOrNull()
        }

        val streamProvider = assetStreamProvider(context, type) ?: run {
            requestFastFollowPack(context)
            return null
        }
        return runCatching {
            streamProvider().use(Movie::decodeStream)
        }.getOrNull()
    }

    /**
     * The reaction as Android's animated drawable, sized for [targetSizePx]: its frames decode on
     * a background thread and draw on the GPU, where [Movie] decodes each frame in software on the
     * main thread. Reads the file, so call it off the main thread. Null until the pack arrives.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun decodeAnimatedDrawable(context: Context, type: ReactionType, targetSizePx: Int): Drawable? {
        val appContext = context.applicationContext
        val source = rawResFor(type)?.let { ImageDecoder.createSource(appContext.resources, it) }
            ?: packFile(appContext, type)?.let { ImageDecoder.createSource(it) }
            ?: type.assetPackRelativePath()
                .takeIf { path -> runCatching { appContext.assets.open(path).use { true } }.getOrDefault(false) }
                ?.let { path -> ImageDecoder.createSource(appContext.assets, path) }
            ?: run {
                requestFastFollowPack(appContext)
                return null
            }
        return runCatching {
            ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                // Decode no larger than it's shown: big GIFs then cost far less per frame.
                val longest = maxOf(info.size.width, info.size.height)
                if (targetSizePx in 1 until longest) {
                    val ratio = targetSizePx.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * ratio).toInt().coerceAtLeast(1),
                        (info.size.height * ratio).toInt().coerceAtLeast(1)
                    )
                }
            }
        }.getOrNull()
    }

    private fun packFile(context: Context, type: ReactionType): File? {
        val assetsPath = createReactionAssetPackManager(context)
            ?.getPackLocation(REACTION_ASSET_PACK)
            ?.assetsPath()
            ?: return null
        return File(assetsPath, type.assetPackRelativePath()).takeIf(File::isFile)
    }

    private fun assetStreamProvider(
        context: Context,
        type: ReactionType
    ): (() -> InputStream)? {
        val relativePath = type.assetPackRelativePath()
        val manager = createReactionAssetPackManager(context)
        val assetsPath = manager
            ?.getPackLocation(REACTION_ASSET_PACK)
            ?.assetsPath()
        if (assetsPath != null) {
            val file = File(assetsPath, relativePath).takeIf(File::isFile)
            if (file != null) return { file.inputStream() }
        }

        val appContext = context.applicationContext
        if (runCatching { appContext.assets.open(relativePath).use { true } }.getOrDefault(false)) {
            return { appContext.assets.open(relativePath) }
        }

        return null
    }
}

private fun ReactionType.assetPackRelativePath(): String = when (this) {
    ReactionType.HEART -> "reactions/reaction_heart.gif"
    ReactionType.HUG -> "reactions/reaction_hug.gif"
    ReactionType.KISS -> "reactions/reaction_kiss.gif"
    ReactionType.SMILE -> "reactions/reaction_smile.gif"
    ReactionType.LAUGH -> "reactions/reaction_laugh.gif"
    ReactionType.SPARKLE -> "reactions/reaction_sparkles.gif"
}

internal fun createReactionAssetPackManager(context: Context): AssetPackManager? =
    runCatching { AssetPackManagerFactory.getInstance(context.applicationContext) }.getOrNull()
