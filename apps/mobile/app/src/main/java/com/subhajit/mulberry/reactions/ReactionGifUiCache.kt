package com.subhajit.mulberry.reactions

import android.content.Context
import android.graphics.Movie
import android.os.Build
import androidx.annotation.MainThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The reactions' decoded GIFs for the app's screens, decoded once, off the main thread, and kept.
 * Decoding all six takes about a third of a second on a mid-range phone, which the rail used to
 * spend on the main thread each time it opened.
 *
 * Only the main thread reads and draws these: a [Movie] keeps its current frame, so it can't be
 * shared across threads. The live wallpaper decodes its own copies on its own thread.
 */
object ReactionGifUiCache {
    private val movies = mutableMapOf<ReactionType, Movie>()

    /** The decoded GIF, if it's ready. */
    @MainThread
    fun peek(type: ReactionType): Movie? = movies[type]

    /** The decoded GIF, decoding it in the background the first time. Null until its pack arrives. */
    @MainThread
    suspend fun movie(context: Context, type: ReactionType): Movie? {
        movies[type]?.let { return it }
        val decoded = withContext(Dispatchers.IO) {
            ReactionGifAssets.decodeMovie(context.applicationContext, type)
        } ?: return null
        return movies.getOrPut(type) { decoded }
    }

    /**
     * Decodes every reaction ahead of time, so the rail opens at once. Android 9 and later draw
     * reactions with the platform's animated drawable instead, so there is nothing to warm.
     */
    @MainThread
    suspend fun prewarm(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return
        ReactionType.entries.forEach { type -> movie(context, type) }
    }
}
