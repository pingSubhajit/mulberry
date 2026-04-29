package com.subhajit.mulberry.review

data class ReviewPromptState(
    val milestone3Reached: Boolean = false,
    val attemptCount: Int = 0,
    val lastAttemptAtMs: Long? = null,
    val nextEligibleAtMs: Long? = null
)

