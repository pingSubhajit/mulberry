package com.subhajit.elaris.drawing.engine

import com.subhajit.elaris.drawing.model.Stroke
import com.subhajit.elaris.drawing.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StrokeHitTesterTest {
    private val hitTester = StrokeHitTester()

    @Test
    fun `finds the most recent matching stroke`() {
        val first = Stroke(
            id = "first",
            colorArgb = 0xFF000000L,
            width = 10f,
            points = listOf(StrokePoint(0f, 0f), StrokePoint(100f, 0f)),
            createdAt = 1L
        )
        val second = Stroke(
            id = "second",
            colorArgb = 0xFFFF6F2CL,
            width = 10f,
            points = listOf(StrokePoint(0f, 2f), StrokePoint(100f, 2f)),
            createdAt = 2L
        )

        val hit = hitTester.findStrokeHit(
            strokes = listOf(first, second),
            point = StrokePoint(50f, 1f)
        )

        assertEquals("second", hit?.id)
    }

    @Test
    fun `returns null when tap misses every stroke`() {
        val stroke = Stroke(
            id = "one",
            colorArgb = 0xFF000000L,
            width = 8f,
            points = listOf(StrokePoint(0f, 0f), StrokePoint(20f, 0f)),
            createdAt = 1L
        )

        val hit = hitTester.findStrokeHit(
            strokes = listOf(stroke),
            point = StrokePoint(200f, 200f)
        )

        assertNull(hit)
    }
}
