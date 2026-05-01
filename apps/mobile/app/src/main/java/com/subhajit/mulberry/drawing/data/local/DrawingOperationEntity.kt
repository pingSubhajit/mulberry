package com.subhajit.mulberry.drawing.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.subhajit.mulberry.drawing.model.DrawingOperationType
import com.subhajit.mulberry.sync.CanvasKeys

@Entity(tableName = "drawing_operation_entities")
data class DrawingOperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val canvasKey: String = CanvasKeys.SHARED,
    val type: DrawingOperationType,
    val strokeId: String? = null,
    val payload: String? = null,
    val revision: Long,
    val createdAt: Long,
    val clientOperationId: String? = null,
    val serverRevision: Long? = null,
    val syncStatus: String = "LOCAL_ONLY"
)
