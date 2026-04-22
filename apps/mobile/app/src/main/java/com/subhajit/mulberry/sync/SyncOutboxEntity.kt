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
        Index(value = ["status", "createdAt"]),
        Index(value = ["batchId"])
    ]
)
data class SyncOutboxEntity(
    @PrimaryKey val clientOperationId: String,
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
            type = type,
            strokeId = strokeId,
            payload = JsonParser.parseString(payloadJson).asJsonObject.toSyncPayload(type),
            clientCreatedAt = clientCreatedAt
        )

    companion object {
        fun fromDomain(operation: CanvasSyncOperation): SyncOutboxEntity =
            SyncOutboxEntity(
                clientOperationId = operation.clientOperationId,
                type = operation.type,
                strokeId = operation.strokeId,
                payloadJson = operation.payload.toJsonObject().toString(),
                clientCreatedAt = operation.clientCreatedAt
            )
    }
}
