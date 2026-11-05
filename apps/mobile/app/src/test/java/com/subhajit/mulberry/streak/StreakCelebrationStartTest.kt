package com.subhajit.mulberry.streak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreakCelebrationStartTest {
    @Test
    fun `a rise since the screen last showed the streak ticks up from the shown count`() {
        assertEquals(3, celebrationStart(currentStreakDays = 4, hasActivityToday = true, lostRecently = false, shownDays = 3))
    }

    @Test
    fun `the first count ever shown is the baseline`() {
        assertNull(celebrationStart(currentStreakDays = 4, hasActivityToday = true, lostRecently = false, shownDays = null))
    }

    @Test
    fun `a streak already seen at today's count keeps still`() {
        assertNull(celebrationStart(currentStreakDays = 4, hasActivityToday = true, lostRecently = false, shownDays = 4))
    }

    @Test
    fun `nothing ticks up before today's drawing or after a loss`() {
        assertNull(celebrationStart(currentStreakDays = 3, hasActivityToday = false, lostRecently = false, shownDays = 2))
        assertNull(celebrationStart(currentStreakDays = 0, hasActivityToday = false, lostRecently = true, shownDays = 3))
    }
}
