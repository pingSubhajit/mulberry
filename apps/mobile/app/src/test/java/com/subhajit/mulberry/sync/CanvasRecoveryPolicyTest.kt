package com.subhajit.mulberry.sync

import com.subhajit.mulberry.drawing.model.DrawingOperationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasRecoveryPolicyTest {
    private val policy = CanvasRecoveryPolicy()

    @Test
    fun choosesOperationReplayForSmallReadyGap() {
        val input = CanvasRecoveryInput(
            lastAppliedRevision = 10,
            latestRevision = 20,
            missedOperationCount = 10,
            idleDurationMs = 1_000,
            hasPendingLocalOperations = false,
            reason = CanvasRecoveryReason.READY
        )

        assertFalse(policy.shouldUseSnapshot(input))
    }

    @Test
    fun choosesSnapshotForLargeGapLongIdleAndResyncRequired() {
        assertTrue(
            policy.shouldUseSnapshot(
                input(latestRevision = 10 + CanvasRecoveryPolicy.MAX_REVISION_GAP + 1)
            )
        )
        assertTrue(
            policy.shouldUseSnapshot(
                input(idleDurationMs = CanvasRecoveryPolicy.LONG_IDLE_MS)
            )
        )
        assertTrue(
            policy.shouldUseSnapshot(
                input(reason = CanvasRecoveryReason.RESYNC_REQUIRED)
            )
        )
    }

    @Test
    fun avoidsSnapshotReplacementWhenLocalOperationsArePending() {
        val input = input(
            latestRevision = 1_000,
            missedOperationCount = 1_000,
            idleDurationMs = CanvasRecoveryPolicy.LONG_IDLE_MS,
            hasPendingLocalOperations = true,
            reason = CanvasRecoveryReason.RESYNC_REQUIRED
        )

        assertFalse(policy.shouldUseSnapshot(input))
    }

    @Test
    fun compactTailOperationsDropsEverythingBeforeLastClear() {
        val compacted = policy.compactTailOperations(
            listOf(
                operation(1, DrawingOperationType.ADD_STROKE),
                operation(2, DrawingOperationType.CLEAR_CANVAS),
                operation(3, DrawingOperationType.ADD_STROKE),
                operation(4, DrawingOperationType.CLEAR_CANVAS),
                operation(5, DrawingOperationType.ADD_STROKE)
            )
        )

        assertEquals(listOf(4L, 5L), compacted.map { it.serverRevision })
    }

    private fun input(
        latestRevision: Long = 20,
        missedOperationCount: Int = 10,
        idleDurationMs: Long = 1_000,
        hasPendingLocalOperations: Boolean = false,
        reason: CanvasRecoveryReason = CanvasRecoveryReason.READY
    ): CanvasRecoveryInput = CanvasRecoveryInput(
        lastAppliedRevision = 10,
        latestRevision = latestRevision,
        missedOperationCount = missedOperationCount,
        idleDurationMs = idleDurationMs,
        hasPendingLocalOperations = hasPendingLocalOperations,
        reason = reason
    )

    private fun operation(
        revision: Long,
        type: DrawingOperationType
    ): ServerCanvasOperation = ServerCanvasOperation(
        clientOperationId = "op-$revision",
        actorUserId = "user-1",
        pairSessionId = "pair-1",
        type = type,
        strokeId = null,
        payload = when (type) {
            DrawingOperationType.CLEAR_CANVAS -> SyncOperationPayload.ClearCanvas
            DrawingOperationType.DELETE_STROKE -> SyncOperationPayload.DeleteStroke
            DrawingOperationType.FINISH_STROKE -> SyncOperationPayload.FinishStroke
            DrawingOperationType.ADD_STROKE -> SyncOperationPayload.ClearCanvas
            DrawingOperationType.APPEND_POINTS -> SyncOperationPayload.ClearCanvas
        },
        clientCreatedAt = "2026-01-01T00:00:00.000Z",
        serverRevision = revision,
        createdAt = "2026-01-01T00:00:00.000Z"
    )
}
