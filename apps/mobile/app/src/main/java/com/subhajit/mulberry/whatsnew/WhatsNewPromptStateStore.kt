package com.subhajit.mulberry.whatsnew

interface WhatsNewPromptStateStore {
    suspend fun get(): WhatsNewPromptState
    suspend fun updateAndGet(transform: (WhatsNewPromptState) -> WhatsNewPromptState): WhatsNewPromptState
}

