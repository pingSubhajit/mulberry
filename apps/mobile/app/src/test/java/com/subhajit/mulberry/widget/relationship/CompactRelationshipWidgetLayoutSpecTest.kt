package com.subhajit.mulberry.widget.relationship

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactRelationshipWidgetLayoutSpecTest {
    @Test
    fun donutTracksTheTextBlockInsteadOfTheTopEdge() {
        val layout = CompactRelationshipWidgetLayoutSpec.calculate(widthDp = 220, heightDp = 220)

        assertEquals(2f, layout.donutToTextGapDp, 0.2f)
        assertTrue(layout.donutTopDp > 32f)
    }

    @Test
    fun compactLayoutFitsSquareishHostsWithoutHorizontalOverflow() {
        val layout = CompactRelationshipWidgetLayoutSpec.calculate(widthDp = 180, heightDp = 180)

        assertTrue(layout.textWidthDp + layout.textEndMarginDp <= 180f)
        assertTrue(layout.donutTopDp >= 8f)
        assertEquals(2f, layout.donutToTextGapDp, 0.2f)
    }
}
