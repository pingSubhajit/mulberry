package com.subhajit.mulberry.reactions

import android.content.Context
import android.graphics.Movie
import androidx.annotation.RawRes
import com.subhajit.mulberry.R

object ReactionGifAssets {
    @RawRes
    fun rawResFor(type: ReactionType): Int = when (type) {
        ReactionType.HEART -> R.raw.reaction_heart
        ReactionType.KISS -> R.raw.reaction_kiss
        ReactionType.LAUGH -> R.raw.reaction_laugh
        ReactionType.SPARKLE -> R.raw.reaction_sparkles
    }

    fun decodeMovie(context: Context, type: ReactionType): Movie? = runCatching {
        context.resources.openRawResource(rawResFor(type)).use { stream ->
            Movie.decodeStream(stream)
        }
    }.getOrNull()
}

