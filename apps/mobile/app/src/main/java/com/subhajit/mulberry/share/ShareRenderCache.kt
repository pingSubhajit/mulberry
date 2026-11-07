package com.subhajit.mulberry.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The preview the share sheet opens on, as last drawn, kept on disk. The next opening shows it
 * straight away while it is drawn afresh, so only the clock or a new doodle changes. It shows
 * this pair's lock screen, so signing out clears it.
 */
@Singleton
class ShareRenderCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Writes in order, so the newest preview is the one left.
    private val writes = Mutex()

    private val directory: File get() = File(context.cacheDir, DIRECTORY)

    suspend fun preview(key: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = previewFile(key)
        if (!file.exists()) return@withContext null
        runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
    }

    /** Keeps [image] as the preview, replacing the one kept before. */
    suspend fun savePreview(image: Bitmap, key: String) = withContext(Dispatchers.IO) {
        writes.withLock {
            val file = previewFile(key)
            try {
                directory.mkdirs()
                val temporary = File(directory, "${file.name}.tmp")
                val wrote = temporary.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                if (!wrote || !temporary.renameTo(file)) {
                    temporary.delete()
                    return@withLock
                }
                directory.listFiles()
                    ?.filter { it.name.startsWith(PREVIEW_PREFIX) && it.name != file.name }
                    ?.forEach(File::delete)
            } catch (error: Exception) {
                Log.w(TAG, "Unable to keep the share preview", error)
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        writes.withLock { directory.deleteRecursively() }
    }

    private fun previewFile(key: String): File = File(directory, "$PREVIEW_PREFIX${digest(key)}.jpg")

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .take(DIGEST_BYTES)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "ShareRenderCache"
        const val DIRECTORY = "share_renders"
        const val PREVIEW_PREFIX = "preview-"
        const val JPEG_QUALITY = 92
        const val DIGEST_BYTES = 12
    }
}
