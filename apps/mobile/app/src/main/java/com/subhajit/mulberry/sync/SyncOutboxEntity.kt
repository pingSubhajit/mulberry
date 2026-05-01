package com.subhajit.mulberry.sync

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.JsonParser
import com.subhajit.mulberry.drawing.model.DrawingOperationType

enum class SyncOutboxStatus {
    PENDING,
    IN_FLIGHT
}

@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["status", "sequenceNumber"]),
        Index(value = ["batchId"])
    ]
)
data class SyncOutboxEntity(
    @PrimaryKey val clientOperationId: String,
    val sequenceNumber: Long,
    val canvasKey: String = CanvasKeys.SHARED,
    val type: DrawingOperationType,
    val strokeId: String?,
    val payloadJson: String,
    val clientCreatedAt: String,
    val status: SyncOutboxStatus = SyncOutboxStatus.PENDING,
    val batchId: String? = null,
    val attemptCount: Int = 0,
    val lastSentAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): CanvasSyncOperation =
        CanvasSyncOperation(
            clientOperationId = clientOperationId,
            canvasKey = canvasKey,
            type = type,
            strokeId = strokeId,
            payload = JsonParser.parseString(payloadJson).asJsonObject.toSyncPayload(type),
            clientCreatedAt = clientCreatedAt
        )

    companion object {
        fun fromDomain(operation: CanvasSyncOperation, sequenceNumber: Long): SyncOutboxEntity =
            SyncOutboxEntity(
                clientOperationId = operation.clientOperationId,
                sequenceNumber = sequenceNumber,
                canvasKey = operation.canvasKey,
                type = operation.type,
                strokeId = operation.strokeId,
                payloadJson = operation.payload.toJsonObject().toString(),
                clientCreatedAt = operation.clientCreatedAt
            )
    }
}
