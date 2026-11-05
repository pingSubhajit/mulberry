package com.subhajit.mulberry.home

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementRotationTest {
    private fun degrees(value: Double): Float = (value * PI / 180).toFloat()

    @Test
    fun aTwistWithinThreeDegreesOfAQuarterTurnSettlesOnIt() {
        assertEquals(0f, ElementRotation.settle(degrees(2.5)), 1e-6f)
        assertEquals(degrees(90.0), ElementRotation.settle(degrees(88.0)), 1e-6f)
        assertEquals(degrees(-180.0), ElementRotation.settle(degrees(-182.0)), 1e-6f)
        assertEquals(degrees(270.0), ElementRotation.settle(degrees(272.9)), 1e-6f)
    }

    @Test
    fun aTwistFurtherAwayIsLeftAlone() {
        assertNull(ElementRotation.quarterTurnNear(degrees(5.0)))
        assertEquals(degrees(45.0), ElementRotation.settle(degrees(45.0)), 1e-6f)
    }

    @Test
    fun onlyExactQuarterTurnsAreSquare() {
        assertTrue(ElementRotation.isSquare(0f))
        assertTrue(ElementRotation.isSquare(degrees(-90.0)))
        assertFalse(ElementRotation.isSquare(degrees(1.0)))
    }

    @Test
    fun theSliderSettlesOnLevel() {
        assertEquals(0f, ElementRotation.settleSliderDegrees(2.9f))
        assertEquals(0f, ElementRotation.settleSliderDegrees(-2.9f))
        assertEquals(3f, ElementRotation.settleSliderDegrees(3f))
        assertEquals(-120f, ElementRotation.settleSliderDegrees(-120f))
    }
}
