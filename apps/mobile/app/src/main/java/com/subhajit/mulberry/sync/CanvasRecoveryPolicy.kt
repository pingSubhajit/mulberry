package com.subhajit.mulberry.sync

import com.subhajit.mulberry.drawing.model.DrawingOperationType
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

data class CanvasRecoveryInput(
    val lastAppliedRevision: Long,
    val latestRevision: Long,
    val missedOperationCount: Int,
    val idleDurationMs: Long,
    val hasPendingLocalOperations: Boolean,
    val reason: CanvasRecoveryReason
) {
    val revisionGap: Long
        get() = max(0L, latestRevision - lastAppliedRevision)
}

enum class CanvasRecoveryReason {
    READY,
    RESYNC_REQUIRED,
    REVISION_GAP,
    EMPTY_TAIL_GAP
}

@Singleton
class CanvasRecoveryPolicy @Inject constructor() {
    fun shouldUseSnapshot(input: CanvasRecoveryInput): Boolean {
        if (input.hasPendingLocalOperations) return false
        return input.reason == CanvasRecoveryReason.RESYNC_REQUIRED ||
            input.reason == CanvasRecoveryReason.REVISION_GAP ||
            input.reason == CanvasRecoveryReason.EMPTY_TAIL_GAP ||
            input.idleDurationMs >= LONG_IDLE_MS ||
            input.missedOperationCount > MAX_TAIL_OPERATIONS ||
            input.revisionGap > MAX_REVISION_GAP
    }

    fun compactTailOperations(operations: List<ServerCanvasOperation>): List<ServerCanvasOperation> {
        val sorted = operations.sortedBy { it.serverRevision }
        val lastClearIndex = sorted.indexOfLast { it.type == DrawingOperationType.CLEAR_CANVAS }
        return if (lastClearIndex >= 0) {
            sorted.drop(lastClearIndex)
        } else {
            sorted
        }
    }

    companion object {
        const val MAX_TAIL_OPERATIONS = 150
        const val MAX_REVISION_GAP = 150L
        const val LONG_IDLE_MS = 30_000L
    }
}
