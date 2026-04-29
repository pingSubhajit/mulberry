package com.subhajit.mulberry.review

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class ReviewPromptBackoffPolicyTest {
    @Test
    fun `delayMsForAttempt uses expected schedule`() {
        assertEquals(0L, ReviewPromptBackoffPolicy.delayMsForAttempt(0))
        assertEquals(TimeUnit.DAYS.toMillis(14), ReviewPromptBackoffPolicy.delayMsForAttempt(1))
        assertEquals(TimeUnit.DAYS.toMillis(60), ReviewPromptBackoffPolicy.delayMsForAttempt(2))
        assertEquals(TimeUnit.DAYS.toMillis(180), ReviewPromptBackoffPolicy.delayMsForAttempt(3))
        assertEquals(TimeUnit.DAYS.toMillis(365), ReviewPromptBackoffPolicy.delayMsForAttempt(4))
        assertEquals(TimeUnit.DAYS.toMillis(365), ReviewPromptBackoffPolicy.delayMsForAttempt(9))
    }
}

