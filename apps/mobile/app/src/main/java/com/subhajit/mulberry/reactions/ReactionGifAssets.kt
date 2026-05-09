package com.subhajit.mulberry.reactions

import android.content.Context
import android.graphics.Movie
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
