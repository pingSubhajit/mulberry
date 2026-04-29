package com.subhajit.mulberry.review

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class ReviewPromptCoordinatorTest {

    @Test
    fun `manual mode always launches and never mutates state`() = runTest {
        val store = FakeStore()
        val coordinator = ReviewPromptCoordinator(store)

        val shouldLaunch = coordinator.reserveIfEligible(
            nowMs = 1234L,
            currentStreakDays = 0,
            isManual = true
        )

        assertTrue(shouldLaunch)
        assertEquals(ReviewPromptState(), store.get())
    }

    @Test
    fun `does nothing when streak below 3`() = runTest {
        val store = FakeStore()
        val coordinator = ReviewPromptCoordinator(store)

        val shouldLaunch = coordinator.reserveIfEligible(
            nowMs = 1000L,
            currentStreakDays = 2,
            isManual = false
        )

        assertFalse(shouldLaunch)
        assertEquals(ReviewPromptState(), store.get())
    }

    @Test
    fun `first time streak reaches 3 reserves attempt and sets milestone`() = runTest {
        val store = FakeStore()
        val coordinator = ReviewPromptCoordinator(store)

        val now = 10_000L
        val shouldLaunch = coordinator.reserveIfEligible(
            nowMs = now,
            currentStreakDays = 3,
            isManual = false
        )

        assertTrue(shouldLaunch)
        val state = store.get()
        assertTrue(state.milestone3Reached)
        assertEquals(1, state.attemptCount)
        assertEquals(now, state.lastAttemptAtMs)
        assertEquals(now + ReviewPromptBackoffPolicy.delayMsForAttempt(1), state.nextEligibleAtMs)
    }

    @Test
    fun `honors next eligible time`() = runTest {
        val store = FakeStore(
            ReviewPromptState(
                milestone3Reached = true,
                attemptCount = 1,
                lastAttemptAtMs = 1_000L,
                nextEligibleAtMs = 50_000L
            )
        )
        val coordinator = ReviewPromptCoordinator(store)

        val shouldLaunchEarly = coordinator.reserveIfEligible(
            nowMs = 40_000L,
            currentStreakDays = 5,
            isManual = false
        )
        assertFalse(shouldLaunchEarly)

        val shouldLaunchOnTime = coordinator.reserveIfEligible(
            nowMs = 60_000L,
            currentStreakDays = 5,
            isManual = false
        )
        assertTrue(shouldLaunchOnTime)
        assertEquals(2, store.get().attemptCount)
    }

    private class FakeStore(initial: ReviewPromptState = ReviewPromptState()) : ReviewPromptStateStore {
        private val ref = AtomicReference(initial)

        override suspend fun get(): ReviewPromptState = ref.get()

        override suspend fun updateAndGet(
            transform: (ReviewPromptState) -> ReviewPromptState
        ): ReviewPromptState {
            while (true) {
                val current = ref.get()
                val updated = transform(current)
                if (ref.compareAndSet(current, updated)) return updated
            }
        }
    }
}

