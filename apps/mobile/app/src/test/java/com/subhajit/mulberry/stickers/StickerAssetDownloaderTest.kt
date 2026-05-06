package com.subhajit.mulberry.stickers

import java.net.SocketTimeoutException
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StickerAssetDownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun downloadStickerAssetToTempFileReturnsFailureOnSocketTimeout() {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor {
                throw SocketTimeoutException("timeout")
            }
            .build()

        val tempFile = tmp.newFile("sticker.download")
        val result = downloadStickerAssetToTempFile(
            okHttpClient = okHttpClient,
            url = "https://example.invalid/sticker.png",
            tempFile = tempFile
        )

        assertFalse(result.ok)
        assertEquals(-1, result.httpCode)
    }

    @Test
    fun downloadStickerAssetToTempFileReturnsFailureOnInvalidUrl() {
        val okHttpClient = OkHttpClient()

        val tempFile = tmp.newFile("sticker.download")
        val result = downloadStickerAssetToTempFile(
            okHttpClient = okHttpClient,
            url = "not-a-url",
            tempFile = tempFile
        )

        assertFalse(result.ok)
        assertEquals(-1, result.httpCode)
    }
}

