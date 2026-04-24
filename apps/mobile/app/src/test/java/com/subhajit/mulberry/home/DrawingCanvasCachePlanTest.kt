package com.subhajit.mulberry.home

import androidx.compose.ui.unit.IntSize
import com.subhajit.mulberry.drawing.render.CanvasStrokeRenderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrawingCanvasCachePlanTest {

    @Test
    fun `returns null when no bitmap cache exists`() {
        val overlayStartIndex = resolveCommittedCacheOverlayStartIndex(
            cacheHasBitmap = false,
            cacheStrokeIds = listOf("a"),
            cacheCanvasSize = IntSize(100, 200),
            cacheStrokeRenderMode = CanvasStrokeRenderMode.Hybrid,
            currentStrokeIds = listOf("a"),
            currentCanvasSize = IntSize(100, 200),
            currentStrokeRenderMode = CanvasStrokeRenderMode.Hybrid
        )

        assertNull(overlayStartIndex)
    }

    @Test
    fun `returns cached stroke count when current strokes extend cached bitmap`() {
        val overlayStartIndex = resolveCommittedCacheOverlayStartIndex(
            cacheHasBitmap = true,
            cacheStrokeIds = listOf("a"),
            cacheCanvasSize = IntSize(100, 200),
            cacheStrokeRenderMode = CanvasStrokeRenderMode.Hybrid,
            currentStrokeIds = listOf("a", "b"),
            currentCanvasSize = IntSize(100, 200),
            currentStrokeRenderMode = CanvasStrokeRenderMode.Hybrid
        )

        assertEquals(1, overlayStartIndex)
    }

    @Test
    fun `returns null when cached bitmap no longer matches current stroke sequence`() {
        val overlayStartIndex = resolveCommittedCacheOverlayStartIndex(
            cacheHasBitmap = true,
            cacheStrokeIds = listOf("a", "b"),
            cacheCanvasSize = IntSize(100, 200),
            cacheStrokeRenderMode = CanvasStrokeRenderMode.Hybrid,
            currentStrokeIds = listOf("a"),
            currentCanvasSize = IntSize(100, 200),
            currentStrokeRenderMode = CanvasStrokeRenderMode.Hybrid
        )

        assertNull(overlayStartIndex)
    }
}
