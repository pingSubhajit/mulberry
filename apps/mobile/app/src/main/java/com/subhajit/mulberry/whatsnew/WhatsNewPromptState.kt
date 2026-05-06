package com.subhajit.mulberry.whatsnew

data class WhatsNewPromptState(
    val lastSeenVersionName: String? = null,
    val pendingVersionName: String? = null,
    val nextRetryAtMs: Long? = null,
    val retryAttempt: Int = 0
)

