package com.subhajit.mulberry.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class SyncOutboxStore @Inject constructor(
    private val dao: SyncOutboxDao,
    private val syncMetadataRepository: SyncMetadataRepository
) {
    suspend fun migrateLegacyPendingOperations() {
        val legacyOperations = syncMetadataRepository.metadata.first().pendingOperations
        if (legacyOperations.isEmpty()) return
        legacyOperations.forEach { operation ->
            dao.insert(SyncOutboxEntity.fromDomain(operation))
        }
        syncMetadataRepository.setPendingOperations(emptyList())
    }

    suspend fun enqueue(operation: CanvasSyncOperation) {
        dao.insert(SyncOutboxEntity.fromDomain(operation))
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

    suspend fun hasPendingOperations(): Boolean = dao.count() > 0

    suspend fun clear() {
        dao.clear()
    }
}
