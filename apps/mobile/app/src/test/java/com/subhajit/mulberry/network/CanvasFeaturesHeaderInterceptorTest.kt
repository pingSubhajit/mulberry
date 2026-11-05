package com.subhajit.mulberry.network

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasFeaturesHeaderInterceptorTest {
    @Test
    fun namesTheCanvasFeaturesOnRequestsToMulberrysServer() {
        val sent = mutableListOf<Request>()
        client(sent).newCall(Request.Builder().url("https://api.mulberry.test/canvas/sync").build()).execute().close()
        assertEquals("photos", sent.single().header(CanvasFeaturesHeaderInterceptor.HEADER))
    }

    @Test
    fun leavesOtherHostsWithoutTheFeatures() {
        val sent = mutableListOf<Request>()
        client(sent).newCall(Request.Builder().url("https://stickers.example.test/heart.png").build()).execute().close()
        assertNull(sent.single().header(CanvasFeaturesHeaderInterceptor.HEADER))
    }

    private fun client(sent: MutableList<Request>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(CanvasFeaturesHeaderInterceptor("api.mulberry.test"))
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
