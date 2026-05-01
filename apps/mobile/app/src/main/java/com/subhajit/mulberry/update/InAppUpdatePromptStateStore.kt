package com.subhajit.mulberry.update

interface InAppUpdatePromptStateStore {
    suspend fun get(): InAppUpdatePromptState
    suspend fun updateAndGet(
        transform: (InAppUpdatePromptState) -> InAppUpdatePromptState
    ): InAppUpdatePromptState
}

