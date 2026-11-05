package com.subhajit.mulberry.streak

import com.subhajit.mulberry.network.StreakResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstDrawingDecisionTest {
    private val today = "2026-11-05"

    private fun streak(day: String = today, hasActivityToday: Boolean) = StreakResponse(
        today = day,
        currentStreakDays = 3,
        previousStreakDays = 0,
        hasActivityToday = hasActivityToday,
        lastActivityDay = "2026-11-04",
        week = emptyList()
    )

    @Test
    fun aDrawingThatCannotGrowTheStreakCelebratesNow() {
        assertEquals(
            FirstDrawingDecision.CELEBRATE,
            decideFirstDrawingCelebration(paired = false, streak = null, today = today)
        )
        assertEquals(
            FirstDrawingDecision.CELEBRATE,
            decideFirstDrawingCelebration(paired = true, streak = streak(hasActivityToday = true), today = today)
        )
    }

    @Test
    fun theDayFirstDrawingIsLeftToTheStreakLevelUp() {
        assertEquals(
            FirstDrawingDecision.LEAVE_TO_STREAK,
            decideFirstDrawingCelebration(paired = true, streak = streak(hasActivityToday = false), today = today)
        )
    }

    @Test
    fun aMissingOrStaleStreakIsUnknown() {
        assertEquals(
            FirstDrawingDecision.UNKNOWN,
            decideFirstDrawingCelebration(paired = true, streak = null, today = today)
        )
        assertEquals(
            FirstDrawingDecision.UNKNOWN,
            decideFirstDrawingCelebration(
                paired = true,
                streak = streak(day = "2026-11-04", hasActivityToday = true),
                today = today
            )
        )
    }

    @Test
    fun onlyAnAccountTheServerSaysHasNeverDrawnAwaitsItsFirstDrawing() {
        assertTrue(awaitsFirstDrawing(userId = "u1", serverHasDrawn = false, drawnHereUserIds = emptySet()))
        assertFalse(awaitsFirstDrawing(userId = "u1", serverHasDrawn = true, drawnHereUserIds = emptySet()))
        // A server too old to say counts as drawn.
        assertFalse(awaitsFirstDrawing(userId = "u1", serverHasDrawn = null, drawnHereUserIds = emptySet()))
        assertFalse(awaitsFirstDrawing(userId = null, serverHasDrawn = false, drawnHereUserIds = emptySet()))
        // Drawn here before the server caught up; another account's drawing doesn't count.
        assertFalse(awaitsFirstDrawing(userId = "u1", serverHasDrawn = false, drawnHereUserIds = setOf("u1")))
        assertTrue(awaitsFirstDrawing(userId = "u1", serverHasDrawn = false, drawnHereUserIds = setOf("u2")))
    }
}
