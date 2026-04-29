package com.subhajit.mulberry.review

interface ReviewPromptStateStore {
    suspend fun get(): ReviewPromptState
    suspend fun updateAndGet(transform: (ReviewPromptState) -> ReviewPromptState): ReviewPromptState
}

