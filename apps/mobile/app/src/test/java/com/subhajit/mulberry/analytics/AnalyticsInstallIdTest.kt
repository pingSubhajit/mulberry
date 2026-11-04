package com.subhajit.mulberry.analytics

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.subhajit.mulberry.core.config.AppConfigFactory
import com.subhajit.mulberry.data.bootstrap.AppSession
import com.subhajit.mulberry.data.bootstrap.DataStoreSessionBootstrapRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsInstallIdTest {
    @Test
    fun `makes one lowercase uuid and keeps it across sign-out`() = runTest {
        val file = File.createTempFile("mulberry-install-id", ".preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file }
        )
        val sessionRepository = DataStoreSessionBootstrapRepository(
            dataStore = dataStore,
            appConfig = AppConfigFactory.fromFields(
                environmentName = "dev",
                apiBaseUrl = "https://api.mulberry.test",
                enableDebugMenu = true
            )
        )

        val id = AnalyticsInstallId(dataStore).get()
        assertTrue(id, UUID_PATTERN.matches(id))
        assertEquals(id, AnalyticsInstallId(dataStore).get())

        sessionRepository.cacheSession(AppSession(accessToken = "access", refreshToken = "refresh", userId = "user-1"))
        sessionRepository.reset()

        assertEquals(id, AnalyticsInstallId(dataStore).get())
    }

    private companion object {
        val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
