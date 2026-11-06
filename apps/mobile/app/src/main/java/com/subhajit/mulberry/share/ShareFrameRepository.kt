package com.subhajit.mulberry.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.annotation.StringRes
import com.subhajit.mulberry.R
import com.subhajit.mulberry.core.config.AppConfig
import com.subhajit.mulberry.network.MulberryApiService
import com.subhajit.mulberry.network.ShareFrameResponse
import com.subhajit.mulberry.stickers.downloadStickerAssetToTempFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Share frames: Plain and Polaroid bundled in the app, and the catalog from `GET /share-frames`.
 * Frame images are cached on disk by (key, version), as a published version never changes.
 */
@Singleton
class ShareFrameRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: MulberryApiService,
    private val okHttpClient: OkHttpClient,
    private val appConfig: AppConfig
) {
    private val downloadMutexes = ConcurrentHashMap<String, Mutex>()

    // Two decoded frames (about 8 MB each) so switching back and forth doesn't decode again.
    private val frameBitmaps = object : LruCache<String, Bitmap>(FRAME_BITMAP_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /** Plain and Polaroid, version 1, from the app's assets. */
    suspend fun bundledFrames(): List<ShareFrame> = withContext(Dispatchers.IO) {
        BUNDLED_FRAMES.mapNotNull { bundled ->
            val layout = runCatching {
                context.assets.open("$BUNDLED_ASSET_DIR/${bundled.key}/layout.json")
                    .bufferedReader()
                    .use { it.readText() }
            }.getOrNull()?.let(ShareFrameLayoutParser::parse) ?: return@mapNotNull null
            ShareFrame(
                key = bundled.key,
                version = BUNDLED_VERSION,
                title = context.getString(bundled.titleRes),
                sortOrder = bundled.sortOrder,
                availableFrom = null,
                availableUntil = null,
                image = ShareFrameImageSource.Bundled("$BUNDLED_ASSET_DIR/${bundled.key}/frame.webp"),
                layout = layout
            )
        }
    }

    /** The published catalog, or null when it can't be loaded. */
    suspend fun catalogFrames(): List<ShareFrame>? = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getShareFrames()
            response.items.orEmpty().mapNotNull { it.toShareFrame(::resolveUrl) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Share frame catalog unavailable", error)
            null
        }
    }

    /** The decoded 1080×1920 frame image: from memory, the disk cache, the app, or downloaded. */
    suspend fun loadFrameBitmap(frame: ShareFrame): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${frame.key}@${frame.version}"
        frameBitmaps.get(cacheKey)?.let { return@withContext it }
        val bitmap = when (val image = frame.image) {
            is ShareFrameImageSource.Bundled -> runCatching {
                context.assets.open(image.assetPath).use { input ->
                    BitmapFactory.decodeStream(input, null, argb8888Options())
                }
            }.getOrNull()
            is ShareFrameImageSource.Remote -> cachedFrameFile(frame, image.frameUrl)
                ?.let { file -> BitmapFactory.decodeFile(file.absolutePath, argb8888Options()) }
        } ?: return@withContext null
        frameBitmaps.put(cacheKey, bitmap)
        bitmap
    }

    private suspend fun cachedFrameFile(frame: ShareFrame, url: String): File? {
        val file = frameCacheFile(frame.key, frame.version)
        if (file.exists() && file.length() > 0) return file
        val mutex = downloadMutexes.getOrPut(file.name) { Mutex() }
        return mutex.withLock {
            if (file.exists() && file.length() > 0) return@withLock file
            val temp = File(file.parentFile, "${file.name}.tmp")
            val result = downloadStickerAssetToTempFile(okHttpClient, url, temp)
            if (!result.ok) {
                Log.w(TAG, "Share frame ${frame.key} v${frame.version} download failed (${result.httpCode})")
                temp.delete()
                return@withLock null
            }
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) {
                    temp.delete()
                    return@withLock null
                }
            }
            file
        }
    }

    private fun frameCacheFile(key: String, version: Int): File {
        val directory = File(context.cacheDir, FRAME_CACHE_DIR).apply { mkdirs() }
        return File(directory, shareFrameCacheFileName(key, version))
    }

    private fun resolveUrl(url: String): String = resolveShareFrameUrl(url, appConfig.apiBaseUrl)

    private fun argb8888Options() = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    private data class BundledFrame(val key: String, @StringRes val titleRes: Int, val sortOrder: Int)

    private companion object {
        const val TAG = "ShareFrames"
        const val BUNDLED_ASSET_DIR = "share_frames"
        const val BUNDLED_VERSION = 1
        const val FRAME_CACHE_DIR = "share_frames"
        const val FRAME_BITMAP_CACHE_BYTES = 2 * SHARE_OUTPUT_WIDTH * SHARE_OUTPUT_HEIGHT * 4

        // Polaroid first: it's the frame picked when no seasonal frame is in its window.
        val BUNDLED_FRAMES = listOf(
            BundledFrame(key = ShareFrameSelector.POLAROID_KEY, titleRes = R.string.share_frame_polaroid, sortOrder = 0),
            BundledFrame(key = "plain", titleRes = R.string.share_frame_plain, sortOrder = 1)
        )
    }
}

/** The catalog when it loaded and has frames this app can draw; otherwise the bundled frames. */
fun chooseShareFrames(catalog: List<ShareFrame>?, bundled: List<ShareFrame>): List<ShareFrame> =
    catalog?.takeIf { it.isNotEmpty() } ?: bundled

/** One cached file per (key, version), named safely whatever the key holds. */
internal fun shareFrameCacheFileName(key: String, version: Int): String =
    "${key.lowercase().replace(Regex("[^a-z0-9-]"), "_")}-v$version.webp"

/** Catalog URLs are absolute; a path is taken as relative to the API's host. */
internal fun resolveShareFrameUrl(url: String, apiBaseUrl: String): String =
    if (url.startsWith("/")) apiBaseUrl.trimEnd('/') + url else url

/** A catalog item as a frame, or null when it's incomplete or its layout can't be drawn here. */
internal fun ShareFrameResponse.toShareFrame(resolveUrl: (String) -> String = { it }): ShareFrame? {
    val key = key?.takeIf { it.isNotBlank() } ?: return null
    val version = version?.takeIf { it > 0 } ?: return null
    val frameUrl = frameUrl?.takeIf { it.isNotBlank() } ?: return null
    val layout = ShareFrameLayoutParser.parse(layout) ?: return null
    return ShareFrame(
        key = key,
        version = version,
        title = title ?: key,
        sortOrder = sortOrder ?: 0,
        availableFrom = CalendarDate.parse(availableFrom),
        availableUntil = CalendarDate.parse(availableUntil),
        image = ShareFrameImageSource.Remote(
            frameUrl = resolveUrl(frameUrl),
            thumbnailUrl = thumbnailUrl?.takeIf { it.isNotBlank() }?.let(resolveUrl)
        ),
        layout = layout
    )
}
