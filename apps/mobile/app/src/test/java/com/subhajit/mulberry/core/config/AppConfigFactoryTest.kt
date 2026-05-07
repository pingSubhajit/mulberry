package com.subhajit.mulberry.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigFactoryTest {
    @Test
    fun `maps dev environment fields into config`() {
        val config = AppConfigFactory.fromFields(
            environmentName = "dev",
            apiBaseUrl = "https://dev.api.elaris.local",
            enableDebugMenu = true
        )

        assertEquals(AppEnvironment.DEV, config.environment)
        assertEquals("https://dev.api.elaris.local", config.apiBaseUrl)
        assertTrue(config.enableDebugMenu)
        assertTrue(config.defaultFeatureFlags.showDeveloperBootstrapActions)
    }

    @Test
    fun `maps prod environment fields into config`() {
        val config = AppConfigFactory.fromFields(
            environmentName = "prod",
            apiBaseUrl = "https://api.mulberry.my",
            enableDebugMenu = false
        )

        assertEquals(AppEnvironment.PROD, config.environment)
        assertEquals("https://api.mulberry.my", config.apiBaseUrl)
        assertFalse(config.enableDebugMenu)
        assertFalse(config.defaultFeatureFlags.showDeveloperBootstrapActions)
    }
}
