package com.subhajit.mulberry.network

import com.subhajit.mulberry.analytics.DeviceFamily
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalyticsHeaderInterceptorTest {
    private val installId = "0f8fad5b-d9cb-469f-a165-70867728950e"

    @Test
    fun namesTheDeviceFamilyAndInstallIdOnRequestsToMulberrysServer() {
        var family = DeviceFamily.ANDROID_PHONE
        val sent = mutableListOf<Request>()
        val client = client(AnalyticsHeaderInterceptor("api.mulberry.test", { family }, { installId }), sent)

        client.newCall(Request.Builder().url("https://api.mulberry.test/bootstrap").build()).execute().close()
        family = DeviceFamily.ANDROID_TABLET
        client.newCall(Request.Builder().url("https://api.mulberry.test/bootstrap").build()).execute().close()

        assertEquals(
            listOf("android_phone", "android_tablet"),
            sent.map { it.header(AnalyticsHeaderInterceptor.DEVICE_FAMILY_HEADER) }
        )
        assertEquals(
            listOf(installId, installId),
            sent.map { it.header(AnalyticsHeaderInterceptor.ANONYMOUS_ID_HEADER) }
        )
    }

    @Test
    fun leavesOtherHostsWithoutEitherHeader() {
        val sent = mutableListOf<Request>()
        val client = client(
            AnalyticsHeaderInterceptor("api.mulberry.test", { DeviceFamily.ANDROID_PHONE }, { installId }),
            sent
        )

        client.newCall(Request.Builder().url("https://stickers.example.test/heart.png").build()).execute().close()

        assertNull(sent.single().header(AnalyticsHeaderInterceptor.DEVICE_FAMILY_HEADER))
        assertNull(sent.single().header(AnalyticsHeaderInterceptor.ANONYMOUS_ID_HEADER))
    }

    @Test
    fun tabletsStartAtASmallestWidthOf600dp() {
        assertEquals(DeviceFamily.ANDROID_PHONE, DeviceFamily.fromSmallestWidthDp(411))
        assertEquals(DeviceFamily.ANDROID_PHONE, DeviceFamily.fromSmallestWidthDp(599))
        assertEquals(DeviceFamily.ANDROID_TABLET, DeviceFamily.fromSmallestWidthDp(600))
        assertEquals(DeviceFamily.ANDROID_TABLET, DeviceFamily.fromSmallestWidthDp(800))
    }

    private fun client(interceptor: AnalyticsHeaderInterceptor, sent: MutableList<Request>): OkHttpClient =
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
