package com.subhajit.mulberry.drawing.engine

import com.subhajit.mulberry.drawing.model.BrushStyle
import com.subhajit.mulberry.drawing.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeBuilderTest {
    private val strokeBuilder = StrokeBuilder()

    @Test
    fun `start append and finish build a committed stroke`() {
        val stroke = strokeBuilder.startStroke(
            point = StrokePoint(10f, 12f),
            brushStyle = BrushStyle(colorArgb = 0xFFFF6F2CL, width = 8f),
            createdAt = 100L
        )

        val appended = strokeBuilder.appendPoint(stroke, StrokePoint(20f, 24f))
        val finished = strokeBuilder.finishStroke(appended)

        assertNotNull(finished)
        assertEquals(2, finished!!.points.size)
        assertEquals(0xFFFF6F2CL, finished.colorArgb)
        assertEquals(8f, finished.width, 0.001f)
        assertEquals(100L, finished.createdAt)
    }

    @Test
    fun `duplicate nearby points are ignored`() {
        val stroke = strokeBuilder.startStroke(
            point = StrokePoint(10f, 10f),
            brushStyle = BrushStyle(colorArgb = 0xFF000000L, width = 6f)
        )

        val appended = strokeBuilder.appendPoint(stroke, StrokePoint(10.1f, 10.1f))

        assertEquals(1, appended.points.size)
    }

    @Test
    fun `single point stroke still finishes`() {
        val stroke = strokeBuilder.startStroke(
            point = StrokePoint(4f, 4f),
            brushStyle = BrushStyle(colorArgb = 0xFF000000L, width = 6f)
        )

        val finished = strokeBuilder.finishStroke(stroke)

        assertTrue(finished != null && finished.points.size == 1)
    }
}
