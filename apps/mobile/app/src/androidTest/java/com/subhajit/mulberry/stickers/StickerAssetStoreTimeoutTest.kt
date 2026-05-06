package com.subhajit.mulberry.stickers

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subhajit.mulberry.data.bootstrap.AppSession
import com.subhajit.mulberry.data.bootstrap.AuthStatus
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.PartnerWallpaperStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapState
import java.io.File
import java.net.SocketTimeoutException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StickerAssetStoreTimeoutTest {
    private lateinit var stickerAssetStore: StickerAssetStore

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        stickerAssetStore = StickerAssetStore(
            context = context,
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor {
                    throw SocketTimeoutException("timeout")
                }
                .build(),
            stickerCatalogRepository = FakeStickerCatalogRepository(),
            stickerCatalogCacheStore = FakeStickerCatalogCacheStore(),
            sessionBootstrapRepository = FakeSessionBootstrapRepository()
        )
    }

    @After
    fun tearDown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "stickers").deleteRecursively()
    }

    @Test
    fun getOrDownloadStickerAssetReturnsNullOnSocketTimeout() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packKey = "kawaii-cats"
        val packVersion = 1
        val stickerId = "test-sticker"
        val destination = stickerAssetStore.destinationFile(
            userId = "test-user",
            packKey = packKey,
            packVersion = packVersion,
            stickerId = stickerId,
            variant = StickerAssetVariant.FULL
        )
        destination.parentFile?.deleteRecursively()

        val result = stickerAssetStore.getOrDownloadStickerAsset(
            packKey = packKey,
            packVersion = packVersion,
            stickerId = stickerId,
            variant = StickerAssetVariant.FULL,
            userId = "test-user"
        )

        assertNull(result)
        assertFalse(destination.exists())
    }

    private class FakeStickerCatalogRepository : StickerCatalogRepository {
        override suspend fun fetchPacks() = emptyList<StickerPackSummary>()

        override suspend fun fetchPackDetail(packKey: String, version: Int?): StickerPackDetail {
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
        override suspend fun putPacks(userId: String, packs: List<StickerPackSummary>, fetchedAtMs: Long) = Unit
        override suspend fun getCachedPackDetail(userId: String, packKey: String, packVersion: Int) = null
        override suspend fun putPackDetail(userId: String, detail: StickerPackDetail) = Unit
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
        override suspend fun setPartnerWallpaperStatus(status: PartnerWallpaperStatus?) = Unit
        override suspend fun seedDemoSession() = Unit

        override suspend fun reset() {
            sessionFlow.value = null
            stateFlow.value = SessionBootstrapState(authStatus = AuthStatus.SIGNED_OUT)
        }
    }
}
