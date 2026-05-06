package com.subhajit.mulberry.whatsnew

import com.subhajit.mulberry.network.MulberryApiService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.IOException

sealed interface WhatsNewFetchResult {
    data class Success(val rawMarkdown: String) : WhatsNewFetchResult
    data object NotFound : WhatsNewFetchResult
    data class Error(val retryable: Boolean, val message: String) : WhatsNewFetchResult
}

@Singleton
class WhatsNewRepository @Inject constructor(
    private val api: MulberryApiService
) {
    suspend fun fetchByVersion(versionName: String): WhatsNewFetchResult =
        fetch { api.getWhatsNewMarkdown(versionName) }

    suspend fun fetchLatest(): WhatsNewFetchResult =
        fetch { api.getLatestWhatsNewMarkdown() }

    private suspend fun fetch(
        call: suspend () -> Response<ResponseBody>
    ): WhatsNewFetchResult = withContext(Dispatchers.IO) {
        try {
            val response = call()
            if (response.isSuccessful) {
                val body = response.body()?.string().orEmpty()
                return@withContext WhatsNewFetchResult.Success(body)
            }
            if (response.code() == 404) return@withContext WhatsNewFetchResult.NotFound
            val retryable = response.code() in 500..599
            return@withContext WhatsNewFetchResult.Error(
                retryable = retryable,
                message = "HTTP ${response.code()}"
            )
        } catch (e: IOException) {
            return@withContext WhatsNewFetchResult.Error(
                retryable = true,
                message = e.message ?: "Network error"
            )
        } catch (e: Exception) {
            return@withContext WhatsNewFetchResult.Error(
                retryable = false,
                message = e.message ?: "Unknown error"
            )
        }
    }
}

