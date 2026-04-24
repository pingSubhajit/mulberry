package com.subhajit.mulberry.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCanvasSnapshotRendererPlacementTest {

    @Test
    fun placementCentersScreenViewportInsideWiderWallpaperBitmap() {
        val placement = calculateSnapshotPlacement(
            bitmapWidth = 1440,
            bitmapHeight = 2400,
            screenWidth = 1080,
            screenHeight = 2400,
            authoredViewportWidth = 0,
            authoredViewportHeight = 0
        )

        assertEquals(180f, placement.viewport.left, 0.001f)
        assertEquals(1260f, placement.viewport.right, 0.001f)
        assertEquals(1080f, placement.scale, 0.001f)
        assertEquals(180f, placement.offsetX, 0.001f)
        assertEquals(0f, placement.offsetY, 0.001f)
    }

    @Test
    fun placementFallsBackToScreenViewportWhenSourceSizeMissing() {
        val placement = calculateSnapshotPlacement(
            bitmapWidth = 4320,
            bitmapHeight = 2400,
            screenWidth = 1080,
            screenHeight = 2400,
            authoredViewportWidth = 0,
            authoredViewportHeight = 0
        )

        assertEquals(1080f, placement.scale, 0.001f)
        assertEquals(1620f, placement.offsetX, 0.001f)
        assertEquals(0f, placement.offsetY, 0.001f)
        assertTrue(placement.viewport.right > placement.viewport.left)
    }

    @Test
    fun placementFitsAuthoredCanvasAspectRatioInsideScreenViewport() {
        val placement = calculateSnapshotPlacement(
            bitmapWidth = 768,
            bitmapHeight = 1688,
            screenWidth = 768,
            screenHeight = 1688,
            authoredViewportWidth = 774,
            authoredViewportHeight = 1134
        )

        assertEquals(0f, placement.offsetX, 0.001f)
        assertEquals(281.3954f, placement.offsetY, 0.001f)
        assertEquals(768f, placement.viewport.width, 0.001f)
        assertEquals(1125.2092f, placement.viewport.height, 0.001f)
    }
}
