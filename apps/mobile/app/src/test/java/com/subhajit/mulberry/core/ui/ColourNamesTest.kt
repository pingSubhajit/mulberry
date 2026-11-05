package com.subhajit.mulberry.core.ui

import com.subhajit.mulberry.drawing.model.DrawingDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColourNamesTest {
    @Test
    fun paletteColoursUseTheirOwnNames() {
        assertEquals("Mulberry red", ColourNames.name(0xFFB31329L))
        assertEquals("Lemon yellow", ColourNames.name(0xFFFFE66DL))
        assertEquals("Off-white", ColourNames.name(0xFFF7F4EFL))
        assertEquals("Green", ColourNames.name(0xFF00A53CL))
    }

    @Test
    fun everyPaletteColourHasAName() {
        DrawingDefaults.palette.forEach { argb ->
            val name = ColourNames.name(argb)
            assertTrue("${argb.toString(16)} -> $name", name.isNotBlank())
        }
        assertEquals(DrawingDefaults.palette.size, DrawingDefaults.palette.map(ColourNames::name).toSet().size)
    }

    @Test
    fun alphaDoesNotChangeTheName() {
        assertEquals("Mulberry red", ColourNames.name(0x80B31329L))
        assertEquals("Mulberry red", ColourNames.name(0xFFB31329L.toInt()))
    }

    @Test
    fun otherColoursAreDescribedByHueAndLightness() {
        assertEquals("Red", ColourNames.name(0xFFFF0000L))
        assertEquals("Blue", ColourNames.name(0xFF0066FFL))
        assertEquals("Indigo", ColourNames.name(0xFF0000FFL))
        assertEquals("Green", ColourNames.name(0xFF00FF00L))
        assertEquals("Yellow", ColourNames.name(0xFFFFFF00L))
        assertEquals("Cyan", ColourNames.name(0xFF00FFFFL))
        assertEquals("Magenta", ColourNames.name(0xFFFF00FFL))
        assertEquals("Dark red", ColourNames.name(0xFF660000L))
        assertEquals("Light blue", ColourNames.name(0xFFB0C4FFL))
    }

    @Test
    fun greysAndExtremesHaveTheirOwnNames() {
        assertEquals("Black", ColourNames.name(0xFF000000L))
        assertEquals("Black", ColourNames.name(0xFF202020L))
        assertEquals("White", ColourNames.name(0xFFFFFFFFL))
        assertEquals("Light grey", ColourNames.name(0xFFC0C0C0L))
        assertEquals("Grey", ColourNames.name(0xFF808080L))
        assertEquals("Dark grey", ColourNames.name(0xFF404040L))
    }
}
