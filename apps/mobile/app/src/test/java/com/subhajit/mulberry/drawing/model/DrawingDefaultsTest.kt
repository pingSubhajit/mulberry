package com.subhajit.mulberry.drawing.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawingDefaultsTest {
    @Test
    fun defaultColorMatchesFirstPaletteSwatch() {
        assertEquals(DrawingDefaults.palette.first(), DrawingDefaults.DEFAULT_COLOR_ARGB)
    }
}
