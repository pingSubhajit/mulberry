package com.subhajit.mulberry.analytics

import com.subhajit.mulberry.network.MulberryApiService
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class ApiAnalyticsEventSenderTest {
    private val event = AnalyticsEventRecord(
        id = "6f9619ff-8b86-d011-b42d-00c04fc964ff",
        event = "mulberry_push_opened",
        occurredAt = "2026-11-04T12:00:00.123Z",
        properties = mapOf("push_type" to "REACTION")
    )

    @Test
    fun `posts the batch in the contract's shape`() = runTest {
        val sent = mutableListOf<Request>()
        val sender = sender(sent) { 202 }

        assertEquals(AnalyticsSendResult.DROP, sender.send(listOf(event)))

        val request = sent.single()
        assertEquals("POST", request.method)
        assertEquals("/analytics/events", request.url.encodedPath)
        assertEquals(
            """{"events":[{"id":"6f9619ff-8b86-d011-b42d-00c04fc964ff","event":"mulberry_push_opened",""" +
                """"occurredAt":"2026-11-04T12:00:00.123Z","properties":{"push_type":"REACTION"}}]}""",
            Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        )
    }

    @Test
    fun `drops on 2xx and 4xx, keeps on 5xx and network errors`() = runTest {
        assertEquals(AnalyticsSendResult.DROP, sender { 401 }.send(listOf(event)))
        assertEquals(AnalyticsSendResult.DROP, sender { 422 }.send(listOf(event)))
        assertEquals(AnalyticsSendResult.KEEP, sender { 500 }.send(listOf(event)))
        assertEquals(AnalyticsSendResult.KEEP, sender { throw IOException("offline") }.send(listOf(event)))
    }

    private fun sender(
        sent: MutableList<Request> = mutableListOf(),
        code: () -> Int
    ): ApiAnalyticsEventSender {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                sent += chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code())
                    .message("")
                    .body("""{"accepted":1,"rejected":0}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val api = Retrofit.Builder()
            .baseUrl("https://api.mulberry.test/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MulberryApiService::class.java)
        return ApiAnalyticsEventSender(api)
    }
}
