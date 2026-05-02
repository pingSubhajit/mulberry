package com.subhajit.mulberry.stickers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerRenderSizingTest {

    @Test
    fun `keeps long edge at max size for landscape stickers`() {
        val size = resolveStickerRenderSizePx(
            maxSizePx = 200f,
            bitmapWidthPx = 400,
            bitmapHeightPx = 200
        )

        assertEquals(200f, size.widthPx, 0.0001f)
        assertEquals(100f, size.heightPx, 0.0001f)
    }

    @Test
    fun `keeps long edge at max size for portrait stickers`() {
        val size = resolveStickerRenderSizePx(
            maxSizePx = 200f,
            bitmapWidthPx = 200,
            bitmapHeightPx = 400
        )

        assertEquals(100f, size.widthPx, 0.0001f)
        assertEquals(200f, size.heightPx, 0.0001f)
    }

    @Test
    fun `never returns zero or negative sizes`() {
        val size = resolveStickerRenderSizePx(
            maxSizePx = 0f,
            bitmapWidthPx = 0,
            bitmapHeightPx = 0
        )
        assertTrue(size.widthPx > 0f)
        assertTrue(size.heightPx > 0f)
    }
}

