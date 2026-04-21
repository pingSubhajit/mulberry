package com.subhajit.mulberry.data.bootstrap

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.subhajit.mulberry.core.config.AppConfigFactory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreSessionBootstrapRepositoryTest {
    @Test
    fun `first launch starts with expected defaults`() = runTest {
        val repository = createRepository(this)

        assertEquals(SessionBootstrapState(), repository.state.first())
    }

    @Test
    fun `cache bootstrap persists signed in onboarding state`() = runTest {
        val repository = createRepository(this)

        repository.cacheSession(
            AppSession(
                accessToken = "access",
                refreshToken = "refresh",
                userId = "user-1"
            )
        )
        repository.cacheBootstrap(
            SessionBootstrapState(
                authStatus = AuthStatus.SIGNED_IN,
                hasCompletedOnboarding = true,
                userId = "user-1",
                userDisplayName = "Subhajit",
                userPhotoUrl = "https://example.test/avatar.png",
                partnerDisplayName = "Ankita",
                anniversaryDate = "2026-01-01",
                pairingStatus = PairingStatus.UNPAIRED
            )
        )

        val state = repository.state.first()
        val session = repository.session.first()

        assertTrue(state.hasCompletedOnboarding)
        assertEquals(AuthStatus.SIGNED_IN, state.authStatus)
        assertEquals("Subhajit", state.userDisplayName)
        assertEquals("https://example.test/avatar.png", state.userPhotoUrl)
        assertNotNull(session)
    }

    @Test
    fun `reset clears bootstrap state`() = runTest {
        val repository = createRepository(this)
        repository.cacheSession(
            AppSession(
                accessToken = "access",
                refreshToken = "refresh",
                userId = "user-1"
            )
        )
        repository.cacheBootstrap(
            SessionBootstrapState(
                authStatus = AuthStatus.SIGNED_IN,
                hasCompletedOnboarding = true,
                userId = "user-1",
                pairingStatus = PairingStatus.PAIRED,
                pairSessionId = "pair-1"
            )
        )
        repository.setWallpaperConfigured(true)

        repository.reset()

        assertEquals(SessionBootstrapState(), repository.state.first())
        assertNull(repository.session.first())
    }

    @Test
    fun `seed demo session persists paired state in dev`() = runTest {
        val repository = createRepository(this, enableDebugMenu = true)

        repository.seedDemoSession()

        val state = repository.state.first()
        val session = repository.session.first()

        assertTrue(state.hasCompletedOnboarding)
        assertEquals(AuthStatus.SIGNED_IN, state.authStatus)
        assertEquals(PairingStatus.PAIRED, state.pairingStatus)
        assertEquals("Subhajit", state.userDisplayName)
        assertFalse(state.hasWallpaperConfigured)
        assertNotNull(session)
    }

    private fun createRepository(
        scope: TestScope,
        enableDebugMenu: Boolean = true
    ): DataStoreSessionBootstrapRepository {
        val file = File.createTempFile("elaris-bootstrap", ".preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope.backgroundScope,
            produceFile = { file }
        )

        return DataStoreSessionBootstrapRepository(
            dataStore = dataStore,
            appConfig = AppConfigFactory.fromFields(
                environmentName = if (enableDebugMenu) "dev" else "prod",
                apiBaseUrl = "https://api.elaris.test",
                enableDebugMenu = enableDebugMenu
            )
        )
    }
}
