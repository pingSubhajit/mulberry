package com.subhajit.mulberry.network

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeZoneHeaderInterceptorTest {
    @Test
    fun namesTheCurrentTimeZoneOnRequestsToMulberrysServer() {
        var timeZone = "Asia/Kolkata"
        val sent = mutableListOf<Request>()
        val client = client(TimeZoneHeaderInterceptor("api.mulberry.test") { timeZone }, sent)

        client.newCall(Request.Builder().url("https://api.mulberry.test/bootstrap").build()).execute().close()
        timeZone = "America/New_York"
        client.newCall(Request.Builder().url("https://api.mulberry.test/bootstrap").build()).execute().close()

        assertEquals(
            listOf("Asia/Kolkata", "America/New_York"),
            sent.map { it.header(TimeZoneHeaderInterceptor.HEADER) }
        )
    }

    @Test
    fun leavesOtherHostsWithoutTheTimeZone() {
        val sent = mutableListOf<Request>()
        val client = client(TimeZoneHeaderInterceptor("api.mulberry.test") { "Asia/Kolkata" }, sent)

        client.newCall(Request.Builder().url("https://stickers.example.test/heart.png").build()).execute().close()

        assertNull(sent.single().header(TimeZoneHeaderInterceptor.HEADER))
    }

    private fun client(interceptor: TimeZoneHeaderInterceptor, sent: MutableList<Request>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { chain ->
                sent += chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(204)
                    .message("No Content")
                    .body("".toResponseBody())
                    .build()
            }
            .build()
}
