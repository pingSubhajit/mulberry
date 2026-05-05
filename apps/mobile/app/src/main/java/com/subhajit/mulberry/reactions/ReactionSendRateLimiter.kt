package com.subhajit.mulberry.reactions

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lightweight client-side cooldown to avoid accidental multi-sends and reduce backend/push load.
 *
 * This is not meant to be tamper-proof; the backend should still enforce an authoritative limit.
 */
@Singleton
class ReactionSendRateLimiter @Inject constructor() {
    private val mutex = Mutex()
    private var lastAcceptedAtMs: Long = 0L

    suspend fun tryAcquire(nowMs: Long = SystemClock.elapsedRealtime()): Boolean = mutex.withLock {
        if (nowMs - lastAcceptedAtMs < MIN_GAP_MS) return false
        lastAcceptedAtMs = nowMs
        true
    }

    companion object {
        const val MIN_GAP_MS: Long = 800L
    }
}

