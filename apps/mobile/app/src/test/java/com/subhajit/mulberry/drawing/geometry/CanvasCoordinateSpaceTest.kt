package com.subhajit.mulberry.drawing.geometry

import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.sync.SyncOperationPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasCoordinateSpaceTest {

    @Test
    fun normalizeAndDenormalizePointRoundTripsAcrossSurface() {
        val normalized = StrokePoint(x = 180f, y = 400f).normalizeToSurface(width = 720, height = 1600)
        val restored = normalized.denormalizeToSurface(width = 720f, height = 1600f)

        assertEquals(0.25f, normalized.x, 0.0001f)
        assertEquals(0.25f, normalized.y, 0.0001f)
        assertEquals(180f, restored.x, 0.0001f)
        assertEquals(400f, restored.y, 0.0001f)
    }

    @Test
    fun denormalizedStrokeKeepsRelativeGeometryAcrossDifferentSurfaces() {
        val stroke = Stroke(
            id = "cat",
            colorArgb = 0xFFFF6A2AL,
            width = 0.01f,
            points = listOf(
                StrokePoint(0.10f, 0.20f),
                StrokePoint(0.40f, 0.80f)
            ),
            createdAt = 1L
        )

        val smallSurface = stroke.denormalizeToSurface(width = 720f, height = 1600f)
        val largeSurface = stroke.denormalizeToSurface(width = 1080f, height = 2400f)

        assertEquals(72f, smallSurface.points.first().x, 0.0001f)
        assertEquals(108f, largeSurface.points.first().x, 0.0001f)
        assertEquals(7.2f, smallSurface.width, 0.0001f)
        assertEquals(10.8f, largeSurface.width, 0.0001f)
    }

    @Test
    fun legacyGeometryDetectionRejectsPixelSpaceStroke() {
        val legacyStroke = Stroke(
            id = "legacy",
            colorArgb = 0xFF000000,
            width = 0.015f,
            points = listOf(StrokePoint(120f, 240f)),
            createdAt = 1L
        )
        val normalizedStroke = Stroke(
            id = "normalized",
            colorArgb = 0xFF000000,
            width = 0.015f,
            points = listOf(StrokePoint(0.2f, 0.4f)),
            createdAt = 1L
        )

        assertTrue(legacyStroke.hasLegacyGeometry())
        assertFalse(normalizedStroke.hasLegacyGeometry())
    }

    @Test
    fun addStrokePayloadWithNormalizedPointAndPixelWidthIsNotTreatedAsLegacy() {
        val payload = SyncOperationPayload.AddStroke(
            id = "mixed-width",
            colorArgb = 0xFF000000,
            width = 28f,
            createdAt = 1L,
            firstPoint = StrokePoint(0.6300572f, 0.01572425f)
        )

        assertFalse(payload.hasLegacyGeometry())
    }
}
