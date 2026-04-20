package com.subhajit.elaris.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class ElarisWallpaperServiceRectTest {
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
