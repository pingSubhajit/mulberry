package com.subhajit.mulberry.core.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeTextTest {
    @Test
    fun layoutsChangeShapeFromOneAndAHalfTimes() {
        assertFalse(isLargeTextScale(1f))
        assertFalse(isLargeTextScale(1.3f))
        assertFalse(isLargeTextScale(1.49f))
        assertTrue(isLargeTextScale(1.5f))
        assertTrue(isLargeTextScale(2f))
    }
}
