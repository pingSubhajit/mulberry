package com.subhajit.mulberry.home

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasControlTraySpacingTest {
    @Test
    fun spacingMakesLastBaseVisibleToolHalfClipped() {
        val toolSize = 49.dp
        val baseSpacing = 18.dp
        val minSpacing = baseSpacing
        val maxSpacing = 80.dp
        val toolCount = 8

        // A representative "wide phone" content width (already excludes tray padding).
        val availableWidth = 390.dp

        val spacing = computeCanvasTrayToolSpacing(
            availableWidth = availableWidth,
            toolSize = toolSize,
            baseSpacing = baseSpacing,
            minSpacing = minSpacing,
            maxSpacing = maxSpacing,
            toolCount = toolCount
        )

        // With base spacing, 6 full tools fit in 390dp:
        // 6*49 + 5*18 = 384dp, but 7 full tools don't.
        // We therefore expect the 6th tool to be half-clipped after adjusting spacing:
        // 5 full + half of 6th.
        val fullToolsBeforePeek = 5
        val expectedSpacing =
            (availableWidth - (toolSize * (fullToolsBeforePeek + 0.5f))) / fullToolsBeforePeek.toFloat()

        assertEquals(expectedSpacing.value, spacing.value, 0.01f)
        assertTrue("Expected spacing to be within bounds", spacing in minSpacing..maxSpacing)
    }
}
