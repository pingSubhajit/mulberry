package com.subhajit.mulberry.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class MulberryWallpaperServiceRectTest {
    @Test
    fun centerCropSourceRectCropsLandscapeBitmapToPortraitTargetWithoutSquashing() {
        val rect = centerCropSourceRect(
            bitmapWidth = 4096,
            bitmapHeight = 2731,
            targetWidth = 1080,
            targetHeight = 2400
        )

        assertEquals(ScreenSourceRect(1434, 0, 2662, 2731), rect)
    }

    @Test
    fun centerCropSourceRectCropsPortraitBitmapToLandscapeTargetWithoutSquashing() {
        val rect = centerCropSourceRect(
            bitmapWidth = 1080,
            bitmapHeight = 2400,
            targetWidth = 1200,
            targetHeight = 600
        )

        assertEquals(ScreenSourceRect(0, 930, 1080, 1470), rect)
    }

    @Test
    fun centeredScreenSourceRectCropsCenteredViewportFromWiderBitmap() {
        val rect = centeredScreenSourceRect(
            bitmapWidth = 1440,
            bitmapHeight = 2400,
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertEquals(ScreenSourceRect(180, 0, 1260, 2400), rect)
    }

    @Test
    fun centeredScreenSourceRectFallsBackToFullBitmapWhenScreenUnknown() {
        val rect = centeredScreenSourceRect(
            bitmapWidth = 1080,
            bitmapHeight = 2400,
            screenWidth = 0,
            screenHeight = 0
        )

        assertEquals(ScreenSourceRect(0, 0, 1080, 2400), rect)
    }
}
