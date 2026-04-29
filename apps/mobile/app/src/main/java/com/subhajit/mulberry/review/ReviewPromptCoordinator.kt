package com.subhajit.mulberry.review

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReviewPromptCoordinator @Inject constructor(
    private val store: ReviewPromptStateStore
) {
    suspend fun reserveIfEligible(
        nowMs: Long,
        currentStreakDays: Int,
        isManual: Boolean
    ): Boolean {
        if (isManual) return true
        if (currentStreakDays < 3) return false

        val updated = store.updateAndGet { current ->
            val milestone3Reached = current.milestone3Reached || currentStreakDays >= 3
            val nextEligibleAtMs = current.nextEligibleAtMs ?: 0L
            if (nowMs < nextEligibleAtMs) {
                current.copy(milestone3Reached = milestone3Reached)
            } else {
                val nextAttemptCount = current.attemptCount + 1
                val delayMs = ReviewPromptBackoffPolicy.delayMsForAttempt(nextAttemptCount)
                current.copy(
                    milestone3Reached = milestone3Reached,
                    attemptCount = nextAttemptCount,
                    lastAttemptAtMs = nowMs,
                    nextEligibleAtMs = nowMs + delayMs
                )
            }
        }

        val reservedAtMs = updated.lastAttemptAtMs
        return reservedAtMs != null && reservedAtMs == nowMs
    }
}

