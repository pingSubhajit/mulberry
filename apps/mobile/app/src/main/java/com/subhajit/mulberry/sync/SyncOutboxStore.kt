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

interface CanvasSyncOutboxStore {
    suspend fun migrateLegacyPendingOperations()
    suspend fun enqueue(operation: CanvasSyncOperation)
    suspend fun nextBatch(maxOperations: Int, maxPayloadBytes: Int): List<CanvasSyncOperation>
    suspend fun markInFlight(
        operations: List<CanvasSyncOperation>,
        batchId: String,
        sentAt: Long = System.currentTimeMillis()
    )
    suspend fun acknowledge(clientOperationIds: List<String>)
    suspend fun resetInFlightToPending()
    suspend fun resetStaleInFlightToPending(staleBefore: Long): Int
    suspend fun hasPendingOperations(): Boolean
    suspend fun summary(): SyncOutboxSummary
    suspend fun clear()
}

@Singleton
class SyncOutboxStore @Inject constructor(
    private val dao: SyncOutboxDao,
    private val syncMetadataRepository: SyncMetadataRepository
) : CanvasSyncOutboxStore {
    private val nextSequence = AtomicLong(0L)

    override suspend fun migrateLegacyPendingOperations() {
        val legacyOperations = syncMetadataRepository.metadata.first().pendingOperations
        if (legacyOperations.isEmpty()) return
        legacyOperations.forEach { operation ->
            dao.insert(SyncOutboxEntity.fromDomain(operation, allocateSequenceNumber()))
        }
        syncMetadataRepository.setPendingOperations(emptyList())
    }

    override suspend fun enqueue(operation: CanvasSyncOperation) {
        dao.insert(SyncOutboxEntity.fromDomain(operation, allocateSequenceNumber()))
    }

    override suspend fun nextBatch(
        maxOperations: Int,
        maxPayloadBytes: Int
    ): List<CanvasSyncOperation> {
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

    override suspend fun markInFlight(
        operations: List<CanvasSyncOperation>,
        batchId: String,
        sentAt: Long
    ) {
        if (operations.isEmpty()) return
        dao.markInFlight(operations.map { it.clientOperationId }, batchId, sentAt)
    }

    override suspend fun acknowledge(clientOperationIds: List<String>) {
        if (clientOperationIds.isEmpty()) return
        dao.deleteByClientOperationIds(clientOperationIds)
    }

    override suspend fun resetInFlightToPending() {
        dao.resetInFlightToPending()
    }

    override suspend fun resetStaleInFlightToPending(staleBefore: Long): Int =
        dao.resetStaleInFlightToPending(staleBefore)

    override suspend fun hasPendingOperations(): Boolean = dao.count() > 0

    override suspend fun summary(): SyncOutboxSummary =
        SyncOutboxSummary(
            pending = dao.countByStatus(SyncOutboxStatus.PENDING),
            inFlight = dao.countByStatus(SyncOutboxStatus.IN_FLIGHT)
        )

    override suspend fun clear() {
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
