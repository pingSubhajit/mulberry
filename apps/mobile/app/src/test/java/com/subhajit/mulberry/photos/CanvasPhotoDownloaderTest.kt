package com.subhajit.mulberry.photos

import com.subhajit.mulberry.network.MulberryApiService
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class CanvasPhotoDownloaderTest {
    private data class RecordedRequest(val path: String, val authorization: String?)

    private lateinit var server: ServerSocket
    private lateinit var downloader: CanvasPhotoDownloader
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private val photoBytes = byteArrayOf(1, 2, 3, 4, 5)

    private val baseUrl: String
        get() = "http://127.0.0.1:${server.localPort}"

    @Before
    fun setUp() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { serve(it) }
            }
        }

        // Adds the token to every request, whatever its host, as AuthHeaderInterceptor does.
        val apiClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer mulberry-token")
                        .build()
                )
            }
            .build()
        val apiService = Retrofit.Builder()
            .baseUrl("$baseUrl/")
            .client(apiClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MulberryApiService::class.java)
        downloader = CanvasPhotoDownloader(apiService, apiClient)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun downloadAsksForTheUrlThenFetchesItWithoutTheAuthHeader() = runTest {
        val bytes = downloader.download("photo-1")

        assertArrayEquals(photoBytes, bytes)
        assertEquals(
            listOf(
                RecordedRequest("/canvas/photos/photo-1/url", "Bearer mulberry-token"),
                RecordedRequest("/storage/photo-1", null)
            ),
            requests.toList()
        )
    }

    @Test
    fun urlNotFoundIsUnavailable() = runTest {
        val bytes = downloader.download("someone-elses")

        assertNull(bytes)
        assertEquals(listOf("/canvas/photos/someone-elses/url"), requests.map { it.path })
    }

    @Test
    fun failedStorageFetchIsUnavailable() = runTest {
        assertNull(downloader.download("expired"))
    }

    /** One request per connection: enough HTTP/1.1 for OkHttp. */
    private fun serve(socket: Socket) {
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = input.readLine() ?: return
        val headers = generateSequence { input.readLine() }
            .takeWhile { it.isNotEmpty() }
            .associate { line -> line.substringBefore(':').lowercase() to line.substringAfter(':').trim() }
        val path = requestLine.split(' ')[1].substringBefore('?')
        requests += RecordedRequest(path = path, authorization = headers["authorization"])
        val (code, body) = when (path) {
            "/canvas/photos/photo-1/url" ->
                200 to """{"url":"$baseUrl/storage/photo-1?token=signed","expiresInSeconds":300}""".toByteArray()
            "/canvas/photos/expired/url" ->
                200 to """{"url":"$baseUrl/storage/expired?token=signed","expiresInSeconds":300}""".toByteArray()
            "/storage/photo-1" -> 200 to photoBytes
            "/storage/expired" -> 400 to "expired".toByteArray()
            else -> 404 to """{"error":"not_found"}""".toByteArray()
        }
        val output = socket.getOutputStream()
        output.write(
            "HTTP/1.1 $code X\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.ISO_8859_1)
        )
        output.write(body)
        output.flush()
    }
}
