package com.subhajit.mulberry.drawing.render

import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Test

class StrokeTransformTest {
    @Test
    fun transformedScalesWidthAndOffsetsPoints() {
        val stroke = Stroke(
            id = "stroke-1",
            colorArgb = 0xFF101010,
            width = 4f,
            points = listOf(
                StrokePoint(x = 10f, y = 20f),
                StrokePoint(x = 30f, y = 50f)
            ),
            createdAt = 123L
        )

        val transformed = stroke.transformed(scale = 2f, offsetX = 5f, offsetY = 7f)

        assertEquals("stroke-1", transformed.id)
        assertEquals(8f, transformed.width)
        assertEquals(25f, transformed.points[0].x)
        assertEquals(47f, transformed.points[0].y)
        assertEquals(65f, transformed.points[1].x)
        assertEquals(107f, transformed.points[1].y)
    }
}
