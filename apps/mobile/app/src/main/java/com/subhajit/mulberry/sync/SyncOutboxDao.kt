package com.subhajit.mulberry.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncOutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: SyncOutboxEntity)

    @Query(
        """
        SELECT *
        FROM sync_outbox
        WHERE status = 'PENDING'
        ORDER BY createdAt ASC
        LIMIT :limit
        """
    )
    suspend fun pendingCandidates(limit: Int): List<SyncOutboxEntity>

    @Query(
        """
        UPDATE sync_outbox
        SET status = 'IN_FLIGHT',
            batchId = :batchId,
            lastSentAt = :sentAt,
            attemptCount = attemptCount + 1
        WHERE clientOperationId IN (:clientOperationIds)
        """
    )
    suspend fun markInFlight(
        clientOperationIds: List<String>,
        batchId: String,
        sentAt: Long
    )

    @Query(
        """
        UPDATE sync_outbox
        SET status = 'PENDING',
            batchId = NULL,
            lastSentAt = NULL
        WHERE status = 'IN_FLIGHT'
        """
    )
    suspend fun resetInFlightToPending()

    @Query("DELETE FROM sync_outbox WHERE clientOperationId IN (:clientOperationIds)")
    suspend fun deleteByClientOperationIds(clientOperationIds: List<String>)

    @Query("DELETE FROM sync_outbox")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM sync_outbox")
    fun observeCount(): Flow<Int>
}
