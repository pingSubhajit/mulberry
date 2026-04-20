package com.subhajit.elaris.data.bootstrap

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.subhajit.elaris.core.config.AppConfigFactory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreSessionBootstrapRepositoryTest {
    @Test
    fun `first launch starts with expected defaults`() = runTest {
        val repository = createRepository(this)

        assertEquals(SessionBootstrapState(), repository.state.first())
    }

    @Test
    fun `completing onboarding persists state`() = runTest {
        val repository = createRepository(this)

        repository.completeOnboarding()

        assertTrue(repository.state.first().hasCompletedOnboarding)
    }

    @Test
    fun `reset clears bootstrap state`() = runTest {
        val repository = createRepository(this)
        repository.completeOnboarding()
        repository.setPairingStatus(PairingStatus.PAIRED_PLACEHOLDER)
        repository.setSessionDisplayState(SessionDisplayState.PLACEHOLDER_SESSION)
        repository.setWallpaperConfigured(true)

        repository.reset()

        assertEquals(SessionBootstrapState(), repository.state.first())
    }

    @Test
    fun `seed demo session persists placeholder paired state in dev`() = runTest {
        val repository = createRepository(this, enableDebugMenu = true)

        repository.seedDemoSession()

        val state = repository.state.first()
        assertTrue(state.hasCompletedOnboarding)
        assertEquals(PairingStatus.PAIRED_PLACEHOLDER, state.pairingStatus)
        assertEquals(SessionDisplayState.PLACEHOLDER_SESSION, state.sessionDisplayState)
        assertFalse(state.hasWallpaperConfigured)
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
