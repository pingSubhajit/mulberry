package com.subhajit.mulberry.wallpaper

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subhajit.mulberry.core.config.AppConfigFactory
import com.subhajit.mulberry.drawing.data.local.CanvasMetadataEntity
import com.subhajit.mulberry.drawing.data.local.CanvasStickerElementEntity
import com.subhajit.mulberry.drawing.data.local.DrawingDatabase
import com.subhajit.mulberry.drawing.data.local.StrokeEntity
import com.subhajit.mulberry.drawing.data.local.StrokePointEntity
import com.subhajit.mulberry.data.bootstrap.AppSession
import com.subhajit.mulberry.data.bootstrap.AuthStatus
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapState
import com.subhajit.mulberry.stickers.StickerAssetStore
import com.subhajit.mulberry.stickers.StickerAssetUrl
import com.subhajit.mulberry.stickers.StickerAssetVariant
import com.subhajit.mulberry.stickers.StickerCatalogCacheStore
import com.subhajit.mulberry.stickers.StickerCatalogRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultCanvasSnapshotRendererTest {
    private lateinit var database: DrawingDatabase
    private lateinit var renderer: DefaultCanvasSnapshotRenderer
    private lateinit var stickerAssetStore: StickerAssetStore

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, DrawingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stickerAssetStore = StickerAssetStore(
            context = context,
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val pngBytes = TEST_PNG_BYTES
                    val body = pngBytes.toResponseBody("image/png".toMediaType())
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body)
                        .build()
                }
                .build(),
            stickerCatalogRepository = FakeStickerCatalogRepository(),
            stickerCatalogCacheStore = FakeStickerCatalogCacheStore(),
            sessionBootstrapRepository = FakeSessionBootstrapRepository()
        )
        renderer = DefaultCanvasSnapshotRenderer(
            context = context,
            database = database,
            drawingDao = database.drawingDao(),
            canvasTextElementDao = database.canvasTextElementDao(),
            canvasStickerElementDao = database.canvasStickerElementDao(),
            canvasMetadataDao = database.canvasMetadataDao(),
            stickerAssetStore = stickerAssetStore,
            appConfig = AppConfigFactory.fromFields(
                environmentName = "dev",
                apiBaseUrl = "http://localhost",
                enableDebugMenu = false
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WallpaperFiles.snapshotFile(context).delete()
        File(context.filesDir, "stickers").deleteRecursively()
    }

    @Test
    fun renderCurrentSnapshotProducesFileAndUpdatesMetadata() = runBlocking {
        database.drawingDao().insertStroke(
            StrokeEntity(
                id = "stroke-1",
                colorArgb = 0xFF000000L,
                width = 12f,
                createdAt = 10L
            )
        )
        database.drawingDao().insertStrokePoints(
            listOf(
                StrokePointEntity(strokeId = "stroke-1", pointIndex = 0, x = 10f, y = 10f),
                StrokePointEntity(strokeId = "stroke-1", pointIndex = 1, x = 100f, y = 100f)
            )
        )
        database.canvasMetadataDao().upsertMetadata(
            CanvasMetadataEntity.default().copy(
                revision = 2L,
                isSnapshotDirty = true
            )
        )

        val result = renderer.renderCurrentSnapshot()
        val metadata = database.canvasMetadataDao().getMetadata()

        assertTrue(java.io.File(result.imagePath).exists())
        assertEquals(2L, metadata?.lastSnapshotRevision)
        assertEquals(false, metadata?.isSnapshotDirty)
        assertNotNull(metadata?.cachedImagePath)
    }

    @Test
    fun renderCurrentSnapshotDownloadsMissingStickerAssets() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packKey = "kawaii-cats"
        val packVersion = 1
        val stickerId = "test-sticker"
        val destination = stickerAssetStore.destinationFile(
            packKey = packKey,
            packVersion = packVersion,
            stickerId = stickerId,
            variant = StickerAssetVariant.FULL
        )
        destination.parentFile?.deleteRecursively()

        database.canvasStickerElementDao().upsert(
            CanvasStickerElementEntity(
                id = "sticker-1",
                createdAt = 10L,
                zIndex = 1L,
                centerX = 0.5f,
                centerY = 0.5f,
                rotationRad = 0f,
                scale = 0.22f,
                packKey = packKey,
                packVersion = packVersion,
                stickerId = stickerId
            )
        )
        database.canvasMetadataDao().upsertMetadata(
            CanvasMetadataEntity.default().copy(
                revision = 3L,
                isSnapshotDirty = true
            )
        )

        renderer.renderCurrentSnapshot()

        assertTrue(destination.exists())
        assertTrue(destination.length() > 0L)
    }

    private class FakeStickerCatalogRepository : StickerCatalogRepository {
        override suspend fun fetchPacks() = emptyList<com.subhajit.mulberry.stickers.StickerPackSummary>()

        override suspend fun fetchPackDetail(
            packKey: String,
            version: Int?
        ): com.subhajit.mulberry.stickers.StickerPackDetail {
            throw UnsupportedOperationException("not needed for this test")
        }

        override suspend fun fetchStickerAssetUrl(
            packKey: String,
            version: Int,
            stickerId: String,
            variant: StickerAssetVariant
        ): StickerAssetUrl {
            return StickerAssetUrl(
                url = "https://example.invalid/sticker.png",
                expiresInSeconds = 600
            )
        }
    }

    private class FakeStickerCatalogCacheStore : StickerCatalogCacheStore {
        override suspend fun getCachedPacks(userId: String) = null
        override suspend fun putPacks(userId: String, packs: List<com.subhajit.mulberry.stickers.StickerPackSummary>, fetchedAtMs: Long) = Unit
        override suspend fun getCachedPackDetail(userId: String, packKey: String, packVersion: Int) = null
        override suspend fun putPackDetail(userId: String, detail: com.subhajit.mulberry.stickers.StickerPackDetail) = Unit
        override suspend fun markPackAccessed(userId: String, packKey: String, packVersion: Int, accessedAtMs: Long) = Unit
        override suspend fun getPackAccessMap(userId: String): Map<String, Long> = emptyMap()
        override suspend fun evictPackVersion(userId: String, packKey: String, packVersion: Int) = Unit
        override suspend fun clearUser(userId: String) = Unit
        override suspend fun clearAll() = Unit
    }

    private class FakeSessionBootstrapRepository : SessionBootstrapRepository {
        private val stateFlow = MutableStateFlow(
            SessionBootstrapState(
                authStatus = AuthStatus.SIGNED_IN,
                hasCompletedOnboarding = true,
                userId = "test-user",
                pairingStatus = PairingStatus.PAIRED,
                pairSessionId = "test-pair-session"
            )
        )
        private val sessionFlow = MutableStateFlow<AppSession?>(
            AppSession(accessToken = "test", refreshToken = "test", userId = "test-user")
        )

        override val state: Flow<SessionBootstrapState> = stateFlow
        override val session: Flow<AppSession?> = sessionFlow

        override suspend fun getCurrentSession(): AppSession? = sessionFlow.value
        override suspend fun cacheBootstrap(state: SessionBootstrapState) {
            stateFlow.value = state
        }

        override suspend fun cacheSession(session: AppSession?) {
            sessionFlow.value = session
        }

        override suspend fun setWallpaperConfigured(configured: Boolean) = Unit
        override suspend fun seedDemoSession() = Unit

        override suspend fun reset() {
            sessionFlow.value = null
            stateFlow.value = SessionBootstrapState(authStatus = AuthStatus.SIGNED_OUT)
        }
    }

    private companion object {
        // 1x1 transparent PNG.
        val TEST_PNG_BYTES = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
            0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
            0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01,
            0x0D, 0x0A, 0x2D, 0xB4.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
        )
    }
}
