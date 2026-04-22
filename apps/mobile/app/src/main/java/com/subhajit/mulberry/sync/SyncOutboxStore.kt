package com.subhajit.mulberry.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicLong

data class SyncOutboxSummary(
    val pending: Int,
    val inFlight: Int
) {
    val total: Int
        get() = pending + inFlight
}

@Singleton
class SyncOutboxStore @Inject constructor(
    private val dao: SyncOutboxDao,
    private val syncMetadataRepository: SyncMetadataRepository
) {
    private val nextSequence = AtomicLong(0L)

    suspend fun migrateLegacyPendingOperations() {
        val legacyOperations = syncMetadataRepository.metadata.first().pendingOperations
        if (legacyOperations.isEmpty()) return
        legacyOperations.forEach { operation ->
            dao.insert(SyncOutboxEntity.fromDomain(operation, allocateSequenceNumber()))
        }
        syncMetadataRepository.setPendingOperations(emptyList())
    }

    suspend fun enqueue(operation: CanvasSyncOperation) {
        dao.insert(SyncOutboxEntity.fromDomain(operation, allocateSequenceNumber()))
    }

    suspend fun nextBatch(maxOperations: Int, maxPayloadBytes: Int): List<CanvasSyncOperation> {
        val operations = mutableListOf<CanvasSyncOperation>()
        var sizeBytes = 0
        for (entity in dao.pendingCandidates(maxOperations)) {
            val operation = entity.toDomain()
            val operationBytes = operation.toWireJson().encodeToByteArray().size
            if (operations.isNotEmpty() && sizeBytes + operationBytes > maxPayloadBytes) break
            operations += operation
            sizeBytes += operationBytes
        }
        return operations
    }

    suspend fun markInFlight(
        operations: List<CanvasSyncOperation>,
        batchId: String,
        sentAt: Long = System.currentTimeMillis()
    ) {
        if (operations.isEmpty()) return
        dao.markInFlight(operations.map { it.clientOperationId }, batchId, sentAt)
    }

    suspend fun acknowledge(clientOperationIds: List<String>) {
        if (clientOperationIds.isEmpty()) return
        dao.deleteByClientOperationIds(clientOperationIds)
    }

    suspend fun resetInFlightToPending() {
        dao.resetInFlightToPending()
    }

    suspend fun resetStaleInFlightToPending(staleBefore: Long): Int =
        dao.resetStaleInFlightToPending(staleBefore)

    suspend fun hasPendingOperations(): Boolean = dao.count() > 0

    suspend fun summary(): SyncOutboxSummary =
        SyncOutboxSummary(
            pending = dao.countByStatus(SyncOutboxStatus.PENDING),
            inFlight = dao.countByStatus(SyncOutboxStatus.IN_FLIGHT)
        )

    suspend fun clear() {
        dao.clear()
        nextSequence.set(0L)
    }

    private suspend fun allocateSequenceNumber(): Long {
        while (true) {
            val current = nextSequence.get()
            val initialized = if (current == 0L) dao.maxSequenceNumber() else current
            if (nextSequence.compareAndSet(current, initialized + 1L)) {
                return initialized + 1L
            }
        }
    }
}
