package com.subhajit.elaris.core.flags

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.subhajit.elaris.core.config.AppConfigFactory
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreFeatureFlagProviderTest {
    @Test
    fun `dev overrides persist in datastore`() = runTest {
        val provider = createProvider(this, enableDebugMenu = true)

        provider.setOverride(FeatureFlag.WALLPAPER_SETUP_CTA, false)

        assertFalse(provider.flags.first().showWallpaperSetupCta)
    }

    @Test
    fun `prod ignores local overrides`() = runTest {
        val provider = createProvider(this, enableDebugMenu = false)

        provider.setOverride(FeatureFlag.DEVELOPER_BOOTSTRAP_ACTIONS, true)

        assertFalse(provider.flags.first().showDeveloperBootstrapActions)
    }

    @Test
    fun `clearing overrides restores defaults`() = runTest {
        val provider = createProvider(this, enableDebugMenu = true)

        provider.setOverride(FeatureFlag.PLACEHOLDER_PAIRING_CONTROLS, false)
        provider.clearOverrides()

        assertTrue(provider.flags.first().showPlaceholderPairingControls)
    }

    private fun createProvider(
        scope: TestScope,
        enableDebugMenu: Boolean
    ): DataStoreFeatureFlagProvider {
        val file = File.createTempFile("elaris-flags", ".preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope.backgroundScope,
            produceFile = { file }
        )

        return DataStoreFeatureFlagProvider(
            dataStore = dataStore,
            appConfig = AppConfigFactory.fromFields(
                environmentName = if (enableDebugMenu) "dev" else "prod",
                apiBaseUrl = "https://api.elaris.test",
                enableDebugMenu = enableDebugMenu
            )
        )
    }
}
