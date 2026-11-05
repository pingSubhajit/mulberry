package com.subhajit.mulberry.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.network.CanvasPhotoUploadResponse
import com.subhajit.mulberry.network.MulberryApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Canvas photos, kept on disk per person once downloaded or taken here, and decoded for the canvas
 * at screen size. A photo never changes once uploaded, so its id names its file for good. As on iOS.
 */
@Singleton
class CanvasPhotoStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: MulberryApiService,
    private val sessionBootstrapRepository: SessionBootstrapRepository
) {
    private val files = CanvasPhotoFiles(File(context.cacheDir, "CanvasPhotos"))

    // Keyed by photo id alone: ids are random, and the files behind them are kept per person.
    private val bitmaps = CanvasPhotoMemoryCache<Bitmap>(cacheBytes()) { it.allocationByteCount }
    private val loads = ConcurrentHashMap<String, Deferred<Bitmap?>>()
    // Loads outlive the screen that asked for them, so a photo decoded once stays decoded.
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The photos decoded so far, so a canvas can redraw as each one arrives. */
    val loadedPhotoIds: StateFlow<Set<String>> = bitmaps.loadedIds

    /** The decoded photo, if it is already in memory. Safe to call while drawing. */
    fun cachedBitmap(photoId: String): Bitmap? = bitmaps.get(photoId.lowercase())

    /** The decoded photo, downloaded first if this device has never had it. Null while offline. */
    suspend fun loadBitmap(photoId: String): Bitmap? {
        cachedBitmap(photoId)?.let { return it }
        val key = photoId.lowercase()
        val load = loads.computeIfAbsent(key) {
            val generation = bitmaps.generation
            loadScope.async {
                try {
                    val file = file(photoId, generation) ?: return@async null
                    decode(file)?.also { remember(it, photoId, generation) }
                } finally {
                    loads.remove(key)
                }
            }
        }
        return load.await()
    }

    /**
     * Uploads a photo taken or picked here, and keeps it, so the canvas shows it without a download.
     * [bitmap] is what [bytes] decode to, put in memory under the new id.
     */
    suspend fun upload(bytes: ByteArray, contentType: String, bitmap: Bitmap?): CanvasPhotoUploadResponse =
        withContext(Dispatchers.IO) {
            val generation = bitmaps.generation
            val extension = if (contentType == PNG) "png" else "jpg"
            val part = MultipartBody.Part.createFormData(
                "image",
                "photo.$extension",
                bytes.toRequestBody(contentType.toMediaType())
            )
            val result = apiService.uploadCanvasPhoto(part)
            destination(result.photoId)?.takeIf { generation == bitmaps.generation }?.let { destination ->
                runCatching {
                    destination.parentFile?.mkdirs()
                    writeAtomically(destination, bytes)
                }
            }
            bitmap?.let { remember(it, result.photoId, generation) }
            result
        }

    /**
     * Forgets [userId]'s photos, on disk and in memory: on sign-out and account deletion. A load
     * still under way when this runs keeps nothing.
     */
    suspend fun removeAccount(userId: String) = withContext(Dispatchers.IO) {
        bitmaps.clear()
        files.removeAccount(userId)
    }

    private fun remember(bitmap: Bitmap, photoId: String, generation: Long) {
        bitmaps.put(photoId.lowercase(), bitmap, generation)
    }

    private suspend fun file(photoId: String, generation: Long): File? {
        val destination = destination(photoId) ?: return null
        if (destination.isFile && destination.length() > 0) return destination
        return runCatching {
            val bytes = apiService.getCanvasPhoto(photoId).use { body -> body.bytes() }
            if (bytes.isEmpty()) return null
            // Signed out while it downloaded: nothing is kept.
            if (generation != bitmaps.generation) return null
            destination.parentFile?.mkdirs()
            writeAtomically(destination, bytes)
            destination
        }.onFailure { error ->
            Log.w(TAG, "Canvas photo download failed photoId=$photoId", error)
        }.getOrNull()
    }

    private suspend fun destination(photoId: String): File? =
        files.file(sessionBootstrapRepository.state.first().userId, photoId)

    private fun writeAtomically(destination: File, bytes: ByteArray) {
        val temp = File(destination.parentFile, "${destination.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(destination)) {
            destination.delete()
            temp.renameTo(destination)
        }
    }

    private companion object {
        const val TAG = "CanvasPhotoStore"
        const val PNG = "image/png"

        fun cacheBytes(): Long {
            val max = Runtime.getRuntime().maxMemory()
            return (max / 8).coerceIn(32L * 1024 * 1024, 160L * 1024 * 1024)
        }

        /** Decoded at most [CanvasPhotoProcessing.MAX_PIXELS] on the long edge, as iOS does. */
        fun decode(file: File): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val longEdge = max(bounds.outWidth, bounds.outHeight)
            if (longEdge <= 0) return null
            var sample = 1
            while (longEdge / (sample * 2) >= CanvasPhotoProcessing.MAX_PIXELS) sample *= 2
            val decoded = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            return CanvasPhotoProcessing.fitted(decoded)
        }
    }
}
