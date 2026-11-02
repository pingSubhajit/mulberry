package com.subhajit.mulberry.streak

import com.subhajit.mulberry.network.StreakResponse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreakBreakTest {
    private fun streak(
        current: Int = 0,
        previous: Int = 4,
        last: String? = "2026-11-01",
        seen: Boolean? = false
    ) = StreakResponse(
        today = "2026-11-03",
        currentStreakDays = current,
        previousStreakDays = previous,
        hasActivityToday = false,
        lastActivityDay = last,
        week = emptyList(),
        lostStreakSeen = seen
    )

    @Test
    fun aStreakLostYesterdayBreaksUntilWatched() {
        assertTrue(streak().isLostRecently())
        assertTrue(streak().breakPending(seenDayHere = null))
        // Watched on another device, or on this one.
        assertFalse(streak(seen = true).breakPending(seenDayHere = null))
        assertFalse(streak().breakPending(seenDayHere = "2026-11-01"))
        // An earlier loss watched here doesn't count for this one.
        assertTrue(streak().breakPending(seenDayHere = "2026-10-20"))
        // A server that doesn't track it: each device breaks it once.
        assertTrue(streak(seen = null).breakPending(seenDayHere = null))
    }

    @Test
    fun onlyAFreshLossBreaks() {
        assertFalse(streak(last = "2026-10-31").breakPending(seenDayHere = null))
        assertFalse(streak(current = 3, last = "2026-11-02").breakPending(seenDayHere = null))
        assertFalse(streak(previous = 0).breakPending(seenDayHere = null))
        assertFalse(streak(last = null).breakPending(seenDayHere = null))
    }
}
