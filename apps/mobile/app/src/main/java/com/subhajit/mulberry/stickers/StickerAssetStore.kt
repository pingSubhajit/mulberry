package com.subhajit.mulberry.stickers

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class StickerAssetStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val stickerCatalogRepository: StickerCatalogRepository
) {
    suspend fun getOrDownloadStickerAsset(
        packKey: String,
        packVersion: Int,
        stickerId: String,
        variant: StickerAssetVariant,
        urlHint: String? = null
    ): File? = withContext(Dispatchers.IO) {
        val normalizedPackKey = packKey.trim().lowercase()
        val normalizedStickerId = stickerId.trim()
        if (normalizedPackKey.isBlank() || packVersion <= 0 || normalizedStickerId.isBlank()) return@withContext null

        val destination = destinationFile(
            packKey = normalizedPackKey,
            packVersion = packVersion,
            stickerId = normalizedStickerId,
            variant = variant
        )
        if (destination.exists() && destination.length() > 0) return@withContext destination

        val url = urlHint ?: runCatching {
            stickerCatalogRepository.fetchStickerAssetUrl(
                packKey = normalizedPackKey,
                version = packVersion,
                stickerId = normalizedStickerId,
                variant = variant
            ).url
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@withContext null

        destination.parentFile?.mkdirs()
        val temp = File.createTempFile("sticker-", ".download", destination.parentFile ?: context.cacheDir)
        try {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                temp.outputStream().use { output ->
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }
            if (!temp.renameTo(destination)) {
                temp.copyTo(destination, overwrite = true)
                temp.delete()
            }
            destination
        } finally {
            if (temp.exists() && temp != destination) {
                temp.delete()
            }
        }
    }

    fun destinationFile(
        packKey: String,
        packVersion: Int,
        stickerId: String,
        variant: StickerAssetVariant
    ): File {
        val base = File(context.filesDir, "stickers")
        val folder = File(base, "${packKey.trim().lowercase()}/${packVersion}")
        val name = "${stickerId.trim()}-${variant.name.lowercase()}.png"
        return File(folder, name)
    }

    suspend fun clearAllStickerAssets() = withContext(Dispatchers.IO) {
        File(context.filesDir, "stickers").deleteRecursively()
    }
}
