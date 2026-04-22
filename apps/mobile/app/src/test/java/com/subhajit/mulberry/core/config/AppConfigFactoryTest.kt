package com.subhajit.mulberry.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.subhajit.mulberry.drawing.render.CanvasStrokeRenderMode

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
        assertEquals(CanvasStrokeRenderMode.Hybrid, config.canvasStrokeRenderMode)
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
        assertEquals(CanvasStrokeRenderMode.Hybrid, config.canvasStrokeRenderMode)
        assertFalse(config.defaultFeatureFlags.showDeveloperBootstrapActions)
    }

    @Test
    fun `maps canvas stroke render mode aliases into config`() {
        val dryBrushConfig = AppConfigFactory.fromFields(
            environmentName = "dev",
            apiBaseUrl = "https://dev.api.elaris.local",
            enableDebugMenu = true,
            canvasStrokeRenderMode = "dry-brush-only"
        )
        val roundConfig = AppConfigFactory.fromFields(
            environmentName = "dev",
            apiBaseUrl = "https://dev.api.elaris.local",
            enableDebugMenu = true,
            canvasStrokeRenderMode = "round_stroke_only"
        )

        assertEquals(CanvasStrokeRenderMode.DryBrushOnly, dryBrushConfig.canvasStrokeRenderMode)
        assertEquals(CanvasStrokeRenderMode.RoundStrokeOnly, roundConfig.canvasStrokeRenderMode)
    }
}
