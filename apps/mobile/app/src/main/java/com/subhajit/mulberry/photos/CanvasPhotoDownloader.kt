package com.subhajit.mulberry.photos

import android.util.Log
import com.subhajit.mulberry.network.MulberryApiService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads a canvas photo: Mulberry hands out a short-lived signed URL, and the bytes come from
 * there. That URL points at storage, not at Mulberry, so it is fetched without Mulberry's headers:
 * the API client adds the bearer token to every request, and storage would try to validate it.
 */
@Singleton
class CanvasPhotoDownloader @Inject constructor(
    private val apiService: MulberryApiService,
    apiClient: OkHttpClient
) {
    // Same connection pool and timeouts as the API client, with none of its headers or logging.
    private val storageClient: OkHttpClient = apiClient.newBuilder()
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .build()

    /** The photo's bytes, or null when it is unavailable, gone, or the network fails. */
    suspend fun download(photoId: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val url = apiService.getCanvasPhotoUrl(photoId).url.toHttpUrlOrNull()
            if (url == null) {
                Log.w(TAG, "Canvas photo URL unusable photoId=$photoId")
                return@withContext null
            }
            val request = Request.Builder().url(url).get().build()
            storageClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Canvas photo download failed photoId=$photoId code=${response.code}")
                    return@withContext null
                }
                response.body?.bytes()?.takeIf { it.isNotEmpty() }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Canvas photo download failed photoId=$photoId", error)
            null
        }
    }

    private companion object {
        const val TAG = "CanvasPhotoDownloader"
    }
}
