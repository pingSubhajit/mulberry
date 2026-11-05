package com.subhajit.mulberry.network

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthHeaderInterceptorTest {
    @Test
    fun signsRequestsToMulberrysServer() {
        val sent = mutableListOf<Request>()
        client(token = "access", sent).newCall(Request.Builder().url("https://api.mulberry.test/bootstrap").build()).execute().close()
        assertEquals("Bearer access", sent.single().header("Authorization"))
    }

    @Test
    fun neverSendsTheSessionToOtherHosts() {
        val sent = mutableListOf<Request>()
        client(token = "access", sent)
            .newCall(Request.Builder().url("https://storage.example.test/object/sign/stickers/heart.png?token=signed").build())
            .execute().close()
        assertNull(sent.single().header("Authorization"))
    }

    @Test
    fun leavesRequestsUnsignedWithoutASession() {
        val sent = mutableListOf<Request>()
        client(token = null, sent).newCall(Request.Builder().url("https://api.mulberry.test/bootstrap").build()).execute().close()
        assertNull(sent.single().header("Authorization"))
    }

    private fun client(token: String?, sent: MutableList<Request>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(AuthHeaderInterceptor("api.mulberry.test") { token })
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
