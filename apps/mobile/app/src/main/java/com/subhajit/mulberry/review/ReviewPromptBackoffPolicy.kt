package com.subhajit.mulberry.review

import java.util.concurrent.TimeUnit

object ReviewPromptBackoffPolicy {
    fun delayMsForAttempt(attempt: Int): Long {
        val days = when {
            attempt <= 0 -> 0L
            attempt == 1 -> 14L
            attempt == 2 -> 60L
            attempt == 3 -> 180L
            else -> 365L
        }
        return TimeUnit.DAYS.toMillis(days)
    }
}

