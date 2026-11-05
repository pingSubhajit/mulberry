package com.subhajit.mulberry.drawing.engine

import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `a drag segment erases every stroke it crosses between samples`() {
        val left = verticalStroke(id = "left", x = 100f)
        val right = verticalStroke(id = "right", x = 300f)
        val from = StrokePoint(0f, 50f)
        val to = StrokePoint(400f, 50f)

        // Neither touch sample lands on a stroke; only the path between them does.
        assertNull(hitTester.findStrokeHit(listOf(left, right), from))
        assertNull(hitTester.findStrokeHit(listOf(left, right), to))

        val hits = hitTester.findStrokesAlongSegment(listOf(left, right), from, to)

        assertEquals(listOf("right", "left"), hits.map { it.id })
    }

    @Test
    fun `a drag segment catches a dot and strokes it passes near`() {
        val dot = Stroke(
            id = "dot",
            colorArgb = 0xFF000000L,
            width = 10f,
            points = listOf(StrokePoint(200f, 60f)),
            createdAt = 1L
        )
        val near = Stroke(
            id = "near",
            colorArgb = 0xFF000000L,
            width = 10f,
            points = listOf(StrokePoint(0f, 70f), StrokePoint(400f, 70f)),
            createdAt = 2L
        )

        val hits = hitTester.findStrokesAlongSegment(
            strokes = listOf(dot, near),
            from = StrokePoint(100f, 50f),
            to = StrokePoint(300f, 50f)
        )

        assertEquals(setOf("dot", "near"), hits.map { it.id }.toSet())
    }

    @Test
    fun `a drag segment that stays clear erases nothing`() {
        val hits = hitTester.findStrokesAlongSegment(
            strokes = listOf(verticalStroke(id = "one", x = 100f)),
            from = StrokePoint(0f, 200f),
            to = StrokePoint(400f, 200f)
        )

        assertTrue(hits.isEmpty())
    }

    private fun verticalStroke(id: String, x: Float) = Stroke(
        id = id,
        colorArgb = 0xFF000000L,
        width = 10f,
        points = listOf(StrokePoint(x, 0f), StrokePoint(x, 100f)),
        createdAt = 1L
    )
}
