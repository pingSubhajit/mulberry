package com.subhajit.mulberry.analytics

import com.subhajit.mulberry.network.MulberryApiService
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class AnalyticsEventsRequest(val events: List<AnalyticsEventRecord>)

enum class AnalyticsSendResult {
    /** The server answered for the batch (2xx or 4xx), so it is never sent again. */
    DROP,

    /** A network error or 5xx: the batch stays queued for the next try. */
    KEEP
}

fun interface AnalyticsEventSender {
    suspend fun send(events: List<AnalyticsEventRecord>): AnalyticsSendResult
}

/**
 * Sends through the app's OkHttp client, which adds the bearer token when signed in and the
 * anonymous ID always. There is no authenticator on that client, so a 401 never refreshes or
 * signs anyone out; it just drops the batch.
 */
@Singleton
class ApiAnalyticsEventSender @Inject constructor(
    private val apiService: MulberryApiService
) : AnalyticsEventSender {
    override suspend fun send(events: List<AnalyticsEventRecord>): AnalyticsSendResult = try {
        val response = apiService.sendAnalyticsEvents(AnalyticsEventsRequest(events))
        response.body()?.close()
        response.errorBody()?.close()
        resultForStatus(response.code())
    } catch (_: IOException) {
        AnalyticsSendResult.KEEP
    }

    internal companion object {
        fun resultForStatus(code: Int): AnalyticsSendResult =
            if (code in 200..299 || code in 400..499) AnalyticsSendResult.DROP else AnalyticsSendResult.KEEP
    }
}
