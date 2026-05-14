package com.subhajit.mulberry.whatsnew

import com.subhajit.mulberry.network.MulberryApiService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

sealed interface WhatsNewFetchResult {
    data class Success(val rawMarkdown: String) : WhatsNewFetchResult
    data object NotFound : WhatsNewFetchResult
    data class Error(val retryable: Boolean, val message: String) : WhatsNewFetchResult
}

sealed interface WhatsNewListFetchResult {
    data class Success(
        val entries: List<WhatsNewEntry>,
        val nextCursor: String?
    ) : WhatsNewListFetchResult

    data class Error(val retryable: Boolean, val message: String) : WhatsNewListFetchResult
}

@Singleton
class WhatsNewRepository @Inject constructor(
    private val api: MulberryApiService
) {
    suspend fun fetchByVersion(versionName: String): WhatsNewFetchResult =
        fetch { api.getWhatsNewMarkdown(versionName) }

    suspend fun fetchLatest(): WhatsNewFetchResult =
        fetch { api.getLatestWhatsNewMarkdown() }

    suspend fun fetchPage(
        cursor: String?,
        limit: Int = DEFAULT_PAGE_SIZE
    ): WhatsNewListFetchResult = withContext(Dispatchers.IO) {
        try {
            val response = api.getWhatsNewEntries(cursor = cursor, limit = limit)
            WhatsNewListFetchResult.Success(
                entries = response.items.map { item ->
                    val parsed = WhatsNewParser.parse(item.rawMarkdown)
                    if (parsed.versionName.isNullOrBlank()) {
                        parsed.copy(versionName = item.version)
                    } else {
                        parsed
                    }
                },
                nextCursor = response.nextCursor
            )
        } catch (e: IOException) {
            WhatsNewListFetchResult.Error(
                retryable = true,
                message = e.message ?: "Network error"
            )
        } catch (e: HttpException) {
            WhatsNewListFetchResult.Error(
                retryable = e.code() in 500..599,
                message = "HTTP ${e.code()}"
            )
        } catch (e: Exception) {
            WhatsNewListFetchResult.Error(
                retryable = false,
                message = e.message ?: "Unknown error"
            )
        }
    }

    suspend fun hasEntryForVersion(versionName: String): Boolean =
        fetchByVersion(versionName) is WhatsNewFetchResult.Success

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

    private companion object {
        private const val DEFAULT_PAGE_SIZE = 3
    }
}
