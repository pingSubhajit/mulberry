package com.subhajit.mulberry.reactions

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RawRes
import com.subhajit.mulberry.R

object ReactionGifAssets {
    @RawRes
    private fun rawResFor(type: ReactionType): Int? = when (type) {
        ReactionType.HEART -> R.raw.reaction_heart
        else -> null
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

        return runCatching {
            context.applicationContext.assets.open(type.assetPath()).use(Movie::decodeStream)
        }.getOrNull()
    }

    /**
     * The reaction as Android's animated drawable, sized for [targetSizePx]: its frames decode on
     * a background thread and draw on the GPU, where [Movie] decodes each frame in software on the
     * main thread. Reads the file, so call it off the main thread.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun decodeAnimatedDrawable(context: Context, type: ReactionType, targetSizePx: Int): Drawable? {
        val appContext = context.applicationContext
        val source = rawResFor(type)?.let { ImageDecoder.createSource(appContext.resources, it) }
            ?: ImageDecoder.createSource(appContext.assets, type.assetPath())
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
}

private fun ReactionType.assetPath(): String = when (this) {
    ReactionType.HEART -> "reactions/reaction_heart.gif"
    ReactionType.HUG -> "reactions/reaction_hug.gif"
    ReactionType.KISS -> "reactions/reaction_kiss.gif"
    ReactionType.SMILE -> "reactions/reaction_smile.gif"
    ReactionType.LAUGH -> "reactions/reaction_laugh.gif"
    ReactionType.SPARKLE -> "reactions/reaction_sparkles.gif"
}
