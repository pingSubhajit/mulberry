package com.subhajit.mulberry.core.ui

import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HapticsTest {
    private val nougat = 24
    private val red = 30
    private val upsideDownCake = 34

    @Test
    fun selectionsTickAsSegmentsWhereTheyCanAndAsAClockBelow() {
        assertEquals(HapticFeedbackConstants.SEGMENT_TICK, hapticFeedbackConstant(MulberryHaptic.Selection, upsideDownCake))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, hapticFeedbackConstant(MulberryHaptic.Selection, upsideDownCake - 1))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, hapticFeedbackConstant(MulberryHaptic.Selection, nougat))
        assertEquals(HapticFeedbackConstants.SEGMENT_TICK, hapticFeedbackConstant(MulberryHaptic.RotationSnap, upsideDownCake))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, hapticFeedbackConstant(MulberryHaptic.RotationSnap, nougat))
    }

    @Test
    fun successConfirmsFromAndroid11() {
        assertEquals(HapticFeedbackConstants.CONFIRM, hapticFeedbackConstant(MulberryHaptic.Success, red))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, hapticFeedbackConstant(MulberryHaptic.Success, red - 1))
    }

    @Test
    fun rejectFallsBackToALongPress() {
        assertEquals(HapticFeedbackConstants.REJECT, hapticFeedbackConstant(MulberryHaptic.Reject, red))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, hapticFeedbackConstant(MulberryHaptic.Reject, nougat))
    }

    @Test
    fun impactsUseTheSameConstantOnEveryRelease() {
        listOf(nougat, red, upsideDownCake).forEach { sdk ->
            assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, hapticFeedbackConstant(MulberryHaptic.LightImpact, sdk))
            assertEquals(HapticFeedbackConstants.LONG_PRESS, hapticFeedbackConstant(MulberryHaptic.LongPress, sdk))
            assertEquals(HapticFeedbackConstants.LONG_PRESS, hapticFeedbackConstant(MulberryHaptic.ReactionArrived, sdk))
        }
    }

    @Test
    fun predefinedVibrationsNeedAndroid10() {
        MulberryHaptic.entries.forEach { haptic ->
            assertNull(predefinedVibrationEffect(haptic, sdkInt = 28))
        }
    }
}
