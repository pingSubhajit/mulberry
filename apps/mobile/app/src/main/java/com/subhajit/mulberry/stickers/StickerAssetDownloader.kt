package com.subhajit.mulberry.stickers

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class StickerAssetDownloadResult(
    val ok: Boolean,
    val httpCode: Int
)

internal fun downloadStickerAssetToTempFile(
    okHttpClient: OkHttpClient,
    url: String,
    tempFile: File
): StickerAssetDownloadResult {
    try {
        if (tempFile.exists()) tempFile.delete()
        tempFile.parentFile?.mkdirs()
        tempFile.createNewFile()

        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            val code = response.code
            if (!response.isSuccessful) return StickerAssetDownloadResult(false, code)
            val body = response.body ?: return StickerAssetDownloadResult(false, code)
            tempFile.outputStream().use { output ->
                body.byteStream().use { input ->
                    input.copyTo(output)
                }
            }
            return StickerAssetDownloadResult(tempFile.length() > 0L, code)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: IllegalArgumentException) {
        // Request.Builder.url(...) can throw for malformed / unexpected URLs.
        return StickerAssetDownloadResult(false, -1)
    } catch (_: IOException) {
        // Network failures (including timeouts) should not crash the app.
        return StickerAssetDownloadResult(false, -1)
    } finally {
        if (tempFile.exists() && tempFile.length() == 0L) {
            tempFile.delete()
        }
    }
}

