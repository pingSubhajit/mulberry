package com.subhajit.mulberry.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderSizingTest {

    @Test
    fun wallpaperRenderSurfaceSizeClampsRequestedBoundsOnLowRamDevices() {
        val size = resolveWallpaperRenderSurfaceSize(
            displayWidth = 720,
            displayHeight = 1600,
            desiredWidth = 1440,
            desiredHeight = 2400,
            profile = DeviceRenderProfile(
                isLowRamDevice = true,
                memoryClassMb = 128,
                maxCanvasCachePixels = 3_000_000,
                maxWallpaperPixels = 3_200_000
            )
        )

        assertEquals(792, size.width)
        assertEquals(1600, size.height)
    }

    @Test
    fun wallpaperRenderSurfaceSizeKeepsDisplaySizedFloorAfterPixelBudgetClamp() {
        val size = resolveWallpaperRenderSurfaceSize(
            displayWidth = 1080,
            displayHeight = 2400,
            desiredWidth = 1800,
            desiredHeight = 3000,
            profile = DeviceRenderProfile(
                isLowRamDevice = true,
                memoryClassMb = 192,
                maxCanvasCachePixels = 3_000_000,
                maxWallpaperPixels = 3_000_000
            )
        )

        assertTrue(size.width >= 1080)
        assertTrue(size.height >= 2400)
        assertTrue(size.width * size.height <= 3_000_000)
    }

    @Test
    fun calculateInSampleSizePrefersBitmapCloseToTargetWithoutUndersizingBothAxes() {
        val sampleSize = calculateInSampleSize(
            sourceWidth = 4000,
            sourceHeight = 3000,
            targetWidth = 1000,
            targetHeight = 1000
        )

        assertEquals(3, sampleSize)
    }

    @Test
    fun canUseCommittedCanvasCacheRejectsOversizedCanvasForProfileBudget() {
        val canUseCache = canUseCommittedCanvasCache(
            width = 2000,
            height = 2000,
            profile = DeviceRenderProfile(
                isLowRamDevice = true,
                memoryClassMb = 128,
                maxCanvasCachePixels = 3_000_000,
                maxWallpaperPixels = 3_200_000
            )
        )

        assertEquals(false, canUseCache)
    }
}
