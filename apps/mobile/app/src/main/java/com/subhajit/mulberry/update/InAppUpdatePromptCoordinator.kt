package com.subhajit.mulberry.update

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InAppUpdatePromptCoordinator @Inject constructor(
    private val store: InAppUpdatePromptStateStore
) {
    fun cooldownMs(): Long = DEFAULT_COOLDOWN_MS

    suspend fun shouldPromptForUpdate(
        nowMs: Long,
        availableVersionCode: Int,
        isManual: Boolean
    ): Boolean {
        if (isManual) return true
        val state = store.get()
        val declinedAtMs = state.declinedAtMs ?: return true
        val declinedVersionCode = state.declinedVersionCode ?: return true
        if (declinedVersionCode != availableVersionCode) return true
        return nowMs >= declinedAtMs + DEFAULT_COOLDOWN_MS
    }

    suspend fun recordDeclined(
        nowMs: Long,
        availableVersionCode: Int
    ) {
        store.updateAndGet { current ->
            current.copy(
                declinedVersionCode = availableVersionCode,
                declinedAtMs = nowMs
            )
        }
    }

    private companion object {
        private const val DEFAULT_COOLDOWN_MS = 24L * 60L * 60L * 1000L
    }
}

